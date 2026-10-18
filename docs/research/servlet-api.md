# Servlet API 채택 / 유사 API 설계 연구

작성일: 2026-10-17 · 대상: TinyHttpd `0.1.0-SNAPSHOT` (core, router, webserver, websocket)

표기 규칙
- **[검증]**: 직접 확인한 사실. 바이트코드·jar는 Maven Central에서 내려받아 `javap`, animal-sniffer(API 21 시그니처)로 확인했다. 웹 자료는 출처 번호를 붙였다.
- **[판단]**: 근거를 바탕으로 한 필자의 의견이나 추정.

---

## 1. 요약 (결론)

**권장안: Servlet jar에 의존하지 않는 "Servlet 모양의 자체 API"를 core에 둔다. 진짜 `javax.servlet` 어댑터(`tinyhttpd-servlet`)는 지금 만들지 않고, 필요가 확인되면 `javax.servlet-api:3.1.0` 기반의 선택 모듈로 추가한다.**

근거 요약:

1. **API 21에서 쓸 수 있는 Servlet API는 사실상 `javax.servlet-api` 3.1.0(2013년, 동결)뿐이다.** [검증]
   - 4.0.1과 Jakarta 5.0은 `HttpServletResponse`에서 `java.util.function.Supplier`(API 24+)를 참조한다. animal-sniffer API 21 검사에 걸린다.
   - Jakarta 6.0/6.1은 Java 11 바이트코드(major 55)다. 6.1은 `java.util.stream`, `InputStream.readAllBytes()` 등도 쓴다.
   - 현대 생태계는 `jakarta.*`로 옮겨갔으므로, 3.1을 채택해도 재사용할 수 있는 서드파티 라이브러리는 많지 않다. [판단]
2. **"정직한" Servlet 컨테이너는 구현 범위가 크다.** [검증] 3.1 기준 주요 인터페이스(ServletRequest, HttpServletRequest, ServletResponse, HttpServletResponse, ServletContext, HttpSession, AsyncContext, Part 등)의 추상 메서드만 약 225개다(§3.2 표). Winstone은 자체 구현을 유지하지 못하고 Jetty로 내부를 교체했다[9].
3. **Servlet의 의미 체계 일부가 이 프로젝트 목표와 맞지 않는다.** [판단]
   - `getParts()`는 multipart 전체를 먼저 파싱·버퍼링하는 모델이다[검증: 3.1 javadoc]. "디스크로 바로 스트리밍" 목표와 충돌한다.
   - SSE·비동기는 `AsyncContext`/`ReadListener` 같은 무거운 상태 기계를 요구한다.
4. **반면 메서드 이름과 형태를 Servlet에 맞추면 학습 비용은 거의 없다.** `void handle(req, res)`, `Filter.doFilter(req, res, chain)`, `getHeader`, `getInputStream`, `setStatus`, `getOutputStream` 같은 형태다. 나중에 어댑터를 만들 때도 대부분 1:1로 위임할 수 있다.
5. **메모리 차이는 결정 근거가 아니다.** [판단] servlet-api 3.1.0의 클래스는 약 109KB(79개)이고, 로드되는 클래스 수 차이도 미미하다. 결정을 가르는 것은 의미 체계, 구현 범위, Android 호환성, 라이선스다.

---

## 2. 조사 범위

| 항목 | 방법 |
|---|---|
| Servlet API jar 6종 (javax 3.0.1/3.1.0/4.0.1, jakarta 4.0.4/5.0.0/6.0.0/6.1.0) | Maven Central에서 내려받음 → 클래스 major 버전, 크기, POM 라이선스, `javap -v` 상수 풀, default 메서드, animal-sniffer(`android-api-level-21:5.0.1_r2`, 프로젝트가 쓰는 것과 같은 시그니처) |
| Servlet 표면 크기 | `javap`로 인터페이스별 추상 메서드 수 계산, 3.1.0 sources jar의 javadoc에서 예외 조항 확인 |
| 기존 소형·임베디드 컨테이너 | TJWS, Winstone, i-jetty, AOSP의 Jetty, Jetty 9.4(animal-sniffer 검사), Javalin, Undertow, Tomcat |
| 비 Servlet API 설계 | Jetty 12 core, Undertow `HttpServerExchange`, JDK `HttpExchange`, Helidon SE 4, Netty, OkHttp MockWebServer, AndroidAsync |
| 저장소 코드 | `IHTTPSession`, `NanoHTTPD`, `HTTPSession`, `Response`, `RouterNanoHTTPD`, `SimpleWebServer`, `NanoWSD`/`WebSocket`, 기존 연구 `docs/research/keep-alive.md` |

조사하지 못한 것: 실기기(API 21~23)에서 Servlet 4.0/Jakarta 5.0 jar를 실제로 로드했을 때의 ART 동작. 서버별 기본 힙 사용량 실측(신뢰할 만한 공개 수치가 거의 없음).

---

## 3. 옵션별 분석

### 3.1 Servlet API 아티팩트 비교 [검증]

| 아티팩트 | 클래스 major (Java) | jar 크기 | 클래스 수 / 클래스 바이트 | 라이선스 (POM) | API 21 animal-sniffer |
|---|---|---|---|---|---|
| `javax.servlet:javax.servlet-api:3.0.1` | 49 (Java 5) | 85,353 B | 74 / 102 KB | CDDL + GPLv2 w/ CPE | **통과** |
| `javax.servlet:javax.servlet-api:3.1.0` | 51 (Java 7) | 95,806 B | 79 / 109 KB | CDDL + GPLv2 w/ CPE | **통과** |
| `javax.servlet:javax.servlet-api:4.0.1` | 52 (Java 8) | 95,505 B | 85 / 123 KB | CDDL + GPLv2 w/ CPE | 실패: `HttpServletResponse`, `HttpServletResponseWrapper` → `java.util.function.Supplier` |
| `jakarta.servlet:jakarta.servlet-api:4.0.4` | 52 | 82,973 B | 85 / 123 KB | EPL 2.0 + GPL2 w/ CPE | (패키지는 `javax.*`, 4.0.1과 동일 API) |
| `jakarta.servlet:jakarta.servlet-api:5.0.0` | 52 | 307,850 B¹ | 85 / 125 KB | EPL 2.0 + GPL2 w/ CPE | 실패: 4.0.1과 같은 `Supplier` 참조 |
| `jakarta.servlet:jakarta.servlet-api:6.0.0` | **55 (Java 11)** | 347,613 B¹ | 84 / 125 KB | EPL 2.0 + GPL2 w/ CPE | 실패: `Supplier` |
| `jakarta.servlet:jakarta.servlet-api:6.1.0` | **55 (Java 11)** | 398,424 B¹ | 84 / 131 KB | EPL 2.0 + GPL2 w/ CPE | 실패: `Supplier`, `Predicate`, `Stream`, `List.parallelStream()`, `InputStream.readAllBytes()/readNBytes()`, `ByteBuffer.limit(int)/position(int)`(공변 반환) |

