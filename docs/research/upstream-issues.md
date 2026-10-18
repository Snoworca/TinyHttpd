# NanoHttpd 업스트림 결함 목록 (HTTP 코어)

- 조사일: 2026-10-17
- 대상: https://github.com/NanoHttpd/nanohttpd 이슈와 PR (`gh issue/pr list|view`로 읽기만 함)
- 표기: **[R]** = 본문과 코멘트까지 읽은 항목, [T] = 제목이나 검색 결과만 본 항목
- URL 형식: `https://github.com/NanoHttpd/nanohttpd/issues/<번호>` (PR도 이 경로로 리다이렉트됨)
- TinyHttpd의 포크 기준점 `efb2ebf`는 PR #546을 머지한 커밋이므로, #546(역방향 DNS 조회 제거)의 수정은 포함되어 있다.

## 전체 요약

keep-alive 결함의 근본 원인은 **한 커넥션에서 `HTTPSession` 객체 하나를 `while(!socket.isClosed()) session.execute()`로 재사용하는 구조**다. 이 구조 때문에 세 가지 문제가 생긴다.

1. 읽지 않은 요청 바디가 다음 요청에 섞인다.
2. 요청별 상태(헤더, range, queryParameterString)가 다음 요청으로 넘어간다.
3. 비정상 입력이 들어오면 무한 루프에 빠진다.

메인테이너(LordFokas)도 #466에서 keep-alive 시스템이 "worse than you can imagine"이라고 인정했다. v3.0 재작성으로 고치겠다고 했지만 출시되지 않았다.

## 1. Keep-alive / persistent connection

| 이슈 | 상태 | 결함 |
|---|---|---|
| **#314** [R] | OPEN | 핸들러가 `parseBody()`를 호출하지 않으면 바디가 다음 요청 앞에 붙는다(`{}POST /bar`). 코멘트의 해결책(finally에서 inputStream 닫기)을 쓰면 클라이언트에서 `connection abort`가 난다. |
| **#217** [R] | CLOSED | 같은 결함. `curl --data` 다음 GET이 `"debug=truePOST /servlet1"`이 된다. elonen은 "serve()가 요청 전체를 읽어야 한다"고 답하고 `getBodySize()`만 추가했다. 바디 자동 skip은 TODO로 남았다. |
| **#356** [R] | OPEN | 빠른 연속 요청에서 두 번째 요청이 "syntax error"가 난다(이전 JSON 바디가 붙음). 헤더와 바디를 다른 TCP 패킷으로 보내는 클라이언트(iOS/AFNetworking), RestTemplate DELETE→GET에서도 재현됐다. |
| **#466** [R] | OPEN | 연속 요청의 두 번째가 실패한다(`SocketException: NanoHttpd Shutdown`). 2초 지연을 주면 정상이다. |
| **#135** [R] | CLOSED | Firefox에서 POST 바디가 다음 GET 요청 라인 앞에 붙었다. 원인은 `parseBody()` 미호출. |
| **#126**, **#150**, PR **#118** [R] | CLOSED | 세션 재사용으로 `headers` 맵이 초기화되지 않았다. Range 요청 다음의 일반 GET이 이전 range로 응답됐고, POST 다음 GET에 이전 Content-Length가 남아 없는 바디를 기다리다 타임아웃됐다. |
| **#311**, **#596**, **#364**, PR **#657** [R] | OPEN | 쿼리가 없는 요청에서 `decodeParms`가 호출되지 않아 이전 `queryParameterString`이 남는다. PR #657은 **OkHttp**로 재현했다. |
| **#621** [R], PR #622 | OPEN | 헤더가 8192바이트를 넘으면 keep-alive 루프가 무한 루프에 빠진다. |
| **#529** [R] | OPEN | 종료 CRLF 없이 `GET / HTTP/1.1`만 보내고 소켓을 닫으면 무한 루프가 된다. 헤더 끝을 못 찾으면 reset 후 재시도를 반복한다. |
| **#362** [R], PR #412 | OPEN | 약 8000자 이상의 URL 이후 EPIPE 로그가 끝없이 찍힌다("non-exiting thread"). |
| **#133** [R] | CLOSED | 클라이언트의 `Connection: close`를 무시하고 소켓을 열어 둔다. 부분 수정 커밋(de2bb1b)을 elonen이 "inferior"라고 평가했다. |
| **#46** [R] | CLOSED | HTTP/1.0 요청에 HTTP/1.1 + keep-alive로 응답해 ab가 hang된다. |
| **#131**, **#462**, **#386** [R] | CLOSED | 타임아웃이 5초 고정이었다(이후 `start(timeout)`로 설정 가능). 블로킹 read 타임아웃이 곧 연결 수명이다. |
| **#460** [R] | OPEN | long-polling을 하면 연결 하나가 스레드 하나를 계속 점유한다. 구조적 한계. |
| **#350** [R] | CLOSED | HTTP 파이프라이닝은 "아마 지원되지 않는다"는 메인테이너 답변. |
| **#57** [R] CLOSED, **#355** [R] OPEN | | `stop()`이 ServerSocket만 닫아 keep-alive 연결이 계속 서비스된다. 스레드풀이나 non-daemon 모드에서는 JVM이 종료되지 않는다. |

