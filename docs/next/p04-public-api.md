# P4. 공개 API 완성

- 목표: P1에서 선언한 API 골격을 완성한다. 서버 조립(빌더), 필터, 오류 처리, 경로와 쿼리 디코딩, 쿠키, 로깅을 정한다.
- 선행: P1~P3
- 관련: [servlet-api.md](../research/servlet-api.md) §4 · [core-analysis.md](../research/core-analysis.md) §1, §2.4, §2.6 · 결정 D-01~D-04, D-07
- 원칙(servlet-api.md §4.1):
  1. 이름이 같으면 의미도 같다.
  2. Servlet에서 가져오지 않는 것을 정해 둔다.
  3. 서버가 프레이밍을 소유한다.
  4. API 21 표준 라이브러리만 쓴다.

## 패키지 구성

| 패키지 | 내용 | 공개 여부 |
|---|---|---|
| `com.snoworca.tinyhttpd` | `TinyHttpd`, `TinyHttpd.Builder`, `ServerStats`, `TlsConfig`(P7-02) | 공개 |
| `com.snoworca.tinyhttpd.http` | `Request`, `Response`, `Handler`, `Filter`, `FilterChain`, `HttpException`, `HttpStatus`, `Cookie`, `EventStream`, `MultipartReader`, `MultipartLimits`, `Part`, `UpgradedConnection`, `ErrorHandler`, `MimeTypes`, `RequestWrapper`, `ResponseWrapper`(P4-02), `FileResponses`, `FileResource`(P5-06), `EventBroadcaster`(P6-04) | 공개 |
| `com.snoworca.tinyhttpd.http.filter` | `AccessLogFilter`, `HostAllowlistFilter`, `ForwardedHeadersFilter`, `GzipFilter`(P5-07) | 공개 |
| `com.snoworca.tinyhttpd.http.util` | `Html`(이스케이프), `Base64` | 공개. 구 `com.snoworca.tinyhttpd.util`(삭제 예정)과 섞지 않는다 |
| `com.snoworca.tinyhttpd.log` | `TinyLog`(로깅 추상화) | 공개 |
| `com.snoworca.tinyhttpd.server` | `Connection`, `Exchange`, `WorkerPool`, 파서, 스트림 | **내부.** 가능한 한 package-private로 둔다. public이 필요한 클래스는 javadoc에 "내부 API, 예고 없이 바뀜"을 적는다 |

## 작업