¹ Jakarta 5.0 이상의 jar가 큰 이유는 `jakarta/servlet/resources/*.xsd`(배포 서술자 스키마, 개당 50~120KB)가 들어 있기 때문이다. 클래스 크기는 4.0과 비슷하다.

보충:
- **Jakarta Servlet 6.0의 최소 요구는 "Java SE 11 or higher"다**[1]. 6.1은 Java SE 17 이상이다[1](검색 결과의 6.1 페이지 요약). 바이트코드 major 55로도 확인했다.
- `java.util.function.Supplier`는 Android API 24에 추가되었다. [판단: Android 문서 페이지에서 해당 줄을 직접 읽지 못했지만, animal-sniffer API 21 시그니처에 없다는 사실로 확인된다.] API 21에서 쓰려면 core library desugaring이 필요하다[13]. 이는 CLAUDE.md의 "코어가 디슈가링을 요구하지 않게 한다" 원칙에 어긋난다.
- **default 메서드**: 3.1.0에는 없다. 4.0.1부터 `Filter.init/destroy`, 각종 Listener, `HttpServletRequest.getHttpServletMapping()/newPushBuilder()/getTrailerFields()`, `HttpServletResponse.setTrailerFields(Supplier)` 등이 default다. D8은 default/static 인터페이스 메서드를 모든 API 레벨용으로 변환한다[13]. 그래서 default 메서드 자체는 문제가 아니다. 문제는 그 시그니처가 `Supplier`를 참조한다는 점이다.
- `javax.naming`, `java.nio.file`, `java.beans`, `java.lang.management` 참조는 어느 버전에도 없다(상수 풀 검사). 비핵심 참조는 `java.text.MessageFormat`, `java.util.ResourceBundle`, `java.security.Principal`, `java.lang.reflect.Method` 정도이며 모두 API 21에 있다.
- **"런타임에는 될 수도 있다"는 주장에 대하여** [판단]: ART는 클래스를 지연 해석하므로, `setTrailerFields(Supplier)`를 호출하지 않으면 4.0.1/5.0도 API 21에서 동작할 *가능성*이 있다. 하지만 D8이 minSdk<24용으로 default 메서드를 구현 클래스에 복사하므로, 구현 클래스에 `Supplier` 시그니처 메서드가 생긴다. 이 경우 검증기가 soft-fail할 수 있다. 빌드 게이트(animal-sniffer)로 보증할 수 없고, 실기기로만 확인할 수 있다. 따라서 채택 근거로 쓰지 않는다.
- `javac --release 8`은 Java 11 바이트코드(Jakarta 6.0) jar를 클래스패스에 두고도 오류 없이 컴파일했다(직접 시험). "컴파일 단계에서 막힌다"는 통념은 틀렸다. 막는 것은 animal-sniffer와 런타임이다.

#### 라이선스가 BSD-3-Clause 라이브러리 / APK 배포에 주는 의미

| | CDDL 1.0/1.1 (+GPLv2 CPE 선택) — javax | EPL-2.0 (+GPLv2 CPE 2차 라이선스) — jakarta |
|---|---|---|
| 성격 | 파일 단위 약한 카피레프트 | 파일/모듈 단위 약한 카피레프트 |
| 수정 없이 jar를 함께 배포 | 가능. 실행 형태로 배포하면 소스를 구할 수 있게 하고, 그 방법을 수령자에게 알려야 한다(§3.1). 실행 형태는 다른 라이선스로 배포할 수 있다(§3.5). 다른 코드와 묶은 "Larger Work"를 허용한다(§3.6)[14] | 가능. 다른 형태로 배포할 때도 소스를 제공해야 하고, "소스를 이 라이선스로 구할 수 있다"는 진술과 획득 방법을 함께 제공해야 한다(§3.1)[15] |
| 우리 코드(인터페이스 구현체)에 전염되는가 | 아니다. 수정은 Covered Software *파일*의 변경으로 정의된다 | 아니다. "Modified Works"는 링크·이름 바인딩·서브클래싱만 하는 작업을 제외한다[15] |
| TinyHttpd 저장소에서의 영향 | 어댑터 모듈이 의존성으로 선언만 하면 BSD 소스는 그대로 유지된다. 단, 앱 개발자가 APK의 오픈소스 고지 화면에 CDDL 고지와 소스 위치(Maven Central sources jar 링크 등)를 추가해야 한다 | 같음 |

[판단] 법적으로 막히지는 않는다. 다만 "core에 런타임 의존성이 없다"는 현재 장점이 사라지고, 사용자에게 고지 의무가 하나 더 생긴다. core가 servlet jar에 의존하면 **이 라이브러리를 쓰는 모든 앱**이 이 의무를 진다. 선택 모듈로 분리하면 그 모듈을 쓰는 앱만 진다. 법률 자문이 아니므로 배포 전에 원문을 다시 확인할 것.

### 3.2 정직한 Servlet 컨테이너의 구현 범위

#### 인터페이스별 추상 메서드 수 [검증: javap]

| 인터페이스 | 3.1.0 | 4.0.1 |
|---|---|---|
| `ServletRequest` | 37 | 37 |
| `HttpServletRequest` (추가분) | 32 | 32 (+default 4) |
| `ServletResponse` | 16 | 16 |
| `HttpServletResponse` (추가분) | 21 | 21 (+default 2) |
| `ServletContext` | 52 | 59 |
| `HttpSession` | 17 | 17 |
| `AsyncContext` | 13 | 13 |
| `Part` | 10 | 10 |
| `ServletInputStream` / `ServletOutputStream` (3.1 NIO 추상분) | 3 / 2 | 3 / 2 |
| `RequestDispatcher`, `Filter`, `FilterChain`, `ServletConfig` | 2, 3, 1, 4 | 2, 1(+2 default), 1, 4 |
| `FilterRegistration`, `ServletRegistration`, `HttpUpgradeHandler`, `WebConnection` | 4, 3, 2, 2 | 동일 |
| **합계(대략)** | **약 225** | **약 230** |

여기에 `HttpServlet`의 메서드 디스패치(이건 API jar에 이미 구현됨), `Cookie`, Listener 이벤트 발행, `web.xml`/어노테이션 처리(선택)가 더해진다.

#### 무엇을 "지원 안 함"으로 둘 수 있나

