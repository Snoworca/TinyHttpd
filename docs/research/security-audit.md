# 보안 감사 (P9-03)

- 날짜: 2026-10-18
- 커밋: `5bf1c16` (감사 시작 시 `37e6143`. 그 사이 커밋은 테스트·빌드·문서만 바꿔 `*/src/main`은 같다)
- 범위: `core/src/main`(server, http, http.filter, http.util), router, webserver, websocket, session
- 위협 모델: docs/next/p09-security.md (LAN 공격자, 악성 웹 페이지, 포트 포워딩 노출, 자원 고갈 = 서비스 거부)
- 방법: 점검표를 보기 전에 코드만 읽고 발견 사항을 찾았다. 그 뒤 SEC 표와 상한 표를 한 줄씩 확인했다. "재현"은 저장소 밖 임시 클래스를 HEAD 소스로 컴파일해 돌린 결과다.
- 빌드: `./gradlew build -q` 결과는 맨 아래에 있다.

## 발견 사항

| ID | 심각도 | 위치 | 시나리오 | 제안 |
|---|---|---|---|---|
| AUD-01 | MEDIUM | `server/Connection.java:224-225`, `server/BodyInput.java:109-136`, `server/HttpServer.java:357-375` | 바디 읽기와 drain은 read 한 번마다 `bodyReadTimeout`만 건다. 총 시간 제한도 최소 속도도 없다. 워치독도 HANDLING/DRAINING을 보지 않는다. `Content-Length: 60000`을 보내고 바이트를 29 s마다 하나씩 흘리면, 핸들러가 바디를 읽든 무시하든(무시하면 drain 64 KB 예산 안) 워커가 사실상 무기한 묶인다. 기본값이면 주소당 32개라 주소 두 개로 워커 64개를 모두 잡는다. 재현: `maxWorkers 2`, `bodyReadTimeout 500 ms`에서 300 ms마다 1바이트를 보냈다. 5 s(타임아웃의 10배) 뒤에도 busy=2였고, 새 클라이언트는 503을 받았다. | drain에 총 마감(`lingerTimeout`처럼)을 둔다. 바디 읽기에는 최소 전송률이나 총 마감을 두고, 워치독이 HANDLING/DRAINING의 마감도 집행하게 한다. |
| AUD-02 | MEDIUM | `server/InputBuffer.java:38-53, 82-88`, `server/BodyInput.java:252`, `TinyHttpd.java:575-577` | `build()`는 `maxRequestLineBytes`만 `inputBufferBytes - 2`와 비교하고 `maxChunkSizeLineBytes`는 비교하지 않는다. `maxChunkSizeLineBytes >= inputBufferBytes - 1`이면 LF 없는 긴 청크 크기 줄에서 버퍼가 가득 찬다. 그 상태에서 `fill()`이 길이 0으로 read해 0을 돌려주므로 `readLine`이 끝없이 돈다. 소켓 타임아웃도 걸리지 않는다. 메모리를 줄이려고 `inputBufferBytes(1024)`로 두면 기본값 1024에서 바로 이 조건이 된다. 재현: 1100바이트 크기 줄을 보낸 뒤 클라이언트가 닫았다. 워커가 3 s 동안 CPU 3011 ms(코어 하나 100%)를 썼고, `stop()` 뒤에도 남았다("1 worker threads still running after stop"). | `build()`에서 `maxChunkSizeLineBytes <= inputBufferBytes - 2`를 검사한다. `readLine`은 버퍼가 가득 차 더 못 읽으면 too-long으로 실패하게 한다(`fill()`이 0이면 오류). |
| AUD-03 | MEDIUM | `websocket/WebSocket.java:321-325, 339-352` | 프레임 페이로드 배열을 데이터가 오기 전에 선언 길이대로 만든다(14바이트 헤더 하나 → 1 MB 할당). 조각 메시지는 `ByteArrayOutputStream`이 두 배씩 커지고, 끝나면 `toByteArray`로 한 번 더 복사한다. 기본값(16연결, 1 MB, 1 MB)이면 연결당 4 MB 안팎, 합쳐서 수십 MB가 된다. 구형 폰 힙에서는 OOM 위험이 있다. `Origin`이 없는 클라이언트는 출처 검사를 통과한다. 이 항목은 테스트 없이 코드로만 확인했다. | 기본 상한을 낮춘다(예: 64 KB). 페이로드는 실제로 받은 만큼 늘리며 읽는다. 메시지 버퍼는 상한 검사 뒤 정확한 크기로 잡는다. 총 메모리를 P9-02 표에 적는다. |
| AUD-04 | LOW | `webserver/StaticFileHandler.java:106-124` | 디렉터리의 index 파일은 `resolve()`의 루트 검사를 거치지 않는다. 루트 안의 `d/index.html`이 루트 밖을 가리키는 심볼릭 링크면 `GET /d/`가 그 파일을 보낸다(`followSymlinksOutsideRoot=false`인데도). 재현: 같은 링크를 직접 요청하면 403인데, `/d/`로 요청하면 200과 비밀 파일 내용이 왔다. 전제: 공격자가 루트에 링크를 만들 수 있어야 한다(데스크톱 배포, 공유 폴더). | index 후보도 `resolve(path + name)`처럼 정규 경로 검사를 거친다. |
| AUD-05 | LOW | `webserver/StaticFileHandler.java:102-103` | 디렉터리 리다이렉트를 원시 `getRequestURI()`로 만든다. `GET //evil.example`이 루트의 `evil.example` 디렉터리로 정규화되면 `Location: //evil.example/`이 나가고, 브라우저는 `http://evil.example/`로 간다(오픈 리다이렉트). 재현했다. 전제: 공격자가 정한 이름의 디렉터리가 있어야 한다(업로드 폴더 등). | `Location`을 정규화된 `getPath()`를 세그먼트별로 인코딩해 만든다. 앞 슬래시는 하나만 둔다. |
| AUD-06 | LOW | `server/HttpServer.java:262-282, 386-394` | 거절한 평문 소켓(용량 초과, 주소별 초과)을 `lingering` 목록에 `lingerTimeout`(2 s) 동안 둔다. 목록에 상한이 없어서 연결 폭주를 받으면 열린 fd가 초당 거절 수 × 2 s만큼 쌓인다. fd 한도가 낮은 기기에서는 fd가 고갈되어 accept와 `sendFile`의 파일 열기가 실패할 수 있다. | `lingering` 크기에 상한(예: `maxConnections`)을 두고, 넘으면 바로 닫는다. |
| AUD-07 | LOW | `TlsConfig.java:211-217, 293-294` | `clientAuth(WANT/NEED)`에 `trustManagers`를 주지 않으면 플랫폼 기본 CA 저장소를 쓴다. 그러면 공개 CA가 발급한 아무 클라이언트 인증서나 통과한다. 앱이 "인증서 있음 = 인증됨"으로 처리하면 인증이 우회된다. | `clientAuth != NONE`이면 `build()`에서 명시적 `trustManagers`를 요구하거나, 최소한 문서에 경고한다. |
| AUD-08 | LOW | `server/HttpServer.java:234-247`, `server/Connection.java:146` | 헤더 slowloris는 `headerTimeout`(10 s)으로 연결마다 끊긴다. 하지만 주소당 32 ≥ `maxWorkers`/2라서, 주소 두 개가 10 s마다 다시 연결하기만 하면 워커 전부를 계속 잡는다. 주소별 상한은 IPv6 주소 하나 단위로 센다. | `maxConnectionsPerAddress` 기본값을 `maxWorkers`/4 이하(예: 16)로 낮춘다. 부하 중에는 헤더 마감을 줄인다. 구조상 남는 위험은 수용으로 기록한다. |

