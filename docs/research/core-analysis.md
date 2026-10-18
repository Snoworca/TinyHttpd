# core 구조 분석

- 작성일: 2026-10-17
- 대상: `core/src/main/java/com/snoworca/tinyhttpd/` (포크 기준점 `efb2ebf`, 약 3,000줄)
- 관련 자료: [keep-alive 연구](keep-alive.md), [업스트림 결함 목록](upstream-issues.md), [Servlet API 연구](servlet-api.md)

## 1. 구조

```
NanoHTTPD (abstract)               서버 수명주기, 정적 유틸리티(MIME, 퍼센트 디코딩, SSL 팩토리), 인터셉터 체인
 ├─ ServerRunnable                 accept 루프. 소켓마다 setSoTimeout 후 asyncRunner.exec(ClientHandler)
 ├─ ClientHandler                  연결 하나. while(!closed) session.execute()
 │   └─ HTTPSession : IHTTPSession  요청 파싱 + 바디 파싱 + 핸들러 호출 + 응답 전송 (연결 단위로 재사용)
 ├─ response/Response              상태, 헤더, InputStream 바디, gzip/chunked 결정, 직렬화
 │   └─ ChunkedOutputStream
 ├─ threading/DefaultAsyncRunner   연결마다 new Thread (상한 없음)
 ├─ sockets/*ServerSocketFactory   평문 / SSLServerSocket
 ├─ tempfiles/*                    업로드용 임시 파일
 └─ content/                       ContentType, Cookie, CookieHandler
```