명세가 미지원을 공식적으로 허용하는 경로가 있다. [검증: 3.1.0 sources javadoc]

| 기능 | 명세상 탈출구 | 사용자가 겪는 결과 |
|---|---|---|
| `AsyncContext` (`startAsync`) | 필터/서블릿이 비동기를 지원하지 않으면(`isAsyncSupported()==false`) `IllegalStateException` | 명세대로다. 다만 Spring `DeferredResult`, Jersey `@Suspended`, Jetty `EventSourceServlet` 같은 SSE 구현은 동작하지 않는다 |
| `ServletInputStream.setReadListener` / `ServletOutputStream.setWriteListener` | 요청이 upgrade 상태도 async 상태도 아니면 `IllegalStateException` | async가 없으면 자동으로 성립한다. 논블로킹 IO를 쓰는 코드는 실패한다 |
| `getParts()` / `getPart()` | `@MultipartConfig`가 없으면 `IllegalStateException`, multipart가 아니면 `ServletException` | 설정을 지원하지 않으면 업로드 서블릿이 전부 실패한다. 지원하면 **바디 전체를 먼저 파싱해 임시 파일/메모리에 담아야 한다**(컬렉션 반환 모델) |
| `ServletContext.addServlet/addFilter/addListener` 등 | 선언되지 않은 `ServletContextListener`에 넘긴 컨텍스트에서 호출하면 `UnsupportedOperationException`(javadoc 27곳) | 프로그래매틱 등록을 쓰는 프레임워크 초기화가 실패한다 |
| `HttpServletRequest.upgrade()` | 명세상 탈출구가 없다(구현 필요) | WebSocket을 Servlet 위에서 하려면 구현해야 한다 |
| `RequestDispatcher.forward/include`, `getResourcePaths`, `getRealPath`, JSP, 보안(`login`, `authenticate`, `isUserInRole`) | 명세상 탈출구가 없다. `null`이나 UOE를 반환하면 **비준수** | 대부분의 프레임워크(Spring MVC의 뷰 해석, 정적 리소스, 에러 페이지)가 이 경로를 쓴다. UOE가 런타임에 산발적으로 터진다 |

[판단] 핵심 문제는 **"Servlet API를 제공한다"는 약속과 실제 동작의 간극**이다. 사용자는 `HttpServletRequest`를 보면 Tomcat처럼 동작하리라 기대한다. 그러나 이 서버에서 돌릴 수 있는 것은 직접 작성한 단순 서블릿과 일부 소형 라이브러리뿐이다. Spring 5는 Java 8 API(`java.util.function`, `java.time` 등)에 의존하므로 API 21에서 어차피 디슈가링 없이 돌지 않는다. Spring 6는 Java 17이 필요하다. 이 부분은 직접 확인하지 않았다.

#### 기존 소형/임베디드 구현의 선택 [검증 + 판단]

| 구현 | Servlet 수준 | 크기 | Android | 비고 |
|---|---|---|---|---|
| **TJWS** (Tiny Java Web Server) | "mostly compatible with ... 4.0", 3.0 배포 서술자에서 async·multipart 지원, JSR-356 WebSocket | 코어 약 119KB, J2EE 배포 모듈 98KB, WebSocket 68KB, Jasper 1.5MB 별도[2] | 1.83부터 빌드 옵션으로 Android용 컴파일. Android 앱 "Atjeews". "App servers features are not supported for Android"[2] | BSD 계열 라이선스. 1인 유지보수. Open Hub에서는 비활성으로 분류[3]. **Android에서 Servlet을 제공하는 거의 유일한 현존 사례이자 참고 구현이다** |
| **Winstone** (Hudson/Jenkins) | 원래 Servlet 2.4. 0.9.10은 jar 하나 166KB[4] | 166KB | – | Jenkins 포크는 "it became impractical to maintain a from-scratch servlet implementation on our own" 때문에 내부를 Jetty로 교체했다. 현재는 Jetty 12.1 위의 CLI다[9] |
| **i-jetty** | Jetty 6/7 시대 | – | Android 앱 + 서버 어댑터 | 2020-03-25에 아카이브됨(읽기 전용)[5] |
| **AOSP `external/jetty`** | Jetty 8.1.15 (Servlet 3.0) | – | Android에 맞지 않는 소스를 `NON_ANDROID_SRC`로 제외해 빌드했다[6] | "원본 그대로는 Android에서 안 된다"는 방증이다 |
| **Jetty 9.4.58** (마지막 Java 8 계열) | Servlet 3.1 | server 749KB + util 595KB + http 253KB + io 185KB + servlet 148KB + security 120KB ≈ **2.05MB** (+servlet-api 96KB) | animal-sniffer API 21 검사에서 **고유 JDK 참조 333개가 누락**(`java.nio.file.*`, `java.util.stream`, `java.util.function`, `LongAdder`, `List.removeIf`, `AtomicInteger.updateAndGet` 등). [검증] | 커뮤니티 지원은 2022-06-01에 종료, 현재 EOL 표기[10] |
| **Javalin 4** (Jetty 9.4 기반) | – | 584KB + Jetty | 공식 문서가 Android에서 **`minSdkVersion 26`** 을 요구한다[7] | Javalin 5 이상은 "Jetty 11+ not working on Android"로 Android 비호환[7]. Jetty 11+는 `ServiceLoader$Provider` 부재 문제가 있다[8] |
| **Undertow 2.2** | core + servlet | core 2.37MB + servlet 533KB + XNIO 등 | 확인하지 못함 | servlet 계층은 core 위의 별도 아티팩트. JSR-356이 Servlet API 기반이라 WebSocket에 servlet이 필요하다[11] |
| **Tomcat embed core 9.0.123** | Servlet 4.0 | **3.76MB** (11.0.x는 3.66MB) | CLAUDE.md에서 이미 탈락 | – |

[판단] 교훈 세 가지:
- 작은 Servlet 구현은 존재한다(TJWS, 초기 Winstone). 모두 1인 또는 소수가 오래 유지하다 멈추거나, 큰 컨테이너로 갈아탔다.
- Android에서 "진짜 Servlet"을 쓰려 한 시도(i-jetty, AOSP Jetty, Javalin 4)는 소스를 잘라내거나 minSdk를 올리는 식으로 타협했다.
- 메모리·크기 면에서 Servlet *API 자체*(약 100KB)는 가볍다. 무거운 것은 *컨테이너 의미 체계*를 정직하게 구현하는 일이다.

### 3.3 자체 API를 설계한다면: 다른 경량 서버의 요청/응답 모델