참고 PR (MERGED): #81(타임아웃 후 503이 나고 미종료), #96(요청마다 스트림을 감싸 StackOverflow, #91), #40(`isClosed()` 오판으로 무한 루프). OPEN: #602(finally에서 InputStream close).

## 2. TLS / HTTPS

| 이슈 | 상태 | 결함 |
|---|---|---|
| **#264** [R] | CLOSED | HTTPS 포트에 평문 HTTP로 요청하면 `SSLException`인데 클라이언트가 200 OK를 받는다. |
| **#584**, **#299** [R] | OPEN | Android에서 `SSLProtocolException … BAD_WRITE_RETRY`(boringssl) 로그가 최고 속도로 반복된다(Nmap 스캔으로 재현). |
| **#524** [R] | CLOSED | TLS 클라이언트가 끊기면 `GZIPOutputStream` 생성자의 헤더 쓰기에서 broken pipe가 난다. |
| **#514** [R] | CLOSED | `no cipher suites in common`. 핸드셰이크가 첫 read/write에서 지연 수행되므로 예외가 응답 쓰기 시점에 표면화된다. |
| **#361** [R], PR #380 | OPEN | `makeSecure()`로는 protocols만 지정할 수 있고 cipher suite는 설정할 수 없다. |
| **#189**, **#340** [R] | | 대부분 keystore 문제(DSA 키, Android의 JKS 미지원 → BKS 필요). |
| **#523**, **#516** [R], PR #263 | OPEN | Android에서 JKS 로드 불가. `makeSSLSocketFactory(path)`가 classpath 경로를 기대해 혼란을 준다. |
| **#552** [R] | CLOSED | JDK 11/12의 TLS 버그(Java 13에서 수정). |
| **#209**, **#302** [R] | CLOSED | 특정 클라이언트나 VPN에서 HTTPS만 hang된다. 원인 미규명. |
| **#228** [R], PR #230 | CLOSED | PushbackInputStream의 블로킹 read로 처리량이 급감했다(SSL 약 60 req/s, 비SSL 1000+ req/s). |
| #550, #455 [T] | | mutual TLS 미지원, HTTP/HTTPS 동시 서빙 불가. |

"TLS 핸드셰이크가 accept 스레드를 블로킹한다"고 명시한 이슈는 찾지 못했다.

## 3. SSE / 스트리밍 / 장수명 응답

| 이슈 | 상태 | 결함 |
|---|---|---|
| **#236** [R] | OPEN | SSE 미지원. outputStream과 서버 소켓이 private이라 확장할 수 없어 보고자가 클래스 전체를 복사해 포크했다. 우회 코드는 `send()` 오버라이드 + `wait/notify`. |
| **#449**, **#632** [R] | OPEN | `serve()`가 InputStream만 반환할 수 있어 템플릿 출력을 전부 메모리에 버퍼링해야 한다. 메인테이너는 OutputStream 노출을 거부했다. |
| **#51**, #94, #127 | CLOSED | 과거에 `available()`을 Content-Length로 사용해 스트리밍 응답이 깨졌다. |
| **#152** [R] | CLOSED | Icecast 릴레이 오디오가 1초 후 끊겼다. chunked로 해결. |
| **#195** [R] | CLOSED | gzip 응답에 길이 정보가 없어 브라우저가 대기했다. PR #193(gzip이면 chunked)로 수정. |
| **#555** [R] | OPEN | 클라이언트가 사라지면 서버 write가 무기한 블록된다. 끊김 감지 부재. |
| **#397**, **#392** [R] | CLOSED | 끊긴 연결에 대한 send 예외 후 소켓이 닫히지 않아 로그가 무한 반복됐다. |
| **#232** [R] OPEN, **#483** [R], #624 | | 동영상 seek 시 정상적인 클라이언트 abort를 에러로 처리한다. |
| **#211**, **#617** [R], #293 | OPEN | chunked **요청** 바디를 디코딩하지 않아 원시 청크가 그대로 들어오고, 읽다가 타임아웃된다. |