발견하지 못한 것(확인함): TE+CL/obs-fold/단독 CR 스머글링, 응답 헤더·쿠키·SSE 필드 주입, 경로의 `%2F`·점 세그먼트·NUL, 목록·오류 페이지 XSS, `X-Forwarded-*` 기본 신뢰, CSWSH(기본 동일 출처), CSRF 필터의 Origin/Referer 처리, 세션 ID(128비트 SecureRandom, 임의 ID 채택 안 함), TLS 기본값(1.2+, AEAD), multipart 파일 이름 정리, 응답의 스택 트레이스.

## SEC 점검표

| ID | 결과 | 코드 | 테스트 |
|---|---|---|---|
| SEC-01 | 충족 | `RequestParser.java:118-131`(obs-fold, 콜론 앞 공백), `:152-199`(TE/CL), `InputBuffer.java:72-76`(단독 CR) | `rq02_`, `rq04_`, `rq05_`, `rq08_`~`rq12_`, `rq_fuzz_mutatedRequests` |
| SEC-02 | 미충족 | 아래 상한 표 참고 | AUD-01, AUD-02, AUD-03, AUD-06 |
| SEC-03 | 충족 | `ExchangeResponse.java:137-149`(헤더), `:396`(`Location`), `Cookie.java:36-51, 188-198` | `rs10_rs11_headerValidationAndRepeats`, `api16_setCookieSerialization` |
| SEC-04 | 미충족(AUD-04) | `PercentCodec.java:31-87`, `StaticFileHandler.java:147-158`. index 파일 경로는 검사하지 않는다 | `api09_api13_pathDecoding`(API-10/11 단언 포함), `mod08_symlinkOutsideRoot` |
| SEC-05 | 충족 | `Html.java`, `DirectoryListingHandler.java:36-47`, `ErrorHandler.PLAIN`(text/plain). `GeneralHandler` 없음 | `api19_htmlEscape`, `mod09_listingEscapesNames` |
| SEC-06 | 충족 | `Multipart.java:435-450` | `io10_io11_fileNames` |
| SEC-07 | 미충족(AUD-01) | 헤더 마감 `Connection.java:146, 211-219`, 쓰기 `HttpServer.java:363-366`. 바디는 read마다만 건다 | `th04_`, `th05_`(완전히 멈춘 바디만), `th06_` |
| SEC-08 | 충족 | `ForwardedHeadersFilter.java:31-35`. 기본 필터 목록에 없다 | `api23_secT01_forwardedHeadersOnlyFromTrustedProxies` |
| SEC-09 | 충족 | `WebSocketHandler.java:155-186` | `mod26_originCheck` |
| SEC-10 | 충족 | `Session.java:42-46`, `SessionFilter.java:84-97, 109-162` | `mod20_`~`mod24_` |
| SEC-11 | 충족(AUD-07 참고) | `TlsConfig.java:44-52, 143`, `Tls.java:47-71`, `HttpServer.java:368` | `tls01_`~`tls05_` |
| SEC-12 | 충족 | `*/src/main`에 `createTempFile`·`java.io.tmpdir`가 없다(grep). 앱 가이드는 P12-04 몫 | – |
| SEC-13 | 충족 | `Connection.java:282`(500에 메시지 없음), `TinyHttpd.java:117`(`serverHeader` 기본 null), `StaticFileHandler.java:115-116` | `rs13_rs15_handlerExceptions`, `secT02_listingOffByDefault` |
| SEC-14 | 충족 | `HostAllowlistFilter.java`, `ServerConfig.java:95-97`(기본 켬) | `api20_api21_hostAllowlistIsOnByDefault` |
| SEC-15 | 충족 | `CsrfFilter.java:33-85` | `mod25_csrf` |
| SEC-16 | 충족 | `build.gradle`의 `checkRuntimeDependencies`(`check`에 연결) | 빌드 |
| SEC-17 | 충족 | 퍼저 4종. 기본 버퍼 크기로만 돌아서 AUD-02는 잡지 못했다 | `rq_fuzz_mutatedRequests`, `secT03_chunkedBodies`, `secT03_multipartBodies`, `secT03_websocketFrames` |
| SEC-18 | 충족 | `http/util/Base64.java`(자체 구현) | `api22_base64` |
| SEC-19 | 충족 | `FileResponses.java:51`(nosniff), `StaticFileHandler.java:118-143` | `mod30_untrustedContent` |