| 서버 | 핸들러 형태 | 요청 바디 / 미소비 바디 처리 | 응답 스트리밍 | SSE | Android 적합성 |
|---|---|---|---|---|---|
| **Javalin / Spark** | `Context`가 `HttpServletRequest/Response`를 감쌈(`ctx.req()`, `ctx.res()`)[7] | Jetty에 위임 | Jetty | Javalin: `SseClient.sendEvent/sendData/keepAlive/onClose`. 핸들러를 벗어나면 자동으로 닫히므로 브로드캐스트하려면 `keepAlive()`가 필요하다[7] | Javalin 4는 minSdk 26, 5 이상은 불가[7]. Spark 2.9.4는 Java 8 + Jetty 9[16] |
| **Undertow `HttpServerExchange`** | `HttpHandler.handleRequest(exchange)`. IO 스레드에서 시작하므로 블로킹하려면 `dispatch()` 후 `startBlocking()`[11] | `endExchange()` 시 "simply drain the request channel"[11] | 버퍼링 스트림. 한 버퍼에 들어가면 Content-Length를 자동으로 붙인다[11] | `ServerSentEventHandler`, `ServerSentEventConnection.send(data, event, id, callback)`, `sendRetry`[12] | XNIO 기반으로 크다 |
| **Jetty 12 core** | `boolean handle(Request, Response, Callback)`. 처리했으면 `callback.succeeded()` 후 `true`[17] | `read()`/`demand(Runnable)`, Chunk `release()` 의무. 다 읽지 않으면 `consumeAvailable()`로 소비하고, 실패하면 "the underlying connection may be made non-persistent"[18] | `Response.write(last, ByteBuffer, Callback)` | 별도 모듈 | Java 17+, Android 불가 |
| **JDK `HttpExchange`** | `HttpHandler.handle(exchange)` | "Closing an exchange without consuming all of the request body is not an error but may make the underlying TCP connection unusable". `close()`는 남은 바디를 구현 한도까지 읽어 버린다[19] | `sendResponseHeaders(code, len)`: `>0` 고정 길이, `0` chunked, `-1` 바디 없음[19] | 없음(chunked로 직접) | **Android에 없음**(`com.sun.net.httpserver`)[20] |
| **Helidon SE 4** | `(ServerRequest, ServerResponse)` 람다. `send`는 요청을 시작한 스레드에서 호출해야 한다[21] | `req.content().as(...)` | 블로킹 | 별도 SSE 모듈 | Java 21 필수(가상 스레드)[22] |
| **Netty 4.1** | 파이프라인 + `ChannelHandler`, `HttpObjectAggregator` 또는 청크 단위 메시지 | 직접 관리 | `HttpChunkedInput`, `ChunkedWriteHandler` | 직접 구현 | 4.1은 Android 4.0+ 지원을 표방[23]. HTTP 최소 구성(common/buffer/transport/resolver/codec/codec-http/handler 4.1.139) ≈ **3.3MB**[검증: Central] |
| **OkHttp MockWebServer** | `Dispatcher.dispatch(RecordedRequest): MockResponse`[24] | 테스트용으로 기록 | `throttleBody`로 느린 네트워크 흉내[24] | – | 테스트 전용. "응답 객체를 반환"하는 모델의 한계가 NanoHTTPD와 같다 |
| **AndroidAsync** (koush) | `server.get(path, (AsyncHttpServerRequest, AsyncHttpServerResponse) -> response.send(...))`, NIO 단일 스레드 콜백[25] | 콜백 | `response.send` 계열 | 문서에 없음 | Android 전용. 콜백 지옥, 단일 스레드에서 블로킹 금지 |
| **NanoHTTPD (현재)** | `Response serve(IHTTPSession)` | **드레인하지 않음.** `HTTPSession.execute()`에 `// TODO: this.inputStream.skip(body_size - ...)`가 남아 있다[검증: 저장소] | `InputStream`만 반환 가능. 업스트림 #449/#632에서 OutputStream 노출을 거부했다 | 미지원(#236) | 호환은 되지만 설계가 부족하다 |

[판단] 설계 교훈:
1. **"응답 객체를 반환"(NanoHTTPD, MockWebServer) 모델은 스트리밍·SSE에 맞지 않는다.** 응답이 `InputStream` 하나로 고정되기 때문이다. Servlet/JDK/Helidon처럼 **`void handle(req, res)` + 응답 `OutputStream`** 이 블로킹 스레드 풀 모델에 가장 잘 맞는다.
2. **미소비 바디 처리는 서버 책임이다.** Undertow(drain), Jetty(consumeAvailable → 실패하면 비영속), JDK(구현 한도까지 읽고 버림) 모두 같은 방향이다. 핸들러는 경계가 정해진 바디 스트림만 본다. 서버가 끝난 뒤 정해진 한도까지 버리고, 넘으면 연결을 닫는다. `docs/research/keep-alive.md` §4.3~4.6과 일치한다.
3. **비동기 콜백(Jetty 12 core, Undertow non-blocking, AndroidAsync)은 50명 규모에서 얻는 것보다 복잡도가 크다.** CLAUDE.md의 Vert.x 탈락 근거와 같다.
4. **SSE는 전용 객체가 낫다.** Javalin `SseClient`나 Undertow `ServerSentEventConnection`처럼 `send(event, data, id)`, `comment()`(하트비트), `retry()`, 닫힘 감지를 제공한다. 서버는 이 응답의 gzip을 끄고, 항상 flush하고, 쓰기 타임아웃을 적용한다.

### 3.4 옵션 비교

| | A. core가 `javax.servlet` 3.1을 직접 API로 | B. Jakarta 5/6 채택 | **C. Servlet 모양 자체 API + (선택) servlet 어댑터** | D. 비동기/콜백형 자체 API (Jetty 12/Undertow 스타일) | E. 기존 컨테이너 임베드 (TJWS, Jetty 9.x) |
|---|---|---|---|---|---|
| API 21 + 디슈가링 불필요 | ✅ (3.1만) | ❌ (`Supplier`, Java 11) | ✅ | ✅ | TJWS ✅(빌드 옵션) / Jetty 9.4 ❌ (minSdk 26) |
| core 무의존성 유지 | ❌ | ❌ | ✅ (어댑터만 의존) | ✅ | ❌ |
| 라이선스 부담 | CDDL 고지가 모든 사용자에게 | EPL 고지가 모든 사용자에게 | 어댑터 사용자만 | 없음 | TJWS BSD계열 / Jetty EPL·Apache |
| 구현량 | 크다 (약 225개 메서드, 의미 체계 준수) | 더 크다 | 작다~중간 (필요한 것만) | 중간~크다 | 작다 (대신 개조 자유도를 잃음) |
| 스트리밍 업로드(바로 디스크) | `getParts()` 모델과 충돌. `getInputStream()` + 자체 파서로 우회 | 같음 | ✅ 풀(pull) 방식 `MultipartReader` | ✅ | 컨테이너 방식을 따름 |
| SSE | `AsyncContext` 없으면 스레드 점유 블로킹 | 같음 | ✅ 전용 `EventStream` (블로킹) | ✅ (스레드 비점유) | 컨테이너 의존 |
| keep-alive 정확성 | 서버 책임 (동일) | 동일 | 동일 + API로 강제 | 동일 | 컨테이너 의존 |
| 학습 비용 | 최저 | 최저 | 낮다 (이름이 같음) | 높다 | 중간 |
| 생태계 재사용 | 제한적 (javax 동결, API 21에서 돌 라이브러리 소수) | API 21 불가 | 어댑터로 같은 범위 확보 가능 | 없음 | Servlet 앱 재사용 |
| NanoHttpd 개조라는 프로젝트 결정과의 정합 | 중간 | 낮다 | 높다 | 중간 | 낮다 (기반 교체) |