### 역할 분리의 문제
- `HTTPSession` 하나가 **연결 상태, 요청 상태, 파서, 바디 저장소, 디스패처**를 모두 겸한다. 게다가 연결 동안 재사용되므로 요청 간 상태 누수가 구조적으로 생긴다(keep-alive 연구 §3.1).
- `Response`는 값 객체이면서 직렬화기다. 메서드(HEAD)나 프로토콜 버전을 외부에서 setter로 주입받아야 올바르게 동작한다.
- `NanoHTTPD`에 서버 수명주기와 무관한 정적 유틸리티(MIME 표, SSL 팩토리, 디코더)가 섞여 있다.
- 확장 지점이 `protected` 메서드와 public 필드(`asyncRunner`, `hostname`, `myPort`)로 흩어져 있다. 반면 SSE나 스트리밍에 필요한 출력 스트림과 소켓은 private이다(업스트림 #236).

## 2. 영역별 문제

keep-alive 관련 문제는 [keep-alive 연구](keep-alive.md)에 측정 결과와 함께 정리했다. 여기서는 그 밖의 영역을 다룬다.

### 2.1 요청 바디와 업로드 (NAS에 치명적)
- `parseBody()`는 바디 전체를 임시 파일에 쓴 뒤 **`FileChannel.map()`으로 통째로 메모리 매핑**한다. 그 위에서 multipart 경계를 찾는다. 대용량 업로드는 Android에서 `mmap failed: ENOMEM`이나 OOM이 난다(업스트림 #545, #457).
- 1KB(`MEMORY_STORE_LIMIT`) 미만이면 메모리, 그 이상이면 임시 파일로 저장한다. 이 한도는 설정할 수 없다.
- 파트 헤더는 1KB(`MAX_HEADER_SIZE`)까지만 읽는다. 일반 POST 바디는 `new String()`으로 전부 메모리에 올린다.
- 파일 업로드는 "임시 파일에 저장 → 핸들러가 다시 복사"의 2단계다. NAS에서는 디스크 쓰기가 두 배가 되고, 임시 디렉터리(앱 캐시)의 용량 제한에 걸린다.
- 임시 파일 생성 실패를 `throw new Error(e)`로 던진다(`HTTPSession.getTmpBucket()`, `saveTmpFile()`).
- **방향**: multipart를 스트리밍 파서로 바꾼다. 파트마다 `InputStream`을 핸들러에 넘겨 **최종 목적지에 바로 쓰게** 한다. 메모리 매핑은 쓰지 않는다.

### 2.2 응답과 다운로드
- 다운로드 버퍼가 16KB로 고정이고, 파일도 `InputStream` 경유로만 보낼 수 있다. `FileChannel.transferTo`를 쓸 방법이 없다.
- Range 처리는 core에 없고 `webserver/SimpleWebServer`에만 있다.
- gzip 기본 정책: `text/*`와 `*/json`이면 클라이언트가 지원할 때 항상 gzip한다. 이미 `Content-Encoding`이 있어도 다시 압축하고(#463), `Deflater.end()`를 호출하지 않으며(#492), `text/event-stream`(SSE)도 압축해 이벤트가 버퍼에 갇힌다.
- 같은 이름의 응답 헤더를 여러 개 보낼 수 없다(`addHeader`가 실제로는 put, #629). 예외적으로 `Set-Cookie`만 별도 목록으로 처리한다.
- `SimpleDateFormat`을 응답마다 생성한다.

### 2.3 TLS
- `SecureServerSocketFactory`는 프로토콜을 지정하지 않으면 **`getSupportedProtocols()` 전부를 활성화**한다. 구형 Android에서는 SSLv3와 TLS 1.0/1.1이 포함될 수 있다. cipher suite는 설정할 수 없다(#361).
- 핸드셰이크는 워커 스레드의 첫 read에서 지연 수행된다. accept 스레드를 막지는 않지만 **핸드셰이크 전용 타임아웃이 없다.** read 타임아웃 5초가 그 역할을 겸한다.
- 핸드셰이크 실패(`SSLException`)를 받으면 같은 TLS 스트림에 평문 HTTP 500 응답을 쓰려고 시도한다(`HTTPSession.execute()`의 catch 블록).
- `makeSSLSocketFactory`는 예외를 `new IOException(e.getMessage())`로 감싸 **원인(cause)을 버린다.** keystore는 classpath 경로로만 받는다. Android에서는 JKS 대신 BKS나 PKCS12가 필요하다(#523, #516).
- TLS 상세: [tls.md](tls.md).

### 2.4 URL, 헤더, 쿠키
- 경로를 세그먼트로 나누기 전에 전체를 퍼센트 디코딩한다. `%2F`가 `/`가 되어 경로 해석이 바뀐다(#451).
- `decodePercent`는 `URLDecoder`(폼 인코딩용)를 쓴다. 그래서 경로의 `+`가 공백이 된다. 잘못된 `%` 이스케이프에서는 `IllegalArgumentException`이 잡히지 않고 연결 루프 밖으로 빠져나간다(#436).
- 요청 헤더가 `Map<String,String>`이라 같은 이름의 헤더는 마지막 값만 남는다(#630).
- 헤더 맵에 `remote-addr`, `http-client-ip`를 끼워 넣는다. 클라이언트가 같은 이름의 헤더를 보내면 덮어써지고, 반대로 핸들러는 이것이 진짜 헤더인지 구분할 수 없다.

### 2.5 수명주기와 스레드
- `start()`는 bind가 끝날 때까지 `Thread.sleep(10)` 폴링을 한다. 상태 필드가 volatile이 아니다(PR #658).
- accept 루프는 `IOException`을 FINE 로그만 남기고 바로 재시도한다. `EMFILE`(파일 디스크립터 고갈) 같은 지속적 오류에서 CPU를 소모하며 회전한다.
- `stop()`은 graceful shutdown이 아니다(keep-alive 연구 §3.6).

### 2.6 로깅과 오류 처리
- 정상적인 클라이언트 abort(동영상 seek, 다운로드 취소)도 SEVERE로 기록한다(#232, #483).
- 실패를 알리는 방식이 `Error` 던지기, 예외 삼키기, 문자열 비교 예외(`"NanoHttpd Shutdown"`)로 일관성이 없다.

## 3. 재설계 방향 (요약)

| 영역 | 현재 | 방향 |
|---|---|---|
| 연결/요청 | `HTTPSession` 재사용 | `Connection`(연결당 하나) + `Exchange`(요청마다 새로) |
| 요청 바디 | 연결 스트림 노출, 서버는 경계를 모름 | 서버가 프레이밍 소유: `BoundedInputStream`/`ChunkedInputStream`, drain, 100-continue |
| 업로드 | 임시 파일 + mmap | 스트리밍 multipart. 파트를 최종 목적지에 바로 기록 |
| 응답 | `Response`가 직렬화까지 담당 | 응답 헤더/상태는 값, 직렬화는 서버가 RFC 9112 규칙으로 한 곳에서 처리 |
| 다운로드 | InputStream 16KB 복사 | 파일 바디 타입 + `FileChannel.transferTo` + core 수준 Range |
| 스트리밍/SSE | 불가 | 명시적 `flush()`, SSE 헬퍼, 쓰기 타임아웃 감시 |
| 스레드 | 연결마다 무제한 | 상한 워커 풀 + 연결 수 제한 + 감시 스레드 |
| TLS | 지원 프로토콜 전부 활성화 | 안전한 기본값(TLS 1.2+), cipher 설정, 핸드셰이크 타임아웃, 원인 보존 |
| API | `serve(IHTTPSession)` → `Response` | [Servlet API 연구](servlet-api.md) 결과에 따름 |

순서 제안:
1. 연결/요청 분리와 바디 프레이밍. keep-alive 결함 테스트를 하나씩 통과시킨다.
2. 응답 직렬화 재작성(HEAD, 204/304, 길이 불일치, 버퍼링)
3. 스레드 모델과 타임아웃
4. 새 API (Servlet 연구 결과 반영)
5. 스트리밍 multipart, Range, SSE
6. TLS