## P9-02 자원 상한 표

| 입력 | 결과 | 코드 | 테스트 |
|---|---|---|---|
| 동시 연결 | 충족 | `WorkerPool.java:107-115` | `th02_overCapacityGets503` |
| 주소별 연결 | 충족(AUD-08 참고) | `HttpServer.java:234-247` | `th16_perAddressLimit` |
| 대기열 대기 시간 | 충족 | `WorkerPool.java:201-221` | `th09_queuedConnectionTimesOutWith503` |
| 워커 스레드 | 충족 | `WorkerPool.java:107` | `th01_idleConnectionsAreBoundedByMaxWorkers` |
| 요청 라인 | 충족 | `RequestParser.java:17`, `TinyHttpd.java:575-577` | `rq20_requestLineTooLong` |
| 헤더 줄 / 전체 / 개수 | 충족 | `RequestParser.java:109-118` | `ka06_`, `rq26_`, `rq27_` |
| 헤더 수신 시간 | 충족 | `Connection.java:146, 211-219`, `HttpServer.java:367` | `th04_slowHeaderGets408` |
| 요청 바디 | 수용(기본 상한 없음. 스트리밍 업로드를 위해서이며, 핸들러가 `readForm`/`MultipartLimits`로 정한다) | `RequestParser.java:188-190`, `BodyInput.java:75-77` | `rs23_`, `rq28_`, `io06_`, `io12_io13_` |
| 미소비 바디 | 충족(크기만. 시간은 AUD-01) | `BodyInput.java:109-136`, `Connection.java:346` | `rq17_unreadLargeBodyClosesAfterResponse` |
| 청크 크기 줄 / trailer | 미충족(AUD-02: 버퍼와의 관계를 검사하지 않아 설정에 따라 상한이 무력화된다) | `BodyInput.java:251-290` | `rq14_`, `rq15_` |
| form 바디 | 충족 | `Exchange.java:271-298` | `io12_io13_readForm` |
| multipart 파트 수 / 크기 / 헤더 | 충족 | `Multipart.java:104, 160, 198, 260` | `io05_`, `io06_` |
| 연결당 요청 수 | 충족 | `Connection.java:341` | `rs16_maxRequestsPerConnection` |
| SSE 스트림 / 대기열 | 충족 | `SseStream.java:60-63, 150-166` | `sse06_`, `sse08_sse09_` |
| WebSocket 연결 / 프레임 / 메시지 | 충족(메모리 총량은 AUD-03) | `Upgraded.java:77-80`, `WebSocket.java:321-349` | `mod16_`, `mod19_` |
| 장수명 연결 스레드 | 충족 | `SseStream.java:60`, `Upgraded.java:77` | `sse08_sse09_`, `mod19_` |
| 세션 수 / 주소별 | 충족 | `SessionFilter.java:128-136` | `mod23_`, `mod24_` |
| TLS 핸드셰이크 시간 | 충족 | `Tls.java:50-51`, `HttpServer.java:368` | `tls05_` |
| `sendFile` 복사 버퍼 | 충족 | `FileTransfer.java:41-42`, `BufferPool.java:29-34` | `io31_` |
| (추가) 바디 수신·drain 총 시간 | 미충족(AUD-01) | 상한 없음 | – |
| (추가) WebSocket 메모리 총량 | 미충족(AUD-03) | 연결당 최대 수 MB × 16 | – |
| (추가) 거절 후 linger 소켓 수 | 미충족(AUD-06) | 상한 없음 | – |

