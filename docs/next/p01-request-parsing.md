# P1. 요청 파싱과 요청 바디 프레이밍

- 목표: 서버가 요청 메시지의 경계를 전부 소유한다. 핸들러는 경계가 정해진 바디 스트림만 본다.
- 선행: P0
- 관련: [keep-alive.md](../research/keep-alive.md) §3.1~3.3, §4.1~4.4 · [servlet-api.md](../research/servlet-api.md) §4.2 · RFC 9112 §2~§7, RFC 9110 §10.1.1
- 이 단계가 통과시킬 결함 테스트: **KA-01, KA-04, KA-06, KA-07, KA-08, KA-10, KA-11, KA-13, KA-14, KA-15, KA-16**. KA-14는 lingering close(P1-15)로 맞춘다. KA-08 테스트는 요청 라인 오류(400)의 `Connection: close`만 확인하므로 이 단계가 소유한다. 서버가 닫기로 한 일반 응답의 `Connection: close`는 P2의 RS-23이 맡는다.

## 설계 요약

```
Acceptor ──accept──▶ Connection (소켓, InputBuffer, 출력 스트림, 상태)
                       loop:
                         r = parser.readRequest(exchange)   // REQUEST | EOF | TIMEOUT | BAD(status)
                         BAD     → writeError(status), close
                         EOF     → close (응답 없음)
                         REQUEST → dispatch(handler) → finishResponse → drainBody → persistent?
```

- `Connection`은 연결당 하나, `Exchange`는 요청마다 새로 만든다. 요청별 상태는 `Exchange`에만 둔다.
- 연결 루프는 **예외가 아니라 결과 값으로** 끝난다. `SocketException` 메시지 비교는 쓰지 않는다.
- 이 단계에서 스레드 모델은 임시로 "연결당 스레드 하나"다. 상한과 풀은 P3에서 붙인다. KA-12는 P3까지 실패한다.
- 응답 직렬화는 P2에서 완성한다. 이 단계에서는 P2를 미리 당겨 쓰지 않도록, 핸들러 응답은 단순 고정 길이로만 쓰는 임시 구현을 둔다(P1-03).

## 작업

### P1-01 공개 API 골격 `[ ]`
- 패키지 `com.snoworca.tinyhttpd.http`에 servlet-api.md §4.2의 인터페이스를 선언한다: `Handler`, `Filter`, `FilterChain`, `HttpException`, `Request`, `Response`, `EventStream`, `MultipartReader`, `MultipartLimits`, `Part`, `UpgradedConnection`, `Cookie`.
- 상태 코드는 `int`로 쓰고, 상수는 `HttpStatus` 클래스에 둔다(`OK = 200` …). 이유 문구(reason phrase) 표도 여기에 둔다.
- `HttpException`은 상태와 메시지 외에 **응답 헤더**를 담을 수 있게 한다(`withHeader(name, value)`). `Retry-After`(P6-01), `Allow`(P8-01) 같은 헤더가 필요하다. servlet-api.md §4.2 스케치를 함께 고친다.
- 아직 구현하지 않는 메서드(`readMultipart`, `startEventStream`, `upgrade`, `sendFile`)는 엔진에서 `UnsupportedOperationException("P5")`처럼 **담당 단계를 적어** 던진다.
- 서버 진입점 `com.snoworca.tinyhttpd.TinyHttpd`와 최소 빌더(`port`, `bindAddress`, `handler`, `threadFactory`)를 만든다. 나머지 옵션은 P3/P4에서 추가한다.
- 최소 `ServerStats` 스냅숏(`acceptedConnections`, `openConnections`, `busyWorkers`)을 만든다. P1-02의 테스트가 쓰고, P3-09에서 항목을 늘린다.
- `Request`에 servlet-api.md §4.2 스케치에 없던 `getServerName()`/`getServerPort()`를 추가한다. Servlet과 같은 의미다: absolute-form이면 그 호스트, 아니면 `Host` 헤더의 호스트와 포트. `HostAllowlistFilter`(P4-12)가 쓴다. 스케치 문서도 고친다.
- 완료 조건: 인터페이스가 컴파일되고 animal-sniffer를 통과한다. javadoc에 servlet-api.md §4.1 원칙 1(이름이 같으면 의미도 같다)과 Servlet과 다른 점을 적는다.

