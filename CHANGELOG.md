# Changelog

## 3.1.0 - 2026-10-24

## English

TinyHttpd 3.1.0 adds the published contracts required by the NaruServe adapter. It preserves the immutable 3.0.0 release.

- `Request.getHeaderEntries()` exposes every header in received order, preserving duplicate names and their original case. Wrappers delegate it; the default implementation keeps older Request implementations compatible.
- A non-101 response with `Upgrade` and `Connection: Upgrade` now preserves the upgrade offer, including HTTP 426 responses.
- `Response.abort()` cancels the active connection without sending a synthetic error response or completing the body. It supports GET and HEAD, wakes blocked writers, closes SSE owners, and prevents reuse of the cancelled connection. Aborting a completed response does not cancel the next request on a reused connection.
- Body streams and Writers reject writes and flushes after cancellation. `ResponseWrapper` delegates abort; older custom Response implementations inherit a default method that throws `UnsupportedOperationException`.

Use `com.snoworca:tinyhttpd-core:3.1.0` and optional modules at the same version. Published 3.0.0 does not contain these new APIs. Java 8 bytecode and Android API 21 API checks remain in place; physical Android device verification remains pending.

Downloads contain binary, sources and Javadoc JARs for all five modules, plus `SHA256SUMS` and license notices. TinyHttpd independently continues NanoHttpd; BSD 3-Clause and the original NanoHttpd notices remain included in every JAR. Preserve those notices when redistributing.

## 한국어

TinyHttpd 3.1.0은 NaruServe 어댑터에 필요한 계약을 정식 배포합니다. 기존 3.0.0 배포 파일은 변경하지 않습니다.

- `Request.getHeaderEntries()`로 중복 헤더와 원래 이름 표기를 보존하면서 수신 순서대로 모든 헤더를 읽습니다. 래퍼는 호출을 전달하며 기본 메서드는 기존 Request 구현의 호환성을 유지합니다.
- 101 이외의 응답에서 `Upgrade`와 `Connection: Upgrade`를 함께 지정하면 HTTP 426을 포함한 업그레이드 제안을 유지합니다.
- `Response.abort()`는 오류 응답이나 바디 종료를 만들어 보내지 않고 활성 연결을 중단합니다. GET·HEAD, 막힌 쓰기 깨우기, SSE 소유자 종료를 지원하고 중단된 연결의 재사용을 막습니다. 완료된 응답을 중단해도 재사용 연결의 다음 요청은 취소되지 않습니다.
- 중단 후 바디 스트림·Writer의 쓰기와 flush는 실패합니다. `ResponseWrapper`는 중단을 전달하며, 기존 사용자 정의 Response는 `UnsupportedOperationException`을 던지는 기본 메서드를 상속합니다.

`com.snoworca:tinyhttpd-core:3.1.0`과 같은 버전의 선택 모듈을 사용하십시오. 기존에 배포한 3.0.0에는 새 API가 없습니다. Java 8 바이트코드와 Android API 21 API 검사를 유지하며, Android 실기기 검증은 아직 진행 전입니다.

5개 모듈의 바이너리·소스·Javadoc JAR, `SHA256SUMS`, 라이선스 고지를 제공합니다. TinyHttpd는 NanoHttpd를 독립적으로 계승·개선했으며 모든 JAR에 BSD 3-Clause 및 원본 NanoHttpd 고지를 포함합니다. 재배포 시 해당 고지를 보존하십시오.

## 3.0.0 - 2026-10-22

## English

TinyHttpd 3.0.0 is the first TinyHttpd release: a lightweight embedded HTTP server for Java and Android, independently continued from NanoHttpd. It is based on NanoHttpd's `2.3.2-SNAPSHOT` development commit `efb2ebf85a2b06f7c508aba9eaad5377e3a01e81` (2019-07-03), with a rewritten engine and redesigned public API. This is a TinyHttpd version, not an upstream NanoHttpd release.

### Highlights

- No external runtime dependencies; five composable library modules.
- On-demand bounded workers, connection limits, reusable buffers, configurable timeouts, and graceful shutdown.
- HTTP/1.1 keep-alive and chunked bodies; streaming multipart uploads and file downloads with Range and conditional-request support.
- Request/Response/Handler/Filter API, static file serving, method/path routing, optional sessions and CSRF protection.
- SSE, optional WebSocket support, and configurable TLS using the platform's supported protocols and cipher suites.
- Java 8 bytecode; Android API 21 compatibility checks in the build. Physical Android device validation remains pending.