---

## 4. 권장안과 인터페이스 스케치

### 4.1 원칙

1. **이름이 같으면 의미도 같다.** Servlet과 같은 이름의 메서드는 Servlet과 같은 의미를 가진다. 의미가 다르면 다른 이름을 쓴다. 예: Servlet의 `getParameter`는 form 바디까지 읽는다. 여기서 쿼리만 본다면 `getQueryParameter`로 이름을 바꾼다. 나중에 어댑터를 만들 때 혼란을 막는다.
2. **Servlet에서 가져오지 않는 것**: `Enumeration`(→ `List`/`Collection`), `Locale`/i18n, `RequestDispatcher`, `ServletContext`, JSP, 보안 역할, `HttpSession`(core 밖의 선택 필터로), `AsyncContext`/`ReadListener`.
3. **서버가 HTTP 프레이밍을 소유한다**(`keep-alive.md` §4). 핸들러는 경계가 정해진 바디 스트림과, 프레이밍을 서버가 관리하는 출력 스트림만 본다.
4. **Java 8 언어 수준, API 21 표준 라이브러리만 쓴다.** `java.util.function` 대신 자체 단일 메서드 인터페이스를 쓴다(람다 사용 가능). `Optional`은 쓰지 않고 `null`을 반환한다. default 메서드는 D8이 처리하지만, core 공개 인터페이스에서는 최소화한다.

### 4.2 core 인터페이스 스케치

> 2026-10-18: 아래는 설계 당시의 초안이다. 실제 API는 코드(`core/src/main/java/com/snoworca/tinyhttpd/http`)가 기준이다. 초안 이후 바뀐 점: `Request.getServerName()/getServerPort()`, `HttpException.withHeader`, 빌더의 `.tls(TlsConfig)`/`listen(...)`, SSE와 WebSocket은 워커 예산이 아닌 장수명 예산을 쓴다(P3), `sendFile(FileChannel…)`·`FileResource`·`FileResponses`(Range는 core), `EventStream.awaitClosed`.

패키지는 예시로 `com.snoworca.tinyhttpd.http`를 쓴다. 이름은 Jetty 12처럼 `Request`/`Response`/`Handler`를 제안한다. 테스트가 쓰는 Apache HttpClient의 `org.apache.http.HttpRequest/HttpResponse`, Java 11의 `java.net.http.HttpRequest`와 겹치지 않게 하려는 것이다. 기존 `protocols.http.response.Response`는 삭제한다(아래 §5).