### P1-02 KA/KB 테스트를 새 엔진으로 전환 `[ ]`
- `ProbeServer`(구 `NanoHTTPD` 상속)를 새 `Handler` 기반 `ProbeHandlers`로 다시 쓴다. 경로는 그대로 둔다: `/hello`, `/second`, `/query`, `/ignore-body`, `/chunked`, `/short`, `/read-all`, `/parse`.
  - `/parse`(구 `parseBody`)는 `getInputStream()`을 끝까지 읽고 바이트 수를 돌려준다. KA-04/KA-13의 의도(서버가 넘긴 바디 길이를 그대로 보고)는 같다.
  - `/chunked`는 길이를 지정하지 않고 `getOutputStream()`에 출력 버퍼(8 KB)보다 많이 쓴다. 작으면 P2 규칙에 따라 Content-Length가 붙어 KA-03의 의도가 사라진다.
  - `/short`는 `setContentLengthLong(10)` 후 5바이트만 쓴다.
- 워커 스레드 기록(`workerThreads`)은 빌더 옵션 `threadFactory`로 받는다. 연결 수(`acceptedConnections`)는 `ServerStats`에서 읽는다.
- **`awaitWorkersIdle`의 의미를 바꾼다.** 지금은 "기록된 워커 스레드가 모두 종료됨"(`isAlive()`)을 센다. P3의 워커 풀은 스레드를 `workerKeepAlive` 동안 살려 두므로 이 정의로는 KA-06/07이 P3에서 다시 실패한다. 새 정의는 "`busyWorkers == 0` 그리고 `openConnections == 0`"이다. 무한 루프 검출은 지금처럼 워커 스레드의 CPU 시간으로 한다. 이 변경은 단언의 의도(연결이 정리되고 회전하지 않음)를 유지하므로 keep-alive.md §2.1에 이유를 적는다.
- **`KnownDefect` 카테고리를 클래스 단위에서 메서드 단위로 옮긴다.** 지금은 `KeepAliveDefectTest` 클래스 전체에 붙어 있어 테스트별로 뗄 수 없다. KB도 같은 방식으로 메서드마다 임시로 붙인다.
- 구 엔진용 `ProbeServer`는 삭제한다. 구 엔진의 측정 결과는 keep-alive.md §2에 기록되어 있다.
- 새 엔진이 요청을 처리하기 전까지는 KB도 실패한다. 그 기간에만 KB 메서드에 `KnownDefect`를 붙이고, 각 KB가 통과하는 작업에서 뗀다(KB-01·03·04: P1-03, KB-02: P1-04, KB-05·06: P1-09).
- 완료 조건: KA/KB가 새 엔진을 대상으로 컴파일된다. 각 테스트의 단언은 바꾸지 않는다(바꿔야 한다면 keep-alive.md에 이유를 적는다).

### P1-03 Connection과 연결 루프 `[ ]`
- `server.Connection`: 소켓, `InputBuffer`, 출력 스트림, 요청 수, 마지막 활동 시각, 현재 단계(`IDLE`, `READING_HEADERS`, `HANDLING`, `WRITING`, `DRAINING`, `CLOSING`). 단계는 `volatile`로 두어 P3 watchdog이 읽을 수 있게 한다.
- acceptor는 `ServerSocket`으로 고정한다. `ServerSocketChannel`로 바꿀지는 P0-07(JVM)과 AND-05(Android)를 거쳐 P5-04에서 정한다. 바꾸게 되더라도 영향이 acceptor와 `Connection` 생성부에 머물도록 소켓 생성을 한 곳에 모은다.
- `server.Acceptor`: `ServerSocket.accept()` 루프. `accept` 실패가 반복되면(예: `EMFILE`) 지수 백오프(10 ms → 최대 1 s)를 둔다(core-analysis.md §2.5).
- 연결 루프 결과 값: `REQUEST`, `EOF`(첫 바이트 전 EOF 포함), `TIMEOUT`, `BAD(status)`.
- 핸들러가 던진 예외 처리 규칙(P2에서 직렬화와 함께 완성):
  - `HttpException`이고 미커밋 → 그 상태로 오류 응답
  - 그 밖의 예외이고 미커밋 → 500
  - 커밋 후 → 연결을 닫는다(응답을 정상으로 끝낼 수 없다)