## 빌드

`./gradlew build -q`를 한 번 실행했고 **실패**했다(1분 6초). `:tinyhttpd-core:test`에서 테스트 251개 중 1개 실패, 5개 건너뜀이었다. 같은 실행에서 "Could not write XML test results" 오류가 18건 났다. 다른 Gradle 실행이 같은 시각에 `core/build`를 쓰고 있었던 것으로 보인다. 또 작업 트리에는 다른 작업자가 커밋하지 않은 `HttpServer.java`, `WorkerPool.java`, `ServerStats.java` 수정이 있었다. 그래서 HEAD의 결과로 볼 수 없다. `-q` 출력에는 실패한 테스트 이름이 없고, 지시대로 다시 실행하지 않았다. 동시 작업이 끝난 뒤 다시 확인해야 한다.

## 처리 결과 (2026-10-18)

| ID | 처리 | 확인 |
|---|---|---|
| AUD-01 | 부분 수정. drain에 총 마감(`bodyReadTimeout`)을 두었다. 핸들러가 읽는 바디는 **수용**: NAS 업로드는 느린 망에서 정상적으로 오래 걸리므로 총 시간 상한을 두지 않는다. 주소별 연결 상한(32)과 읽기당 타임아웃으로 완화하고, 최소 전송률 옵션은 P10 측정 뒤 다시 판단한다 | `aud01_slowDrainIsBounded` |
| AUD-02 | 수정. 빌더가 `inputBufferBytes ≥ maxChunkSizeLineBytes + 2`를 검사하고, 줄 읽기는 버퍼가 가득 찼는데 줄 끝이 없으면 오류로 끝낸다 | `api02_invalidOptionIsNamed` |
| AUD-03 | 수정. 64 KB를 넘는 페이로드는 받은 만큼만 버퍼를 키운다 | `mod16_tooLargeMessage`, `secT03_websocketFrames` |
| AUD-04 | 수정. index 파일도 루트 검사를 거친다 | `aud04_indexSymlinkOutsideRoot` |
| AUD-05 | 수정. 디렉터리 리다이렉트를 정규화한 경로로 만든다 | `aud05_noProtocolRelativeRedirect` |
| AUD-06 | 수정. linger 목록은 `maxConnections`개까지이고, 넘으면 바로 닫는다 | 코드 검토 |
| AUD-07 | 수정. 클라이언트 인증을 켜면 `trustManagers`가 필수다 | `aud07_clientAuthNeedsTrustManagers` |
| AUD-08 | **수용**. 기본값(주소별 32, 헤더 10 s)의 조정은 P10 실기기 측정(P10-04)에서 다시 정한다 | – |

위 처리로 SEC-02, SEC-04, SEC-07과 상한 표의 미충족 행은 "충족" 또는 "수용(이유)"이 되었다.