```java
package com.snoworca.tinyhttpd.http;

import java.io.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 요청 하나를 처리한다. 반환하면 서버가 응답을 마무리하고 남은 요청 바디를 정리한다. */
public interface Handler {
    void handle(Request request, Response response) throws IOException, HttpException;
}

/** Servlet Filter와 같은 형태. chain.doFilter를 부르지 않으면 체인이 거기서 끝난다. */
public interface Filter {
    void doFilter(Request request, Response response, FilterChain chain) throws IOException, HttpException;
}

public interface FilterChain {
    void doFilter(Request request, Response response) throws IOException, HttpException;
}

/** 상태 코드를 지정해 던지면 서버가 (아직 커밋 전이라면) 오류 응답으로 바꾼다. */
public class HttpException extends Exception {
    private final int status;
    public HttpException(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
}

public interface Request {
    // --- 요청 라인 (Servlet과 같은 이름 = 같은 의미) ---
    String getMethod();
    String getRequestURI();          // 디코딩하지 않은 원본 경로 (쿼리 제외)
    String getQueryString();         // 원본, 없으면 null
    String getProtocol();            // "HTTP/1.1"
    String getScheme();              // "http" | "https"
    boolean isSecure();

    // --- TinyHttpd 고유 ---
    /** 퍼센트 디코딩과 정규화를 마친 경로. "..", NUL, 이중 인코딩은 파싱 단계에서 400으로 거부한다. */
    String getPath();

    // --- 헤더 ---
    String getHeader(String name);               // 대소문자 무시, 첫 값
    List<String> getHeaders(String name);        // 순서 보존, 없으면 빈 리스트
    Collection<String> getHeaderNames();
    long getDateHeader(String name);             // 없으면 -1
    String getContentType();
    long getContentLengthLong();                 // 모르면(chunked) -1
    String getCharacterEncoding();               // Content-Type의 charset, 없으면 null
    List<Cookie> getCookies();

    // --- 파라미터: 쿼리만 다룬다 (Servlet getParameter와 의미가 달라 이름을 바꿈) ---
    String getQueryParameter(String name);
    List<String> getQueryParameterValues(String name);
    Map<String, List<String>> getQueryParameterMap();

    // --- 바디: 셋 중 하나만 쓸 수 있다. 섞어 쓰면 IllegalStateException ---
    /**
     * 경계가 정해진 바디 스트림 (Content-Length 또는 chunked 디코딩).
     * 끝에서 -1을 반환하고 연결 스트림을 넘어 읽지 않는다.
     * Expect: 100-continue이면 첫 read에서 서버가 100을 보낸다.
     * close()는 연결을 닫지 않는다.
     */
    InputStream getInputStream() throws IOException;
    /** application/x-www-form-urlencoded 바디를 maxBytes까지 읽는다. 넘으면 HttpException(413). */
    Map<String, List<String>> readForm(int maxBytes) throws IOException, HttpException;
    /** multipart/form-data를 풀(pull) 방식으로 읽는다. 전체를 버퍼링하지 않는다. */
    MultipartReader readMultipart(MultipartLimits limits) throws IOException, HttpException;

    // --- 연결 정보 ---
    String getRemoteAddr();
    int getRemotePort();
    String getLocalAddr();
    int getLocalPort();

    // --- 필터·라우터 간 전달 ---
    Object getAttribute(String name);
    void setAttribute(String name, Object value);
    void removeAttribute(String name);
}

public interface Response {
    // --- Servlet과 같은 이름 = 같은 의미 ---
    void setStatus(int status);
    int getStatus();
    void setHeader(String name, String value);
    void addHeader(String name, String value);
    void setDateHeader(String name, long millis);
    String getHeader(String name);
    boolean containsHeader(String name);
    void setContentType(String type);
    void setContentLengthLong(long length);      // 지정하면 정확히 그만큼 써야 한다. 모자라면 서버가 연결을 끊는다
    void addCookie(Cookie cookie);
    boolean isCommitted();
    void flushBuffer() throws IOException;       // 헤더 커밋 + flush
    void resetBuffer();                          // 커밋 전에만
    void reset();                                // 커밋 전에만
    void sendError(int status, String message) throws IOException;
    void sendRedirect(String location) throws IOException;

    /**
     * 프레이밍은 서버가 관리한다. 길이를 지정하면 고정 길이로 보낸다.
     * 길이가 없을 때 HTTP/1.1이면 chunked, HTTP/1.0이면 close로 끝을 표시한다.
     * 길이가 없어도 핸들러가 끝날 때까지 출력이 버퍼(8KB)를 넘지 않았다면 Content-Length를 붙인다.
     * HEAD 요청이면 쓴 바이트를 버린다.
     * close()는 응답 바디를 끝낼 뿐 연결을 닫지 않는다.
     */
    OutputStream getOutputStream() throws IOException;
    /** 문자 인코딩: Content-Type의 charset, 없으면 UTF-8. Servlet 기본값(ISO-8859-1)과 다르므로 javadoc에 명시한다. */
    Writer getWriter() throws IOException;

    // --- TinyHttpd 확장 ---
    /** 응답 후 이 연결을 닫는다 (Connection: close). */
    void setCloseConnection(boolean close);
    /**
     * 파일 구간을 보낸다. 평문 소켓이면 FileChannel.transferTo를 시도하고, TLS면 버퍼 복사로 대체한다.
     * 상태 코드와 헤더(Content-Range 등)는 호출자가 정한다. Range 파싱은 webserver 모듈의 유틸이 맡는다.
     */
    void sendFile(File file, long offset, long length) throws IOException;
    /**
     * text/event-stream으로 커밋하고 SSE 스트림을 연다.
     * gzip을 끄고, Cache-Control: no-cache를 붙이고, 쓸 때마다 flush한다. 쓰기 타임아웃은 watchdog가 맡는다.
     * 스트림을 닫거나 핸들러가 반환하면 응답이 끝난다.
     */
    EventStream startEventStream() throws IOException;
    /** 101 Switching Protocols를 보내고 원시 스트림을 넘긴다. 이후 HTTP 처리는 끝난다. websocket 모듈이 쓴다. */
    UpgradedConnection upgrade(String protocol) throws IOException;
}

public interface EventStream extends Closeable {
    void send(String event, String data, String id) throws IOException;   // event/id는 null 가능, data의 개행은 여러 data: 줄로 나눈다
    void comment(String text) throws IOException;                          // ": ping" 하트비트. 끊긴 클라이언트를 감지한다
    void retry(long millis) throws IOException;
    boolean isOpen();
    String getLastEventId();                                               // 요청의 Last-Event-ID
}

public interface MultipartReader extends Closeable {
    /** 다음 파트. 앞 파트를 끝까지 읽지 않았으면 자동으로 건너뛴다. 끝이면 null. */
    Part next() throws IOException, HttpException;
}

public interface Part {
    String getName();
    String getSubmittedFileName();     // 경로 성분을 제거한 이름. 없으면 null
    String getContentType();
    String getHeader(String name);
    InputStream getInputStream();      // 경계까지만 읽는다. 크기 한도를 넘으면 IOException(→ 413)
}

public interface UpgradedConnection extends Closeable {
    InputStream getInputStream();      // 이미 버퍼에 읽어 둔 바이트부터 이어 준다
    OutputStream getOutputStream();
}
```

서버 조립 예(Java 8 람다, D8이 API 21용으로 변환):

```java
Router router = new Router();
router.get("/api/files/{path*}", fileApi::list);
router.post("/api/upload", (req, res) -> {
    MultipartReader mr = req.readMultipart(MultipartLimits.of(4L << 30, 100));
    for (Part p; (p = mr.next()) != null; ) {
        if (p.getSubmittedFileName() != null) storage.write(p.getSubmittedFileName(), p.getInputStream()); // 바로 디스크로
    }
    res.setStatus(204);
});
router.get("/events", (req, res) -> {
    EventStream es = res.startEventStream();
    hub.subscribe(es);                  // 브로드캐스트는 hub가 es.send(...)로 한다
    hub.awaitClose(es, 20_000);         // 20초마다 comment 하트비트, 실패하면 반환
});

TinyHttpd server = TinyHttpd.builder()
        .port(8080)
        .maxWorkers(48).maxConnections(128)
        .maxEventStreams(8)            // SSE는 워커 스레드를 점유하므로 따로 상한을 둔다
        .maxDrainBytes(64 * 1024)
        .sslServerSocketFactory(sslFactory)
        .filter(new AccessLogFilter())
        .handler(router)
        .build();
server.start();
```

서버 쪽 교환(exchange) 종료 순서(`keep-alive.md` §4.4~4.6 구현 위치):

```
handle(filters → handler)
  ├ HttpException 또는 미처리 예외 → 커밋 전이면 오류 응답, 커밋 후면 연결 닫기 표시
finishResponse()      // 미커밋이면 커밋. chunked 종료 청크. 선언 길이와 실제 쓴 길이가 다르면 닫기
drainRequestBody(maxDrainBytes)   // 넘거나 타임아웃이면 닫기
persistent = ... (keep-alive.md §4.6의 조건)
```

### 4.3 선택 모듈 `tinyhttpd-servlet` (보류, 필요할 때)

- 의존성은 `api 'javax.servlet:javax.servlet-api:3.1.0'`로 한다. 3.1.0은 API 21 animal-sniffer를 통과하는 마지막 javax 라인이다. 실제 Servlet을 APK에서 돌리려면 jar가 포함되어야 하므로 `compileOnly`가 아니다.
- `ServletHandler implements Handler`가 `HttpServlet` 하나와 `javax.servlet.Filter` 목록을 감싸고, `HttpServletRequestAdapter`/`HttpServletResponseAdapter`가 core 객체에 위임한다.