- 임시 응답 경로: 핸들러가 쓴 바이트를 메모리에 모아 `Content-Length`로 보낸다. 요청에 `Connection: close`가 있으면 응답에도 `Connection: close`를 쓰고 닫는다(KB-03). P2-01에서 교체한다.
- 완료 조건: KB-01, KB-03, KB-04가 새 엔진에서 통과한다. 유휴 타임아웃은 P3 전까지 `soTimeout` 5 s로 임시 유지한다(KB-04, KB-05가 이 값에 맞춰져 있다).

### P1-04 입력 버퍼와 줄 읽기 `[ ]`
- `InputBuffer`: 연결당 고정 `byte[]`(기본 8 KB) 하나. `mark/reset`과 `BufferedInputStream`을 쓰지 않는다.
- 줄 끝: `CRLF`를 기본으로 하고, 단독 `LF`도 받아들인다(RFC 9112 §2.2 허용). **단독 `CR`은 400**이다.
- 요청 라인 앞의 빈 줄은 최대 1개까지 건너뛴다(RFC 9112 §2.2 권고).
- 버퍼에 남은 바이트는 다음 요청의 시작으로 그대로 쓴다(파이프라이닝, KB-02).
- 버퍼는 늘리지 않는다. 그래서 **줄 하나의 상한은 `inputBufferBytes - 2`(줄 끝 CRLF)** 다. 기본값으로는 약 8 KB다(Tomcat의 기본 헤더 상한 8 KB와 같은 수준). 줄이 이 상한을 넘으면:
  - 요청 라인이면 414
  - 헤더 줄이면 431
- 헤더 전체 상한(`maxHeaderBytes`, 16 KB)은 줄들의 합에 대한 검사다. 이미 처리한 줄은 버퍼에서 비우므로 헤더 전체가 버퍼보다 커도 된다.
- 빌더 검사: `inputBufferBytes ≥ maxRequestLineBytes + 2`. 기본값은 8192 / 8190이다(README §5).
- 완료 조건: RQ-01~03, RQ-21, KB-02 통과.

### P1-05 요청 라인 파싱 `[ ]`
- 형식: `method SP request-target SP HTTP-version`. 공백이 정확히 하나가 아니면 400.
- method: `tchar`만 허용. 알려진 메서드 목록과 무관하게 받아들이고, 처리 여부는 핸들러/라우터(405)가 정한다. 대소문자를 구분한다(`get`은 `GET`이 아니다).
- request-target:
  - origin-form(`/path?query`) — 기본
  - absolute-form(`http://host/path`) — 받아들이고 경로와 쿼리만 쓴다. 호스트는 `Host` 헤더보다 우선한다(RFC 9112 §3.2.2)
  - asterisk-form(`*`) — `OPTIONS`일 때만. 그 밖은 400
  - authority-form — `CONNECT`일 때만 문법상 유효. 서버는 지원하지 않으므로 501
- 버전: `HTTP/1.0`, `HTTP/1.1`을 처리한다. `HTTP/1.x`(x ≥ 2)는 HTTP/1.1로 처리한다(RFC 9110 §2.5). 다른 major(`HTTP/2.0`, `HTTP/0.9` 형식 포함)는 505, 형식 오류는 400.
- 한도: 요청 라인이 `maxRequestLineBytes`(8190 B)를 넘으면 **414 후 닫기**.
- 완료 조건: RQ-19, RQ-20, RQ-22, RQ-23 통과.