## 4. 기타 코어 결함

**스레드와 동시성**
- **#86** [R] OPEN: `DefaultAsyncRunner`가 연결마다 스레드를 무제한 생성한다(DoS, Slowloris).
- **#139** [R] CLOSED: 스레드가 연결 단위라 몇 분씩 살아 있으므로 스레드풀 주입 효과가 없다.
- **PR #658** [R] OPEN: `ServerRunnable` 필드가 volatile이 아니라 `start()` 대기 루프가 끝나지 않을 수 있다.
- **#651** [R], #568 [T]: 요청 순서 보장 불가, Android 런타임 종료 중 스레드 시작 시 InternalError.

**multipart, 업로드, 메모리**
- **#545**, **#457** [R] OPEN: `parseBody()`가 업로드 바디 전체를 메모리 매핑해 Android에서 OOM이 난다(1GB).
- **#279**, **#581** [R]: 매핑된 임시 파일을 Windows에서 삭제할 수 없다. `MEMORY_STORE_LIMIT`(1024B)는 설정할 수 없다.
- **#112**, **#41** [R]: boundary 탐색이 느렸다(PR #180으로 수정). **#393** [R]: 파트 헤더 길이 계산 오류. **#557** [R]: 업로드 파일이 가끔 빈 파일이 된다.
- **#620** [T] [SECURITY], PR **#626** [T]: CVE-2020-13697 수정이 머지되지 않았다.

**헤더와 URL 파싱**
- **#630** [R]: 같은 이름의 요청 헤더는 마지막 값만 남는다. **#629** [R]: 같은 이름의 응답 헤더를 여러 개 보낼 수 없다.
- **#451** [R]: 경로를 세그먼트로 나누기 전에 디코딩해 `%2F`가 `/`가 된다. **#436** [R]: 잘못된 `%` 이스케이프에서 예외가 처리되지 않는다.

**HEAD, 상태 코드, Content-Length**
- **#608** [R]: HEAD 요청이 정확히 두 번에 한 번 실패한다(원인 미상). → [keep-alive 연구](keep-alive.md) KA-02에서 HEAD 응답에 바디가 실려 나가는 것을 확인했다. 이 이슈의 유력한 원인이다.
- **#577** [R], PR #618: 204와 101 응답에 Content-Length를 보낸다(RFC 7230 §3.3.2 위반).
- **#87**, **#395** [R]: SimpleWebServer가 Content-Length를 두 번 출력한다. **#498** [T]: `ERR_CONTENT_LENGTH_MISMATCH`.

**gzip**
- **#463** [R]: 이미 인코딩된 응답을 다시 gzip한다. **#492** [R]: Deflater를 `end()`하지 않아 네이티브 리소스가 누수된다(Android StrictMode 경고).

**Android와 성능**
- **#294** [R]: 대용량 다운로드가 20~30KB/s. **#391** [R]: 스트리밍이 1.5MB/s에서 막힌다.
- **#425** [R]: Range 미지원으로 타임아웃. 사용자들이 AndroidAsync로 이전했다.
- #515 [T] StrictMode "Untagged socket", **#521** [R] Oreo에서 서버가 저절로 멈춘다.

## 검색 공백

- OkHttp 관련은 #270 [T]와 PR #657뿐이다.
- "idle keep-alive 커넥션이 OkHttp에서 stale connection reset을 낸다"는 이슈는 찾지 못했다. → keep-alive 연구에서 직접 측정한다.