| 범주 | 처리 |
|---|---|
| 요청 라인·헤더·쿠키·속성·원격 주소·`getInputStream`·`getReader`·`getParameter*`(쿼리 + urlencoded 바디) | 위임해 구현 |
| 응답 상태·헤더·`getOutputStream`·`getWriter`·`sendError`·`sendRedirect`·버퍼 | 위임해 구현. `getWriter` 기본 인코딩은 Servlet 규칙(ISO-8859-1)을 따른다 |
| `getSession()` | 메모리 기반 단순 구현(만료 타이머 하나) |
| `getParts()` | `@MultipartConfig`가 있을 때만 임시 파일로 구현. 없으면 명세대로 `IllegalStateException` |
| `isAsyncSupported()` | `false`. `startAsync`와 `setReadListener`/`setWriteListener`는 명세대로 `IllegalStateException` |
| `ServletContext` | `getMimeType`, `getAttribute`, `getInitParameter`, `getContextPath`, `log`만 구현. `getRealPath`는 `null`(명세 허용). 동적 등록은 `UnsupportedOperationException` |
| `RequestDispatcher`, `login/authenticate`, `upgrade` | `UnsupportedOperationException`. **명세 비준수임을 README에 명시** |

---

## 5. 기존 모듈 영향

현재 버전이 `0.1.0-SNAPSHOT`이고 외부 사용자가 없으므로, 호환 계층 없이 한 번에 교체하는 것을 제안한다. [판단]

| 모듈 | 현재 | 변경 |
|---|---|---|
| **core** | `NanoHTTPD`(추상 서버 + MIME + SSL 유틸 + 인터셉터 목록), `IHTTPSession`/`HTTPSession`(파싱 + 바디 + `parseBody(Map files)`), `Response`(정적 팩토리 + 직렬화 + gzip + chunked), `IHandler<IHTTPSession, Response>`, `CookieHandler`, `tempfiles/*`, `DefaultAsyncRunner` | `TinyHttpd`(빌더/수명주기), `Connection`/`Exchange`(파싱·프레이밍, keep-alive.md §4.1), 위의 `Request`/`Response`/`Handler`/`Filter`/`EventStream`/`MultipartReader`. `parseBody`, `getParms`, `newFixedLengthResponse`, 인터셉터, `IStatus`는 삭제하고 상태는 `int` 상수 클래스로 둔다. temp file 관리자는 multipart가 디스크로 바로 쓰게 되므로 core에서 제거하거나 선택 유틸로 축소한다. `SecureServerSocketFactory`/`makeSSLSocketFactory`는 빌더 옵션으로 흡수한다 |
| **router** (`RouterNanoHTTPD`, 674줄) | `UriResponder.get/put/post/delete/other(UriResource, Map urlParams, IHTTPSession) → Response`, 핸들러를 **요청마다 `Class<?>` 리플렉션으로 생성**, `DefaultHandler`/`GeneralHandler`/`StaticPageHandler`/`Error404UriHandler` 계층 | `Router implements Handler`. `get/post/put/delete/route(method, pattern, Handler)`로 등록한다. 경로 변수는 `req.getAttribute(Router.PATH_PARAMS)` 또는 `PathParams.get(req, "id")`로 꺼낸다. 리플렉션 생성을 없애 요청당 할당과 예외 경로를 줄인다. 405(`Allow` 헤더 포함)와 404는 라우터가 응답한다. 기존 테스트는 다시 작성해야 한다 |
| **webserver** (`SimpleWebServer` 606줄, `WebServerPlugin`, `InternalRewrite`) | `serve()` 안에 디렉터리 목록, Range, ETag, CORS, 플러그인 디스패치가 섞여 있음. `InternalRewrite`는 `Response`를 상속해 재디스패치에 쓰는 편법 | `StaticFileHandler`(Range/206/416, If-None-Match, If-Modified-Since, HEAD, `sendFile` 사용), `DirectoryListingHandler`, `CorsFilter`. `WebServerPlugin.serveFile(... ) → Response`는 `Handler` 기반 플러그인으로 바꾼다. `InternalRewrite`는 `Router.forward(req, res, newPath)`나 필터에서의 경로 재작성으로 대체한다. CLI `main`은 남긴다 |
| **websocket** (`NanoWSD`, `WebSocket`) | `NanoWSD extends NanoHTTPD` + 인터셉터가 `IHTTPSession`을 받아 `SWITCH_PROTOCOL` `Response` 익명 서브클래스를 반환한 뒤 소켓을 가로챈다 | `WebSocketHandler implements Handler`: 핸드셰이크를 검증하고, `Sec-WebSocket-Accept`를 설정한 다음 `res.upgrade("websocket")`으로 원시 스트림을 받는다. `WebSocket`은 `IHTTPSession` 대신 핸드셰이크 `Request`의 스냅숏(헤더, 경로)을 보관한다. `WebSocketFrame`, `OpCode`, `CloseCode`, `CloseFrame`은 거의 그대로 쓴다. 업그레이드 연결도 워커를 점유하므로 `maxEventStreams`처럼 별도 상한(예: `maxUpgradedConnections`)을 둔다 |
| **테스트** | Apache HttpClient 4.5, OkHttp 4.12, Mockito 1.9.5, Jetty WebSocket client 9.3 | HTTP 수준 테스트(실제 소켓)는 대부분 재사용할 수 있다. `IHTTPSession`을 목(mock)으로 만드는 단위 테스트는 다시 써야 한다 |

---

## 6. 리스크와 미해결 질문

| 리스크 | 내용 | 완화 |
|---|---|---|
| SSE/WebSocket의 스레드 점유 | 블로킹 모델에서는 SSE 연결 하나가 워커 하나를 점유한다. 50명이 탭 2개씩 열면 풀이 고갈될 수 있다 [판단] | `maxEventStreams` 상한 + 초과 시 503. 하트비트로 죽은 연결을 회수한다. 측정 후 필요하면 SSE 전용 단일 스레드 브로드캐스터로 바꾸거나 Selector 기반 쓰기를 쓴다(keep-alive.md §4.8의 2단계) |
| "Servlet 같다"는 기대 | 이름이 같아 Servlet 코드를 그대로 붙여 넣으면 일부만 동작한다 | 원칙 4.1-1(이름이 같으면 의미도 같다)을 지키고, 다른 점을 javadoc과 README 표로 명시한다 |
| 자체 API 설계 실수의 고착 | 한 번 공개하면 바꾸기 어렵다 | 0.x 동안은 깨는 변경을 허용한다. router/webserver/websocket을 새 API로 먼저 옮겨 보며 API를 검증한다(내부 사용자 = 첫 고객) |
| javax 3.1 어댑터의 가치 | 생태계가 jakarta로 옮겨 갔다. API 21에서 디슈가링 없이 도는 servlet 기반 라이브러리가 얼마나 있는지는 조사하지 않았다 | 실제 요구(돌리고 싶은 라이브러리 목록)가 생길 때만 착수한다 |
| Jakarta 5.0의 런타임 동작 | `Supplier` 참조가 API 21~23에서 실제로 문제를 일으키는지 확인하지 않았다 | 어댑터를 jakarta로 만들고 싶어지면 실기기 API 21에서 D8 산출물로 시험한다 |
| `transferTo`와 TLS | TLS 소켓에서는 zero-copy가 불가능해 버퍼 복사가 필요하다 | `sendFile`이 내부에서 분기하도록 API를 설계했다 |
| `getWriter` 기본 인코딩 | 자체 API는 UTF-8, 어댑터는 Servlet 규칙(ISO-8859-1) | 문서화한다 |