### P4-01 빌더와 설정 `[ ]`
- `TinyHttpd.builder()` → 불변 설정 객체 `ServerConfig`. 빌더는 [README §5](README.md#5-기본값-표)에서 소속이 "core 빌더"인 값을 옵션으로 가진다. `MultipartLimits`, `TlsConfig`, `WebSocketHandler`, `SessionFilter`에 속한 값은 각자의 객체가 가진다.
- 뒤 단계(P5 `sendFile` 풀, P6 `maxEventStreams`·`eventQueue*`, P8 `maxUpgradedConnections`)의 core 빌더 옵션도 **이 작업에서 모두 선언**한다. 동작은 해당 단계에서 구현한다. 그래서 API-01이 P4에서 기본값 전체를 확인할 수 있다.
- 옵션 묶음: 주소(`port`, `bindAddress`), 핸들러(`handler`, `filter`, `errorHandler`), 한도, 타임아웃, 스레드(`maxWorkers`, `threadFactory`, `daemon`), TLS(`tls(TlsConfig)`, P7-02에서 구현. `SSLServerSocketFactory`를 받는 옵션은 두지 않는다), 로깅(`logger`). servlet-api.md §4.2 예제의 `.sslServerSocketFactory(...)`도 `.tls(...)`로 고친다.
- `build()`에서 검사한다: 양수 여부, `inputBufferBytes ≥ maxRequestLineBytes + 2`, `maxConnections ≥ maxWorkers`, 핸들러 필수. 잘못되면 `IllegalArgumentException`에 옵션 이름을 담는다.
- `port(0)`이면 임의 포트를 쓴다. `getPort()`는 `start()` 뒤 실제 포트를 돌려준다(테스트가 쓴다).
- 완료 조건: API-01, API-02 통과.

### P4-02 필터 체인 `[ ]`
- 등록한 순서대로 실행한다. 마지막에 핸들러가 실행된다. 필터가 `chain.doFilter`를 부르지 않으면 거기서 끝난다.
- 체인 객체는 요청마다 하나 만든다(인덱스 하나만 가진 작은 객체).
- 구 인터셉터(`NanoHTTPD.addHTTPInterceptor`)는 필터로 대체하고 만들지 않는다.
- 필터나 핸들러에서 요청/응답을 감싸는 래퍼를 쓸 수 있게 `RequestWrapper`, `ResponseWrapper`(위임 기본 구현)를 제공한다. Servlet의 `HttpServletRequestWrapper`와 같은 역할이다.
- 완료 조건: API-03, API-04 통과.

### P4-03 `Request` 나머지 구현 `[ ]`
- `getScheme()`/`isSecure()`: 소켓이 `SSLSocket`이면 `https`. 프록시 헤더(`X-Forwarded-Proto`)는 core가 해석하지 않는다. 필요하면 `ForwardedHeadersFilter`(P4-14)를 쓴다(P9 SEC-08).
- `getDateHeader`: IMF-fixdate, RFC 850, asctime 세 형식을 받는다(RFC 9110 §5.6.7). 형식 오류는 `IllegalArgumentException`(Servlet과 같음). 없으면 -1.
- `getContentLengthLong()`: 프레이밍 결정(P1-08) 결과. chunked이면 -1.
- `getCharacterEncoding()`: Content-Type의 `charset` 파라미터. 없으면 `null`.
- 속성 맵은 처음 `setAttribute`를 부를 때 만든다(요청당 할당 최소화).
- 완료 조건: API-05~07 통과.

### P4-04 오류 응답 확장점 `[ ]`
- `ErrorHandler.render(int status, String message, Request req, Response res)`: 엔진 오류(파싱 오류 제외), `HttpException`, `sendError`, 미처리 예외의 응답 본문을 만든다. 기본 구현은 `text/plain` 한 줄이다.
- 파싱 오류(P1-13)에는 쓰지 않는다. 요청 객체가 완성되지 않았고, 악성 입력에 비용을 쓰지 않기 위해서다.
- `ErrorHandler`가 예외를 던지면 기본 구현으로 대체하고 SEVERE 로그를 남긴다.
- 메시지는 HTML로 내보낼 때 이스케이프한다. 기본 구현은 `text/plain`이라 해당하지 않는다(P9 SEC-05).
- 완료 조건: API-08 통과.

### P4-05 경로와 쿼리 디코딩 `[ ]`
경로(`getPath()`)는 요청을 파싱할 때 한 번 만든다. 실패하면 핸들러를 부르지 않고 400을 보낸다.

1. `getRequestURI()`의 원본 경로를 `/`로 나눈다(디코딩 전에 나눈다, 업스트림 #451).
2. 세그먼트마다 퍼센트 디코딩한다. UTF-8 엄격 디코딩이다. 잘못된 `%` 이스케이프, 잘못된 UTF-8 시퀀스는 **400**이다(업스트림 #436).
3. 디코딩한 세그먼트에 `/`(원본 `%2F`)나 `NUL`이 있으면 **400**.
4. `.`이나 `..` 세그먼트(인코딩된 `%2e` 포함)가 있으면 **400**. 브라우저와 OkHttp는 보내기 전에 점 세그먼트를 정규화하므로 정상 요청에는 없다.
5. 빈 세그먼트(`//`)는 하나로 합친다.
6. `+`는 경로에서 **공백이 아니다**(구 코드는 `URLDecoder`를 써서 공백으로 바꿨다).

쿼리:
- 원본은 `getQueryString()`으로 그대로 제공한다.
- 쿼리는 **관용으로** 디코딩한다. 경로와 달리 쿼리의 오류는 요청 전체를 거부할 이유가 되지 않는다(`?q=100%`처럼 그대로 보내는 클라이언트가 있다).
- 파라미터 맵은 처음 조회할 때 만든다. `+`는 공백, 퍼센트는 UTF-8로 디코딩한다(form 인코딩 규칙). 잘못된 `%` 이스케이프는 글자 그대로 남기고, 잘못된 UTF-8은 U+FFFD로 바꾼다. 조회 메서드는 예외를 던지지 않는다. 원본이 필요하면 `getQueryString()`을 쓴다.
- `readForm`(P5-03)도 같은 디코더를 쓴다.

디코더는 `server.PercentCodec` 하나로 만든다. 경로 모드와 form 모드가 있다. `URLDecoder`/`URLEncoder`는 쓰지 않는다.
- 완료 조건: API-09~14 통과. SimpleWebServer의 `uri.contains("../")` 검사(P8-02)가 이 규칙으로 대체된다.

### P4-06 쿠키 `[ ]`
- 요청: `Cookie` 헤더를 관용으로 파싱한다. `;`로 나누고 각 쌍의 앞뒤 공백을 자른다(RFC 6265 §5.2의 관용 규칙). 첫 `=`로 이름과 값을 나누고, 값을 감싼 큰따옴표를 제거한다. 형식이 틀린 쌍은 건너뛴다. 오류로 보지 않는다.
- 응답: `Cookie`(이름, 값, `Max-Age`, `Expires`, `Path`, `Domain`, `Secure`, `HttpOnly`, `SameSite`)를 `Set-Cookie`로 직렬화한다. 이름과 값의 금지 문자는 `IllegalArgumentException`이다.
- 구 `CookieHandler`, `content.Cookie`는 쓰지 않는다(P8-05에서 삭제).
- 완료 조건: API-15, API-16 통과.

### P4-07 로깅 `[ ]`
- `TinyLog` 인터페이스(`isLoggable(level)`, `log(level, msg, throwable)`)와 기본 구현(`java.util.logging`)을 둔다. Android 앱은 logcat이나 Timber로 연결하는 구현을 넣을 수 있다.
- 수준 정책:

| 사건 | 수준 |
|---|---|
| 클라이언트 abort(`Broken pipe`, `Connection reset`, 쓰기 타임아웃) | FINE (업스트림 #232, #483) |
| 파싱 오류(4xx), 유휴 타임아웃 | FINE |
| 503 거절, 유휴 연결 축출 | FINE (통계로 셈) |
| 핸들러 미처리 예외(500) | SEVERE |
| accept 실패 반복, watchdog 내부 오류 | WARNING |

- 메시지 문자열은 `isLoggable`일 때만 만든다(문자열 연결 비용).
- `AccessLogFilter`: 요청 줄, 상태, 응답 바이트, 처리 시간을 한 줄로 기록한다. 기본 수준은 INFO이고 등록해야만 동작한다.
- 완료 조건: API-17 통과. 테스트 실행 중 클라이언트 abort 시나리오가 SEVERE를 남기지 않는다.

### P4-08 MIME 표 `[ ]`
- 구 `NanoHTTPD.mimeTypes()`는 classpath 리소스(`META-INF/tinyhttpd/*.properties`)를 `getResources`로 모두 읽는다. Android에서는 이 조회가 APK zip을 훑으므로 느리고, R8 설정에 따라 리소스가 빠질 수 있다.
- `MimeTypes`를 Java 코드 안의 정적 표(확장자 약 100개)로 바꾼다. `MimeTypes.register(ext, type)`으로 추가할 수 있다. 조회는 소문자 확장자로 한다.
- 표의 출처는 현재 `default-mimetypes.properties`이고, NAS에서 흔한 형식(`mkv`, `heic`, `webp`, `flac`, `opus`, `m4a` 등)을 추가한다.
- 완료 조건: API-18 통과. `META-INF/tinyhttpd` 리소스 파일 삭제는 P8-05에서 한다.

### P4-09 API 문서 `[ ]`
- 공개 타입 전부에 javadoc을 단다. 메서드마다 스레드 안전성, 예외, Servlet과 다른 점을 적는다.
- `docs/api/servlet-differences.md`: 같은 이름 메서드의 의미 차이, Servlet에 있고 여기 없는 것(D-03 세션, D-04 `getParameter`, `RequestDispatcher` 등), 여기에만 있는 것(`getPath`, `sendFile`, `startEventStream`, `upgrade`, `readMultipart`).
- 완료 조건: `./gradlew javadoc`에 경고가 없다(`-Xdoclint:all`).

### P4-10 사용성 검증 `[ ]`
- NAS 시나리오를 새 API로 작성해 테스트 소스에 둔다: 로그인(쿠키), 디렉터리 목록(JSON), 업로드(multipart), 다운로드(Range), 변경 알림(SSE). P5, P6이 끝나기 전에는 해당 부분을 `@Ignore`로 둔다.
- 작성하면서 불편한 점을 기록하고 API를 고친다. 0.x이므로 깨는 변경을 허용한다(D-07).
- 완료 조건: 시나리오 테스트가 컴파일되고, 발견한 문제와 처리 결과가 이 문서 끝의 "변경 기록"에 있다.

### P4-11 `Html.escape` `[ ]`
- `& < > " '`를 이스케이프한다. `ErrorHandler`의 HTML 구현(P4-04), 디렉터리 목록(P8-02), 사용자 코드가 쓴다(P9 SEC-05).
- 완료 조건: API-19 통과.

### P4-12 `HostAllowlistFilter` `[ ]`
- DNS rebinding 방어(P9 SEC-14). `getServerName()`(P1-01)이 허용 목록에 없으면 421(Misdirected Request).
- 기본 허용: **모든 IP 리터럴**(IPv4, 대괄호 IPv6), `localhost`, 빌더에 지정한 이름들. DNS rebinding 공격의 `Host`는 항상 공격자의 도메인 이름이므로 IP 리터럴 허용은 안전하다. 이 방식이면 DHCP로 기기 주소가 바뀌어도 설정을 고칠 필요가 없다.
- 이름 비교는 대소문자를 무시하고, 끝의 `.`을 제거한 뒤 한다. 포트는 비교하지 않는다.
- 완료 조건: API-20, API-21 통과.

### P4-13 Base64 `[ ]`
- `java.util.Base64`는 API 21에 없다(CLAUDE.md). `com.snoworca.tinyhttpd.http.util.Base64`를 **새로 독립 구현**한다. 표준 알파벳과 URL-safe 알파벳, 패딩 있음/없음을 지원한다.
- 구 `NanoWSD.encodeBase64`는 주석에 Stack Overflow 답변에서 가져왔다고 적혀 있다. 라이선스(CC BY-SA)가 BSD 배포와 맞지 않을 수 있으므로 **옮겨 오지 않는다.**
- RFC 4648 §10의 테스트 벡터로 검증한다.
- WebSocket 핸드셰이크(P8-03)와 세션 ID(P8-04)가 쓴다.
- 완료 조건: API-22 통과.

### P4-14 `ForwardedHeadersFilter` `[ ]`
- 신뢰할 프록시 주소 목록을 받는다. `getRemoteAddr()`가 그 목록에 있을 때만 `X-Forwarded-For`(가장 오른쪽의 신뢰하지 않는 주소), `X-Forwarded-Proto`, `X-Forwarded-Host`를 `RequestWrapper`로 반영한다. 목록 밖이면 무시한다(P9 SEC-08).
- 완료 조건: API-23, SEC-T01 통과.

## 새 테스트 목록 (API)

| ID | 시나리오 | 기대 |
|---|---|---|
| API-01 | 빌더 기본값으로 `build()`와 `start()` | 검사 통과, 설정이 README §5의 "core 빌더" 값과 같음 |
| API-02 | `inputBufferBytes` = `maxRequestLineBytes` + 1 | `IllegalArgumentException`에 옵션 이름 포함 |
| API-03 | 필터 2개 + 핸들러 | 순서대로 실행 |
| API-04 | 필터가 `chain.doFilter`를 부르지 않음 | 핸들러 미실행, 필터의 응답 전달 |
| API-05 | `If-Modified-Since` 세 가지 날짜 형식 | 같은 밀리초 값 |
| API-06 | `Content-Type: text/plain; charset=EUC-KR` | `getCharacterEncoding()` = `EUC-KR` |
| API-07 | 속성 설정 후 다른 필터에서 조회 | 같은 객체 |
| API-08 | 사용자 `ErrorHandler`가 JSON 렌더링 | 404 본문이 JSON |
| API-09 | `/a%2Fb` | 400 |
| API-10 | `/a/%2e%2e/b`, `/a/../b` | 400 |
| API-11 | `/a%ZZ`, `/a%C3%28` (잘못된 UTF-8) | 400 |
| API-12 | `/a+b` | `getPath()` = `/a+b` |
| API-13 | `/%ED%95%9C%EA%B8%80` | `getPath()` = `/한글` |
| API-14 | `?q=a+b%26c&q=2` / `?q=100%&r=%ZZ` | `["a b&c", "2"]` / `q`=`100%`, `r`=`%ZZ`, 400 아님 |
| API-15 | `Cookie: a=1; b="2"` / `Cookie: a=1;b=2 ;  c=3` | `a`=`1`, `b`=`2` / 세 쌍 모두 |
| API-16 | `Cookie` 응답 속성 전부 | `Set-Cookie` 한 줄, 속성 순서 고정 |
| API-17 | 클라이언트가 다운로드 중 끊음 | SEVERE 로그 없음 |
| API-18 | `MimeTypes.get("MKV")` | `video/x-matroska` |
| API-19 | `Html.escape("<a href='x'>&")` | `&lt;a href=&#39;x&#39;&gt;&amp;` |
| API-20 | `Host: evil.example` | 421 |
| API-21 | `Host: 192.168.0.7:8080`, `Host: [fe80::1]`, `Host: LOCALHOST.` | 모두 통과 |
| API-22 | RFC 4648 §10 벡터, URL-safe, 패딩 없음 | 인코딩·디코딩 일치 |
| API-23 | 신뢰 프록시(127.0.0.1)에서 `X-Forwarded-For: 1.2.3.4` / 신뢰하지 않는 주소에서 같은 헤더 | `getRemoteAddr()` = `1.2.3.4` / 실제 주소 |

## 완료 조건 (단계)
- API 테스트가 전부 통과한다. 공개 타입 javadoc이 완성되었다.
- `docs/api/servlet-differences.md`가 있다.

## 변경 기록
(P4-10에서 발견한 API 문제와 처리 결과를 여기에 적는다.)
