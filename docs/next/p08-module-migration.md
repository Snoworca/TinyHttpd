# P8. 모듈 이전과 구 코드 삭제

- 목표: router, webserver, websocket을 새 API 위에서 다시 작성한다. 세션 모듈을 새로 만든다. 마지막에 구 패키지를 삭제한다.
- 선행: P4~P6. P8-05(구 코드 삭제)는 P7도 끝나야 한다(구 `SecureServerSocketFactory`, `keystore.jks`를 지우기 때문이다)
- 관련: [servlet-api.md](../research/servlet-api.md) §5 · 결정 D-03, D-06, D-07
- 원칙: 이 모듈들은 새 API의 첫 사용자다. 쓰기 불편한 점이 나오면 모듈에서 우회하지 말고 core API를 고친다(servlet-api.md §6 "자체 API 설계 실수의 고착").

## 작업

### P8-01 router `[ ]`
- 현재: `RouterNanoHTTPD`(674줄). `UriResponder` 계층, 요청마다 `Class<?>` 리플렉션으로 핸들러 생성, 우선순위 정책 3종.
- 새 구조: `com.snoworca.tinyhttpd.router.Router implements Handler`.
  - 등록: `get/post/put/delete/head/options/patch(pattern, Handler)`, `route(method, pattern, Handler)`, `mount(prefix, Handler)`
  - 패턴: 리터럴, `{name}`(세그먼트 하나), `{name*}`(나머지 전부, 마지막에만). 매칭은 `getPath()`(디코딩·정규화 후, P4-05)에 대해 한다.
  - 우선순위: 리터럴 세그먼트 수가 많은 쪽, 같으면 등록 순서. 구 우선순위 정책 3종은 없앤다.
  - 경로 변수: `PathParams.get(req, "id")`(내부적으로 요청 속성 `tinyhttpd.router.pathParams`).
  - 경로는 맞지만 메서드가 없으면 405 + `Allow`. 경로가 없으면 404. HEAD는 GET 라우트로 처리한다(P2-03이 바디를 버린다). OPTIONS는 자동으로 `Allow`를 응답한다.