미해결 질문(소유자 결정 필요):
1. 패키지/클래스 이름: `Request`/`Response`/`Handler` 대 `TinyRequest`/`TinyResponse`. 이 문서는 전자를 제안한다.
2. 세션(`HttpSession` 대응)을 core에 둘지, 선택 필터 모듈로 둘지. 이 문서는 후자를 제안한다.
3. 쿼리와 form 파라미터를 합친 Servlet식 `getParameter`를 편의 메서드로 둘지. 이 문서는 바디 소비가 숨는 것을 피하려고 두지 않는다.
4. `tinyhttpd-servlet` 어댑터를 로드맵에 넣을지, 요구가 생길 때까지 보류할지.

---

## 7. 출처

1. Jakarta Servlet 6.0 (Java SE 11 or higher, 제거된 API 목록) — https://jakarta.ee/specifications/servlet/6.0/ ; 6.1 (Java SE 17) — https://jakarta.ee/specifications/servlet/6.1/
2. TJWS 홈페이지 (footprint, Android/Atjeews, Servlet 4.0 "mostly compatible") — https://tjws.sourceforge.net/
3. TJWS Open Hub (inactive) — https://openhub.net/p/tjws
4. Winstone 0.9.10 (166KB, Servlet 2.4) — https://winstone.sourceforge.net/ , https://central.sonatype.com/artifact/net.sourceforge.winstone/winstone
5. i-jetty (2020-03-25 아카이브) — https://github.com/jetty-project/i-jetty
6. AOSP external/jetty README.android (Jetty 8.1.15, NON_ANDROID_SRC) — https://android.googlesource.com/platform/external/jetty/+/refs/heads/marshmallow-dev/README.android
7. Javalin 문서 (Context, SSE, Android: Javalin 5+ 비호환) — https://javalin.io/documentation ; Javalin 4 문서 (Android minSdk 26) — https://javalin.io/archive/docs/v4.6.X.html
8. Jetty 11+ on Android, `ServiceLoader$Provider` — https://github.com/eclipse/jetty.project/issues/8912
9. Jenkins Winstone README (자체 구현 포기 → Jetty) — https://github.com/jenkinsci/winstone
10. Jetty 9.4 커뮤니티 지원 종료 — https://eclipse.org/lists/jetty-dev/msg03628.html , https://jetty.org/download.html
11. Undertow 문서 (HttpServerExchange, drain, startBlocking, servlet 모듈) — https://undertow.io/undertow-docs/undertow-docs-2.1.0/index.html
12. Undertow SSE javadoc — https://undertow.io/javadoc/2.0.x/io/undertow/server/handlers/sse/ServerSentEventConnection.html , https://undertow.io/javadoc/1.4.x/io/undertow/server/handlers/sse/ServerSentEventHandler.html
13. Android Java 8 지원 (D8 desugar, API desugaring) — https://developer.android.com/studio/write/java8-support
14. CDDL 1.0 (§3.1, §3.5, §3.6) — https://opensource.org/license/cddl-1-0
15. EPL-2.0 원문 (§3.1, Modified Works 정의) — https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt
16. Spark Java — https://github.com/perwendel/spark
17. Jetty 12 프로그래밍 가이드 (Handler) — https://jetty.org/docs/jetty/12/programming-guide/server/http.html ; Jetty 12 소개 — https://webtide.com/introducing-jetty-12/
18. Jetty 12 `Request` javadoc (read/demand/consumeAvailable) — https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/server/Request.html
19. JDK `HttpExchange` javadoc — https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html
20. Android에서 `com.sun.net.httpserver` 부재 사례 — https://github.com/piyush01123/Android-Server
21. Helidon SE 4 WebServer — https://helidon.io/docs/v4/se/webserver
22. Helidon 4 Java 21 요구 — https://helidon.io/docs/v4/about/intro , https://www.infoworld.com/article/3709929/oracle-helidon-4-java-microservices-framework-stresses-virtual-threads.html
23. Netty 4.1 Android 지원 — https://netty.io/wiki/new-and-noteworthy-in-4.1.html
24. OkHttp MockWebServer README — https://github.com/square/okhttp/blob/master/mockwebserver/README.md
25. AndroidAsync — https://github.com/koush/AndroidAsync
26. Maven Central (jar 크기, POM 라이선스, 바이트코드 검사 원본) — https://repo1.maven.org/maven2/javax/servlet/javax.servlet-api/ , https://repo1.maven.org/maven2/jakarta/servlet/jakarta.servlet-api/ , https://repo1.maven.org/maven2/org/eclipse/jetty/ , https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/ , https://repo1.maven.org/maven2/io/netty/ , https://repo1.maven.org/maven2/io/undertow/
27. 저장소 내부: `core/.../HTTPSession.java`(execute의 drain TODO), `docs/research/keep-alive.md`, `docs/research/upstream-issues.md` §3

### 부록: 재현 방법 (검증 항목)
- jar는 scratchpad의 빈 디렉터리(`/private/tmp/claude-501/-Users-beom-work-git/caf2e91e-e8e1-4710-979c-00186efcdea3/scratchpad/jars`)에 내려받았고 실행하지 않았다.
- major 버전: 각 `.class`의 바이트 6~7 (`xxd`).
- API 21 검사: Gradle 캐시의 `org.codehaus.mojo:animal-sniffer:1.24` `SignatureChecker`에 `android-api-level-21-5.0.1_r2.signature`를 주고 압축을 푼 클래스 디렉터리를 검사했다(`javax.servlet.*`/`jakarta.servlet.*` 자체 참조는 무시).
- 메서드 수: `javap -cp <dir> <interface>`의 `abstract` 메서드를 셌다(default/static 제외).