### P1-06 헤더 파싱 `[ ]`
- field-name: `tchar`만. 이름과 `:` 사이 공백은 **400**(RFC 9112 §5.1, 요청 스머글링 방지).
- obs-fold(줄 앞 공백으로 이어지는 헤더)는 **400**.
- 값: 앞뒤 OWS를 잘라 낸다. `NUL`, 단독 `CR`/`LF`가 있으면 400.
- 저장: 순서를 보존하는 이름/값 목록 + 대소문자 무시 조회. 같은 이름의 헤더를 모두 보존한다(업스트림 #630). 요청마다 할당을 줄이도록 `ArrayList` 두 개(이름, 값)에 넣고 조회는 선형 탐색으로 한다. 헤더 수가 100개 이하이므로 해시맵이 필요 없다.
- 한도: 헤더 줄 하나가 줄 상한(P1-04) 초과, 헤더 전체 `maxHeaderBytes`(16 KB) 초과, `maxHeaderCount`(100) 초과 → **431 후 닫기**. 431 응답 뒤에도 워커가 회전하지 않아야 한다(KA-06).
- HTTP/1.1에서 `Host`가 없거나 여러 개면 400(RFC 9112 §3.2).
- 원격 주소를 헤더 맵에 넣지 않는다(구 `remote-addr`, `http-client-ip`). `Request.getRemoteAddr()`로만 제공한다(core-analysis.md §2.4).
- 헤더 도중 EOF → 응답 없이 닫는다(KA-07).
- 완료 조건: KA-06, KA-07, RQ-04~07, RQ-24~27 통과. 응답 후 닫는 테스트(KA-06, RQ-26, RQ-27)의 Linux CI 통과는 P1-15 뒤에 확인한다.

### P1-07 Exchange `[ ]`
- `server.Exchange implements Request`: 요청 라인, 헤더, 원본 쿼리, 바디 스트림, 속성, 원격/로컬 주소. **요청마다 새로 만든다.**
- 쿼리 파라미터 맵은 처음 조회할 때 원본 쿼리에서 만든다(지연 파싱). 쿼리 디코딩 규칙(잘못된 `%` 처리 등)은 P4-05에서 확정하고, 이 단계에서는 UTF-8 퍼센트 디코딩과 `+` → 공백만 구현한다.
- 완료 조건: KA-16 통과.

### P1-08 요청 바디 프레이밍 결정 `[ ]`
RFC 9112 §6.3의 순서대로 결정한다.

| 조건 | 처리 | 테스트 |
|---|---|---|
| HTTP/1.0 요청에 `Transfer-Encoding` | 400 + 닫기 (RFC 9112 §6.1) | RQ-10 |
| `Transfer-Encoding`과 `Content-Length`가 함께 | 400 + 닫기 (스머글링 방지) | RQ-08 |
| `Transfer-Encoding`의 마지막 코딩이 `chunked` | `ChunkedInputStream` | KA-04 |
| `Transfer-Encoding`의 마지막이 chunked가 아님 | 400 + 닫기 (RFC 9112 §6.3) | RQ-09 |
| 마지막은 chunked지만 지원하지 않는 코딩이 앞에 있음(`gzip, chunked`) | 501 + 닫기 (RFC 9112 §6.1) | RQ-09 |
| `Content-Length`가 숫자가 아니거나 음수, `Long` 범위 초과 | 400 + 닫기 | KA-11, RQ-11 |
| `Content-Length`가 여러 개(목록 포함) — 값이 모두 같음 | 그 값으로 처리 | RQ-12 |
| `Content-Length`가 여러 개 — 값이 다름 | 400 + 닫기 | RQ-12 |
| `Content-Length: n` | `BoundedInputStream(n)` | KA-10 |
| 둘 다 없음 | 빈 바디 | KA-13 |

- 전역 바디 상한 `maxRequestBodyBytes`는 기본값 없음(-1)이다. NAS 업로드는 크기가 크고, 상한은 핸들러(또는 라우트)가 정한다. 상한을 지정하면:
  - `Content-Length`가 더 크면 핸들러를 부르지 않고 413 + 닫기
  - chunked면 `ChunkedInputStream`이 누적 바이트를 세다가 넘는 순간 `HttpException(413)`에 해당하는 `IOException`을 던지고 닫기로 표시한다(RQ-28)
- 완료 조건: 표의 테스트와 RQ-28이 모두 통과한다.

### P1-09 `BoundedInputStream` `[ ]`
- `n`바이트를 읽으면 `-1`. `available()`도 남은 길이를 넘지 않는다.
- 상대가 `n`바이트 전에 EOF를 보내면 `TruncatedBodyException extends IOException`을 던지고, 연결을 닫기로 표시한다.
- `close()`는 소켓을 닫지 않는다. 남은 바이트는 drain(P1-12)이 처리한다.
- 읽은 바이트 수를 기록한다(drain과 접근 로그에 쓴다).
- 완료 조건: KA-10, RQ-16, KB-05, KB-06 통과.

### P1-10 `ChunkedInputStream` `[ ]`
- 청크 크기: 16진수, 앞의 0 허용, `Long` 범위 초과는 오류. 청크 크기 줄(chunk-ext 포함)은 `maxChunkSizeLineBytes`(1 KB)까지. chunk-ext는 무시한다.
- 데이터 뒤 `CRLF`가 아니면 오류.
- trailer: `maxTrailerBytes`(8 KB)까지 읽어 버린다. 노출하지 않는다(필요해지면 `Request.getTrailers()`를 추가한다).
- 오류는 `MalformedBodyException extends IOException` + 닫기 표시. 아직 핸들러가 응답을 커밋하지 않았으면 서버가 400을 보낸다.
- 완료 조건: KA-04, RQ-13~15 통과.

### P1-11 `Expect: 100-continue` `[ ]`
- `Expect: 100-continue`이고 HTTP/1.1이면, 핸들러가 **바디 스트림을 처음 읽을 때** `HTTP/1.1 100 Continue\r\n\r\n`을 보내고 flush한다.
- 핸들러가 바디를 읽지 않고 응답하면 100을 보내지 않는다. 이 경우 클라이언트가 바디를 보낼지 알 수 없으므로 drain하지 않고 **연결을 닫는다**(RFC 9110 §10.1.1이 허용).
- 바디 길이가 0이면(Content-Length: 0) 100을 보내지 않는다.
- HTTP/1.0의 `Expect`는 무시한다. `100-continue`가 아닌 기대값은 417 + 닫기.
- 완료 조건: KA-15, RQ-18 통과.

### P1-12 핸들러 이후 drain `[ ]`
- 응답을 끝내고(flush) 나서 바디에 남은 바이트를 `maxDrainBytes`(64 KB)까지 읽어 버린다.
- 한도를 넘거나, `bodyReadTimeout` 안에 끝나지 않거나, 바디가 잘리면(`TruncatedBodyException`) 연결을 닫는다.
- 응답을 drain보다 먼저 보내므로, 업로드를 거부한 응답(413 등)은 클라이언트가 받는다. 이 경우 연결 종료는 lingering close(P1-15)가 처리한다.
- 완료 조건: KA-01, RQ-17 통과(Linux CI 포함).

### P1-13 파싱 오류 응답 `[ ]`
- 대상 상태: 400, 408, 413, 414, 417, 431, 501, 505.
- 핸들러를 부르지 않고 엔진이 직접 쓴다. 본문은 짧은 `text/plain`, 항상 `Connection: close`, 쓴 뒤 닫는다.
- 응답 바이트는 상태별로 미리 만든 고정 부분(상태 줄, `Content-Type`, `Content-Length`, `Connection: close`, 본문)에 캐시한 `Date` 줄(P2-04, 1초마다 갱신)만 끼워 조립한다. RFC 9110 §6.6.1은 4xx/5xx에도 `Date`를 요구한다. 요청마다 문자열을 만들지 않는다.
- P2-04의 `Date` 캐시를 이 작업에서 먼저 만든다.
- 완료 조건: KA-08 통과. 모든 RQ 오류 테스트가 상태 줄과 `Connection: close`를 확인한다.

### P1-14 파서 견고성 테스트 `[ ]`
- 무작위 입력 테스트: 시드를 고정한 난수로 요청 바이트를 변형(바이트 삭제·삽입·교체, CR/LF 주입, 길이 필드 변경)해 1만 건을 보낸다.
- 검사: 처리되지 않은 예외가 없다, 워커가 회전하지 않는다(CPU 시간), 연결이 결국 닫히거나 정상 응답한다.
- 실패한 입력은 시드와 바이트를 출력해 RQ 테스트로 고정한다.
- 완료 조건: 1만 건 통과. CI에서는 1천 건만 돌린다(시간 제한).

### P1-15 lingering close (평문) `[ ]`
- 읽지 않은 입력을 남긴 채 닫으면 Linux(Android 포함)는 RST를 보내고, 클라이언트가 아직 읽지 않은 응답이 버려질 수 있다(keep-alive.md §2.1 KA-14). 이 위험은 P1의 모든 "응답 후 닫기" 경로에 있다: 431(KA-06, RQ-26), 414(RQ-20), 400(RQ-14, 15), 413(RS-23), drain 초과(RQ-17). 그래서 P3이 아니라 이 단계에서 구현한다.
- 서버가 먼저 닫는 경우의 순서:
  1. 출력 버퍼 flush
  2. `shutdownOutput()` (FIN 전송)
  3. 입력을 **EOF가 오거나 `lingerTimeout`(2 s)이 지날 때까지** 읽어 버린다. 바이트 상한은 없다. keep-alive.md §4.9의 "64 KB 상한"은 고친다. 64 KB만 읽고 닫으면 KA-14의 256 KB 잔여 데이터 때문에 RST가 나간다.
  4. `close()`
- `soTimeout`만 있으면 되므로 watchdog이 필요 없다. 클라이언트가 먼저 닫은 경우(EOF)와 타임아웃으로 닫는 경우는 바로 `close()`한다.
- TLS는 P7-05에서 다룬다. 워커 밖에서 닫는 경로(acceptor의 503 거절)는 P3-07에서 다룬다.
- keep-alive.md §4.9를 이 규칙에 맞게 고친다.
- 완료 조건: Linux CI에서 KA-14, RQ-17, RQ-20, KA-06 통과. KA-14의 소유 단계가 P3에서 P1로 바뀐다(keep-alive.md 표에 적는다).

## 새 테스트 목록 (RQ)

| ID | 시나리오 | 기대 |
|---|---|---|
| RQ-01 | 줄 끝이 단독 LF | 정상 처리 |
| RQ-02 | 헤더에 단독 CR | 400 + close |
| RQ-03 | 요청 라인 앞 빈 줄 1개 | 정상 처리 |
| RQ-04 | `Host : x` (콜론 앞 공백) | 400 + close |
| RQ-05 | obs-fold 헤더 | 400 + close |
| RQ-06 | HTTP/1.1, `Host` 없음 | 400 + close |
| RQ-07 | `Host` 두 개 | 400 + close |
| RQ-08 | `Transfer-Encoding: chunked` + `Content-Length` | 400 + close, 핸들러 미호출 |
| RQ-09 | `Transfer-Encoding: gzip` / `gzip, chunked` | 400 + close / 501 + close |
| RQ-10 | HTTP/1.0 + `Transfer-Encoding` | 400 + close |
| RQ-11 | `Content-Length: 99999999999999999999` | 400 + close |
| RQ-12 | `Content-Length: 5, 5` / `5, 6` | 정상 / 400 |
| RQ-13 | 청크 크기 `zz`, 17자리 16진수 | 400 + close |
| RQ-14 | 2 KB chunk-ext | 400 + close |
| RQ-15 | 16 KB trailer | 400 + close |
| RQ-16 | CL 10, 5바이트 보내고 EOF | 핸들러가 `TruncatedBodyException`, 연결 닫힘 |
| RQ-17 | CL 1 MB, 핸들러가 읽지 않음 | 응답 수신 후 서버가 닫음 |
| RQ-18 | `Expect: foo` | 417 + close |
| RQ-19 | `HTTP/2.0` / `HTTP/1.2` / `HTTP/1.1x` | 505 / 1.1로 처리 / 400 |
| RQ-20 | 요청 라인 8 KB + 1 | 414 + close |
| RQ-21 | 요청을 1바이트씩 나눠 보냄 | 정상 처리 |
| RQ-22 | `GET http://h/p?q=1 HTTP/1.1` | 경로 `/p`, 쿼리 `q=1` |
| RQ-23 | `OPTIONS * HTTP/1.1` / `GET * HTTP/1.1` | 핸들러 호출 / 400 |
| RQ-24 | 같은 이름 헤더 3개 | `getHeaders`가 순서대로 3개 |
| RQ-25 | 클라이언트가 `remote-addr: 1.2.3.4` 헤더 전송 | `getRemoteAddr()`는 실제 주소 |
| RQ-26 | 헤더 101개(각각 짧음) / 9 KB짜리 헤더 줄 하나 | 431 + close / 431 + close |
| RQ-27 | 7 KB짜리 헤더 줄 3개(줄마다 상한 이내, 합계 21 KB) | 431 + close (`maxHeaderBytes`) |
| RQ-28 | `maxRequestBodyBytes` 1 KB, chunked로 2 KB | 핸들러가 `IOException`, 413 또는 닫힘 |

헤더 타임아웃(408)은 감시 스레드가 필요하므로 P3의 TH 테스트에서 다룬다.

## 완료 조건 (단계)
- 위 KA 10개와 KA-14(P1-15)에서 `KnownDefect`를 제거했고 기본 `test`에서 통과한다(Linux CI 포함).
- RQ 테스트가 전부 통과한다. KB-01~06이 새 엔진에서 통과한다.
- keep-alive.md §2.1 표에 "수정됨(P1, 커밋)" 열을 추가했다.