### Downloads and usage

The assets include a binary JAR, sources JAR, and Javadoc JAR for each module:

- `tinyhttpd-core`
- `tinyhttpd-webserver`
- `tinyhttpd-websocket`
- `tinyhttpd-router`
- `tinyhttpd-session`

For a local Java application, put the core JAR and the binary JARs for the optional modules you use on the classpath. Optional modules require the core. Sources and Javadoc JARs are for IDE browsing. See the bilingual README for a runnable tutorial. `SHA256SUMS` covers all download files except itself.

The API package is `com.snoworca.tinyhttpd`. Existing NanoHttpd applications must migrate their code; the old `serve()` API is not compatible. Memory usage depends on configuration, workload, and platform.

### License and provenance

BSD 3-Clause. Each library JAR contains the TinyHttpd license, attribution notice, and unmodified NanoHttpd license under `META-INF/`. The same documents are provided as standalone release assets. Preserve the original copyright notices, conditions, and disclaimers when redistributing source or binaries. NanoHttpd authors include Paul S. Hawke, Jarno Elonen, and Konstantinos Togias. No endorsement by the NanoHttpd organization or its contributors is claimed.

---

## 한국어

TinyHttpd 3.0.0은 Java·Android용 경량 임베디드 HTTP 서버 TinyHttpd의 첫 릴리스입니다. NanoHttpd `2.3.2-SNAPSHOT` 개발 버전의 `efb2ebf85a2b06f7c508aba9eaad5377e3a01e81` 커밋(2019-07-03)에서 출발해 HTTP 엔진과 공개 API를 다시 설계하고 독립적으로 개선했습니다. 이 번호는 TinyHttpd의 버전이며 원본 NanoHttpd의 릴리스 번호가 아닙니다.

### 주요 변경

- 외부 런타임 의존성 없이 필요한 모듈을 조합하는 5개 라이브러리 구성.
- 필요 시 생성하는 워커와 연결 수 상한, 버퍼 재사용, 설정 가능한 타임아웃과 정상 종료 대기.
- HTTP/1.1 keep-alive·청크 전송, 스트리밍 multipart 업로드, Range·조건부 요청을 지원하는 파일 다운로드.
- Request/Response/Handler/Filter API, 정적 파일 서버, 메서드·경로 라우팅, 선택형 세션·CSRF 보호.
- SSE, 선택형 WebSocket, 플랫폼이 지원하는 프로토콜·암호군에 맞춰 설정하는 TLS.
- Java 8 바이트코드와 Android API 21 호환성 빌드 검사. Android 실기기 검증은 아직 진행 전입니다.

### 다운로드와 사용

각 모듈의 바이너리·소스·Javadoc JAR를 제공합니다.

- `tinyhttpd-core`
- `tinyhttpd-webserver`
- `tinyhttpd-websocket`
- `tinyhttpd-router`
- `tinyhttpd-session`

로컬 Java 앱에서는 코어 JAR와 사용하는 선택 모듈의 바이너리 JAR를 클래스패스에 넣으세요. 선택 모듈은 코어에 의존합니다. 소스·Javadoc JAR는 IDE에서 코드를 탐색할 때 사용합니다. 실행 예제는 영문·한국어 README에 있습니다. `SHA256SUMS`에는 자기 자신을 제외한 다운로드 파일의 체크섬이 들어 있습니다.

API 패키지는 `com.snoworca.tinyhttpd`입니다. 기존 NanoHttpd 앱은 코드를 이식해야 하며 기존 `serve()` API와 직접 호환되지 않습니다. 메모리 사용량은 설정·부하·플랫폼에 따라 달라집니다.

### 라이선스와 출처

BSD 3-Clause입니다. 각 라이브러리 JAR의 `META-INF/`에 TinyHttpd 라이선스·출처 고지·수정하지 않은 NanoHttpd 원본 라이선스를 포함했습니다. 같은 문서를 릴리스 첨부 파일로도 제공합니다. 소스·바이너리 재배포 시 원저작권 고지·조건·면책 조항을 유지하세요. NanoHttpd 원저작자에는 Paul S. Hawke, Jarno Elonen, Konstantinos Togias가 포함됩니다. NanoHttpd 조직이나 기여자의 보증·추천을 주장하지 않습니다.