- 리플렉션 핸들러 생성을 없앤다. 핸들러는 등록할 때 만든 인스턴스를 쓴다(요청당 할당과 예외 경로 감소).
- 구 `GeneralHandler`는 URL과 파라미터를 이스케이프하지 않고 HTML에 출력한다. **반사형 XSS**다(업스트림 #620의 CVE 수정 미머지와 같은 부류로 보인다. CVE 번호는 착수 시 다시 확인한다). 이 클래스는 옮기지 않는다.
- `StaticPageHandler`는 webserver의 `StaticFileHandler`로 대체한다.
- 테스트: 구 `TestNanolets`, `AppNanolets`의 시나리오를 새 API로 다시 쓴다.
- 완료 조건: MOD-01~06 통과.

### P8-02 webserver `[ ]`
- 현재: `SimpleWebServer`(606줄) 하나에 디렉터리 목록, Range, ETag, CORS, 플러그인 디스패치, CLI가 섞여 있다. `InternalRewrite`는 `Response`를 상속한 편법이다.
- 새 구조:

| 클래스 | 역할 |
|---|---|
| `StaticFileHandler` | 루트 디렉터리 아래 파일을 `FileResponses.serve`(P5-06)로 보낸다. 디렉터리면 `index.html`/`index.htm`을 찾고, 없으면 `DirectoryListingHandler`로 넘기거나 403 |
| `DirectoryListingHandler` | HTML 목록. 파일 이름은 모두 HTML 이스케이프한다(P9 SEC-05). 숨김 파일 표시 여부는 옵션. `StaticFileHandler`에서 목록 보기는 **기본으로 끈다**(인덱스 파일이 없는 디렉터리는 403, SEC-13) |
| `CorsFilter` | 허용 출처 목록, 메서드, 헤더, `Max-Age`, preflight 응답. 와일드카드와 자격 증명 동시 사용은 막는다 |
| `WebServerPlugin` | `Handler` 기반으로 바꾼다. `ServiceLoader` 조회는 유지하되 R8 규칙을 문서화한다(P12-03) |
| `SimpleWebServer` | CLI `main`만 남긴다. 위 조각을 조립한다 |

- 경로 안전성: `getPath()`(P4-05)가 이미 `..`을 거부한다. 그래도 파일을 열기 전에 `getCanonicalFile()`이 루트 아래인지 확인한다. 심볼릭 링크로 루트 밖을 가리키는 경우도 기본적으로 거부한다(옵션 `followSymlinksOutsideRoot`, 기본 `false`). 구 코드의 `uri.contains("../")` 검사를 대체한다.
- 신뢰하지 않는 콘텐츠(사용자가 올린 파일)를 같은 출처에서 보낼 때의 저장형 XSS(P9 SEC-19): `StaticFileHandler`에 `untrustedContent(true)` 옵션을 둔다. 켜면 `text/html`, `image/svg+xml`, `application/xhtml+xml`, `text/xml`을 `Content-Disposition: attachment`로 보내고, 모든 응답에 `Content-Security-Policy: sandbox`를 붙인다. NAS의 사용자 파일 루트에는 이 옵션을 켜라고 가이드에 적는다. `nosniff`는 `FileResponses`가 항상 붙인다(P5-06).
- `InternalRewrite`는 없앤다. 경로 재작성은 `RequestWrapper`로 `getPath()`를 바꾸는 필터로 한다(P4-02).
- 테스트: 구 `TestHttpServer`, `TestCorsHttpServer*`, `DummyPlugin`을 새 API로 다시 쓴다.
- 완료 조건: MOD-07~12, MOD-30, SEC-T02 통과.

### P8-03 websocket `[ ]`
- 핸드셰이크: `WebSocketHandler implements Handler`.
  - 검사: `GET`, HTTP/1.1, `Upgrade: websocket`(토큰), `Connection`에 `upgrade` 토큰, `Sec-WebSocket-Version: 13`, `Sec-WebSocket-Key`(16바이트 base64). 실패하면 400. 버전이 다르면 426 + `Sec-WebSocket-Version: 13`(RFC 6455 §4.4).
  - `Origin` 검사: 기본은 **동일 출처만** 허용한다(`Origin`의 호스트·포트가 요청의 `getServerName()`/`getServerPort()`와 같음). `Origin` 헤더가 없으면(브라우저가 아닌 클라이언트) 허용한다. `allowedOrigins(...)`로 목록을 지정하거나 `allowAnyOrigin()`으로 끌 수 있다. P9 위협 모델의 CSWSH(악성 웹 페이지)를 기본으로 막기 위해서다(SEC-09). 거부하면 403. **한계**: DNS rebinding에서는 `Origin`과 `Host`가 모두 공격자 도메인이라 동일 출처 검사를 통과한다. 이 공격은 `HostAllowlistFilter`(P4-12)만 막는다.
  - 서브프로토콜 협상: 클라이언트가 제시한 목록과 서버 목록의 교집합 중 첫 번째.
  - `Sec-WebSocket-Accept` 계산에 SHA-1과 base64가 필요하다. `java.util.Base64`는 API 21에 없으므로 core의 `http.util.Base64`(P4-13)를 쓴다. 구 `NanoWSD.encodeBase64`는 옮기지 않는다.
- `Response.upgrade("websocket")`(core): `101 Switching Protocols` + `Upgrade`, `Connection: Upgrade`를 쓴다. 이후 HTTP 처리를 끝내고 `UpgradedConnection`(입력 버퍼에 남은 바이트부터 이어지는 입력 스트림 + 원시 출력)을 돌려준다. 업그레이드된 연결은 `maxUpgradedConnections`(기본 16) 상한을 받는다. 넘으면 503.
- 프레임 처리: `WebSocketFrame`, `OpCode`, `CloseCode`, `CloseFrame`은 거의 그대로 쓴다. 다음은 고친다.
  - **크기 상한**: 현재 페이로드 길이를 `Integer.MAX_VALUE`까지 받아 그 크기의 `byte[]`를 바로 할당한다(`WebSocketFrame.readPayload`). 조각 메시지도 전부 합친다. `maxFramePayloadBytes`와 `maxMessageBytes`(둘 다 기본 1 MB)를 두고, 넘으면 1009(Message Too Big)로 닫는다. **길이 필드를 읽은 시점에 검사**하므로 상한을 넘는 프레임에는 버퍼를 할당하지 않는다. 브라우저는 큰 메시지를 조각내지 않고 프레임 하나로 보내므로 프레임 상한을 메시지 상한보다 작게 두지 않는다. 더 큰 메시지가 필요하면 스트리밍 수신 API를 별도로 추가한다(이 계획 범위 밖).
  - 클라이언트 프레임에 마스크가 없으면 1002로 닫는다(RFC 6455 §5.1).
  - 제어 프레임은 125바이트 이하, 조각나면 안 된다. 어기면 1002.
  - 텍스트 메시지는 엄격한 UTF-8 디코딩. 잘못되면 1007.
  - 유휴 감지(별도 타이머 스레드 없음): 업그레이드할 때 `soTimeout`을 `wsPingInterval`(기본 30 s)로 다시 건다. idle 15 s로 남겨 두면 Ping 전에 read가 타임아웃되어 끊기기 때문이다. read가 **프레임 경계에서**(다음 프레임의 첫 바이트를 기다리다) 타임아웃되면 Ping을 보낸다. 다음 주기까지 아무 프레임도 받지 못하면(Pong 포함) 1001로 닫는다. 프레임 **중간에서** 타임아웃되면 파서 상태를 이어 갈 수 없으므로 1002 없이 연결을 닫는다.
  - 서버 정지: `stop()`이 시작되면 모든 업그레이드 연결에 1001(Going Away) Close를 보내고, 상대의 Close를 grace 안에서 기다린 뒤 닫는다(P3-08).
  - 쓰기 동시성: 프레임 쓰기는 연결당 락 하나로 직렬화한다. 읽기 스레드(Ping, Pong, Close 응답)와 앱의 송신 스레드가 동시에 쓸 수 있기 때문이다. 쓰기 타임아웃은 P3 watchdog이 업그레이드된 연결에도 적용된다. 그래서 락을 잡고 막힌 쓰기도 결국 풀린다.
- `WebSocket`은 `IHTTPSession` 대신 핸드셰이크 요청의 스냅숏(경로, 헤더, 쿼리, 원격 주소)을 보관한다.
- `samples/websockets/DebugWebSocketServer`, `EchoSocketSample`은 `main` 소스에 있다. 테스트 소스로 옮기거나 삭제한다(배포 jar에서 빼기 위해).
- 원본 코드를 거의 그대로 옮기는 파일(`WebSocketFrame`, `OpCode`, `CloseCode`, `CloseFrame`)은 원본 저작권 헤더를 유지한다(CLAUDE.md 라이선스 규칙).
- 테스트: 구 `EchoWebSocketsTest`(Jetty WebSocket 클라이언트 9.3)를 OkHttp WebSocket 클라이언트로 바꾼다. 테스트 의존성 하나를 줄인다.
- 완료 조건: MOD-13~19, MOD-26~29 통과.

### P8-04 session 모듈 (신규, D-03) `[ ]`
- 모듈 `session/`(`tinyhttpd-session`). core만 의존한다.
- `SessionFilter`: 쿠키(기본 이름 `TSESSIONID`, `HttpOnly`, `SameSite=Lax`, TLS면 `Secure`)로 기존 세션을 찾는다. **세션은 앱이 요청할 때만 만든다**(`Session.get(req)`는 없으면 `null`, `Session.create(req, res)`가 만든다). 쿠키 없는 요청마다 세션을 만들면 공격자가 요청만으로 상한을 채워 정상 세션을 밀어낼 수 있다.
- 세션 ID: `SecureRandom` 128비트 이상, URL-safe 인코딩. 로그인 후 ID 재발급 API(`Session.rotate(req, res)`)로 세션 고정 공격을 막는다(P9 SEC-10).
- 저장소: 메모리(`SessionStore` 인터페이스, 기본 구현은 `ConcurrentHashMap` + 만료). 만료 정리는 watchdog을 쓰지 않고, 접근 시 검사와 주기 정리(빌더에서 주기 지정)로 한다.
- 상한: 전체 `maxSessions`(기본 1000), 원격 주소별 `maxSessionsPerAddress`(기본 50). 전체 상한을 넘으면 만료된 세션 → 인증 표시(`Session.markAuthenticated()`)가 없는 세션 중 가장 오래 쓰지 않은 것 → 그래도 없으면 새 세션 생성을 거부(503) 순서로 처리한다. 인증된 세션은 밀어내지 않는다.
- `CsrfFilter`(P9 SEC-15): 안전하지 않은 메서드(POST/PUT/PATCH/DELETE)에서 `Origin`(없으면 `Referer`)이 허용 출처인지 확인한다. 둘 다 없으면 세션의 토큰 헤더(`X-CSRF-Token`)를 요구한다. 실패하면 403. DNS rebinding에 대한 한계는 WebSocket `Origin` 검사와 같다(`HostAllowlistFilter`에 의존).
- 완료 조건: MOD-20~25 통과.

### P8-05 구 코드 삭제 `[ ]`
- 모든 모듈이 새 API로 옮겨진 뒤 한 커밋으로 삭제한다.
  - core: `protocols.http` 패키지 전체(`NanoHTTPD`, `HTTPSession`, `IHTTPSession`, `ClientHandler`, `ServerRunnable`, `response.*`, `content.*`, `request.Method`, `sockets.*`, `tempfiles.*`, `threading.*`), `util.ServerRunner`, `util.IHandler`, `util.IFactory`, `util.IFactoryThrowing`
  - 리소스: `META-INF/tinyhttpd/*.properties` (P4-08이 대체)
  - 테스트: `junit/protocols/http/**`(구 엔진 대상), `keystore.jks`, `file-upload-test.htm`, `multipart-form-test.htm`
  - 의존성: 테스트용 Apache HttpClient(`httpclient`, `httpmime`), Jetty WebSocket client, Mockito(쓰는 곳이 남지 않으면)
- 구 테스트 중 **새 엔진에도 의미 있는 시나리오**는 삭제 전에 새 테스트로 옮겼는지 표로 확인한다. 이 표를 이 문서 끝 "구 테스트 이전 표"에 채운다.
- CLAUDE.md "저장소 구성"의 "클래스명은 아직 원본 그대로다" 문장을 지운다.
- 완료 조건: 라이브러리 소스에 `NanoHTTPD`, `IHTTPSession` 같은 이름이 남아 있지 않다(`git grep -n "NanoHTTPD\|IHTTPSession\|NanoWSD" -- core router webserver websocket session`이 비어 있다. LICENSE, `docs/`, `tools/`의 비교용 코드는 대상이 아니다). `./gradlew build` 통과. P0-05에서 미뤄 둔 `-Werror`를 켜고 javadoc 제외 설정을 지운다.

## 새 테스트 목록 (MOD)

| ID | 시나리오 | 기대 |
|---|---|---|
| MOD-01 | `/users/{id}` + `/users/me` | `/users/me`는 리터럴 라우트 |
| MOD-02 | `/files/{path*}`에 `/files/a/b/c.txt` | `path` = `a/b/c.txt` |
| MOD-03 | GET만 등록된 경로에 POST | 405 + `Allow: GET, HEAD, OPTIONS` |
| MOD-04 | 없는 경로 | 404 |
| MOD-05 | HEAD로 GET 라우트 | 헤더만 |
| MOD-06 | `mount("/api", subRouter)` | 하위 라우터가 나머지 경로로 매칭 |
| MOD-07 | 정적 파일 GET/HEAD/Range | 200/헤더만/206 |
| MOD-08 | 루트 밖을 가리키는 심볼릭 링크 | 403 |
| MOD-09 | 디렉터리 목록에 `<script>` 이름의 파일 | 이스케이프되어 출력 |
| MOD-10 | CORS preflight | 허용 출처면 204 + 헤더, 아니면 헤더 없음 |
| MOD-11 | 플러그인(ServiceLoader) 등록 | 확장자별 플러그인 호출 |
| MOD-12 | CLI `main` | 지정 디렉터리 서비스 |
| MOD-13 | OkHttp WebSocket 에코 | 텍스트·바이너리 왕복 |
| MOD-14 | `Sec-WebSocket-Version: 8` | 426 + `Sec-WebSocket-Version: 13` |
| MOD-15 | 마스크 없는 클라이언트 프레임 | 1002로 닫힘 |
| MOD-16 | 2 MB 메시지 (`maxMessageBytes` 1 MB) | 1009로 닫힘, 힙 증가 없음 |
| MOD-17 | 잘못된 UTF-8 텍스트 | 1007로 닫힘 |
| MOD-18 | Pong을 보내지 않는 클라이언트 | Ping 주기 후 닫힘 |
| MOD-19 | `maxUpgradedConnections`+1 | 503 |
| MOD-20 | `Session.create` 호출 / 쿠키 없는 요청만 | `Set-Cookie: TSESSIONID=…; HttpOnly; SameSite=Lax` / 세션 생성 안 함 |
| MOD-21 | 만료 후 요청 | 새 세션 |
| MOD-22 | `Session.rotate` | 새 ID, 속성 유지, 옛 ID 무효 |
| MOD-23 | 인증 세션 10개 + 미인증 세션 990개 상태에서 생성 1회 | 가장 오래된 미인증 세션 제거, 인증 세션 유지 |
| MOD-24 | 같은 주소에서 세션 51개 생성 | 51번째 거부 |
| MOD-25 | 다른 출처의 `Origin`으로 POST (`CsrfFilter`) / 같은 출처 | 403 / 통과 |
| MOD-26 | 다른 출처의 `Origin`으로 핸드셰이크 / `Origin` 없음 | 403 / 성공 |
| MOD-27 | 앱 스레드 송신과 Ping 응답이 동시에 일어남 (1천 회) | 프레임이 섞이지 않음 |
| MOD-28 | 프레임 헤더 2바이트만 보내고 정지 (ping 주기 500 ms) | 연결 닫힘 |
| MOD-29 | 업그레이드 연결이 열린 상태에서 `stop(1000)` | 클라이언트가 1001 Close 수신 |
| MOD-30 | `untrustedContent(true)`에서 `.html` / `.svg` 요청 | `attachment` + `CSP: sandbox` + `nosniff` |

## 구 테스트 이전 표

(P8-05에서 채운다. 열: 구 테스트 클래스, 시나리오 요약, 새 테스트 ID 또는 "의미 없음"과 이유.)

## 완료 조건 (단계)
- MOD 테스트가 전부 통과한다. 구 패키지가 삭제되었다. 구 테스트 이전 표가 채워졌다.
