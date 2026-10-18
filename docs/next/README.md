# TinyHttpd 작업 계획

- 작성일: 2026-10-18
- 근거 자료: [core 구조 분석](../research/core-analysis.md), [keep-alive 연구](../research/keep-alive.md), [Servlet API 연구](../research/servlet-api.md), [업스트림 결함 목록](../research/upstream-issues.md)
- 이 디렉터리는 **앞으로 할 일**을 적는다. 조사 결과와 근거는 `docs/research/`에 두고 여기서는 링크만 건다.

## 1. 확정된 결정

연구 문서의 제안을 모두 채택했다(2026-10-18, 소유자 승인). CLAUDE.md "결정 사항"에도 같은 내용을 적었다.

| ID | 결정 | 근거 |
|---|---|---|
| D-01 | 공개 API는 **Servlet 모양의 자체 API**로 한다. core는 servlet jar에 의존하지 않는다 | servlet-api.md §1, §3.4 옵션 C |
| D-02 | 핵심 타입 이름은 `Request` / `Response` / `Handler` / `Filter`, 패키지는 `com.snoworca.tinyhttpd.http` | servlet-api.md §4.2, 미해결 질문 1 |
| D-03 | 세션(`HttpSession`에 해당)은 core에 넣지 않고 선택 모듈 `tinyhttpd-session`의 필터로 제공한다 | 미해결 질문 2 |
| D-04 | 쿼리와 form을 합친 `getParameter`는 두지 않는다. 쿼리는 `getQueryParameter*`, 바디는 `readForm`으로 명시적으로 읽는다 | 미해결 질문 3 |
| D-05 | `tinyhttpd-servlet`(javax.servlet-api 3.1.0 어댑터)을 **로드맵에 넣는다.** 대신 core API가 안정된 뒤 마지막 단계(P11)에서 진행한다 | 미해결 질문 4 |
| D-06 | 구 코드를 고치지 않는다. **새 엔진과 새 API를 새 패키지에 처음부터 작성**하고, 모든 모듈을 옮긴 뒤 구 패키지를 삭제한다 | §3 |
| D-07 | 0.x 동안은 호환 계층 없이 깨는 변경을 허용한다. 외부 사용자가 없기 때문이다 | servlet-api.md §5 |

## 2. 단계

| 단계 | 문서 | 내용 | 선행 |
|---|---|---|---|
| P0 | [p00-foundation.md](p00-foundation.md) | 저장소 정리, CI, 측정 도구, 테스트 기반 이전 | – |
| P1 | [p01-request-parsing.md](p01-request-parsing.md) | API 골격, Connection/Exchange, 요청 파싱, 요청 바디 프레이밍, drain, 100-continue | P0 |
| P2 | [p02-response-framing.md](p02-response-framing.md) | 응답 직렬화, HEAD/204/304, 길이 검증, 출력 버퍼, 연결 유지 결정 | P1 |
| P3 | [p03-threading-lifecycle.md](p03-threading-lifecycle.md) | 워커 풀, 연결 상한, 타임아웃 분리, watchdog, lingering close, graceful shutdown | P2 |
| P4 | [p04-public-api.md](p04-public-api.md) | 공개 API 완성: 빌더, 필터, 오류 처리, 쿠키, 경로 정규화, 로깅 | P1~P3 |
| P5 | [p05-body-io.md](p05-body-io.md) | 스트리밍 multipart, `readForm`, `sendFile`, Range, gzip 정책 | P4. P5-04 결정은 P10-01/02(측정 앱, 기기)도 필요 |
| P6 | [p06-sse.md](p06-sse.md) | `EventStream`, 하트비트, 상한, 쓰기 타임아웃 | P3, P4 |
| P7 | [p07-tls.md](p07-tls.md) | TLS 연구 문서 작성 후 구현: 안전한 기본값, 핸드셰이크 타임아웃, keystore | P3 |
| P8 | [p08-module-migration.md](p08-module-migration.md) | router, webserver, websocket, session 모듈을 새 API로 이전. 구 패키지 삭제 | P4~P6, P7(P8-05 구 TLS 코드 삭제 전) |
| P9 | [p09-security.md](p09-security.md) | 보안 점검표(모든 단계에 걸침)와 최종 감사 | 단계마다 |
| P10 | [p10-android-verification.md](p10-android-verification.md) | Android 실기기 검증, 메모리·스레드 측정, 성능 | P3 이후 반복 (P0-07, P5-04의 Android 확인 포함) |
| P11 | [p11-servlet-adapter.md](p11-servlet-adapter.md) | `tinyhttpd-servlet` 어댑터 (선택) | P8 |
| P12 | [p12-release.md](p12-release.md) | 문서, 배포, 버전 정책 | P8, P9, P10 |

```
P0 ─ P1 ─ P2 ─ P3 ─┬─ P4 ─┬─ P5 ─┐     (P5-04 결정 ← P10-01/02)
                   │      └─ P6 ─┼─ P8 ─┬─ P11
                   └─ P7 ────────┘      └─ P12
P9(보안 점검)는 P1부터, P10(실기기)은 P3부터 단계마다 끼어든다.
```

P4는 P1에서 만든 API 골격을 완성하는 단계다. 골격(인터페이스 선언)은 엔진이 구현할 대상이므로 P1에서 먼저 만든다.

## 3. 접근 방식: 새 엔진을 옆에 만든다 (D-06)

- 새 코드는 `com.snoworca.tinyhttpd.http`(공개 API)와 `com.snoworca.tinyhttpd.server`(엔진, 비공개 구현)에 둔다. 구 패키지 `com.snoworca.tinyhttpd.protocols.http`는 건드리지 않는다.
- 구 코드를 고치지 않는 이유: `HTTPSession`은 연결 상태와 요청 상태를 한 객체에 담고 재사용하는 구조다. 고치는 비용이 새로 쓰는 비용보다 크고, 고친 결과도 결국 삭제된다(core-analysis.md §1).
- **KA/KB 테스트는 P1-02에서 새 엔진을 향하도록 옮긴다.** 테스트 ID와 단언은 유지한다. 새 엔진이 요청을 처리하기 전까지는 KB도 실패하므로 그 기간에만 KB에 `KnownDefect`를 붙인다(P1-02).
- 구 패키지와 구 테스트는 P8 마지막 작업에서 한 번에 삭제한다. 그 전까지 `./gradlew build`는 구 테스트와 새 테스트를 모두 돌린다.

## 4. 작업 표기 규칙

- 작업 ID: `P<단계>-<번호>` (예: `P1-04`). 커밋 메시지와 PR 제목에 ID를 적는다. 요구사항 추적은 작업 ID와 테스트 ID가 맡는다(P0-08, SpecKiwi 미적용).
- GitHub 이슈(2026-10-18 생성): 로드맵 #1, 단계 추적 #2~#14(P0~P12, 밀스톤과 같음), 작업 #15~#114. 이슈 제목은 `[P1-04] …`처럼 작업 ID로 시작한다. 이 문서가 원본이며, 내용이 바뀌면 문서를 먼저 고치고 이슈를 맞춘다. 커밋과 PR에는 `P1-04 (#26)`처럼 둘 다 적는다.
- 작업마다 **산출물**과 **완료 조건**을 둔다. 완료 조건은 테스트 ID(KA-nn, KB-nn, 단계별 새 ID)나 확인 가능한 명령으로 쓴다.
- 단계별 새 테스트 ID: `RQ-nn`(P1), `RS-nn`(P2), `TH-nn`(P3), `API-nn`(P4), `IO-nn`(P5), `SSE-nn`(P6), `TLS-nn`(P7), `MOD-nn`(P8), `SEC-nn`/`SEC-Tnn`(P9: 점검 항목/테스트), `AND-nn`(P10), `SRV-nn`(P11). 테스트 메서드 이름 앞에 소문자로 붙인다(`rq01_...`).
- 상태 표시: `[ ]` 할 일, `[~]` 진행 중, `[x]` 완료. 완료하면 날짜와 커밋을 적는다.
- `KnownDefect` 카테고리는 **테스트 메서드 단위**로 붙인다(P1-02에서 클래스 단위를 메서드 단위로 바꾼다). 그래야 단계마다 해당 테스트에서만 뗄 수 있다.
- 단계를 끝낼 때마다 확인한다:
  1. `./gradlew build`가 통과한다. animal-sniffer API 21 검사도 포함된다.
  2. 그 단계가 맡은 KA 테스트에서 `@Category(KnownDefect.class)`를 제거했다.
  3. 바뀐 결정이나 수치가 있으면 연구 문서와 이 디렉터리의 문서를 고쳤다. §4.1 표에서 그 단계 담당 항목이 `[x]`다.

## 4.1 연구 문서와 CLAUDE.md 수정 목록

계획이 바뀌면서 연구 문서와 맞지 않게 된 곳이다. 담당 작업을 끝낼 때 함께 고치고 상태를 갱신한다. 단계 완료 확인 3번(위)은 이 표로 점검한다.

| ID | 고칠 곳 | 내용 | 담당 | 상태 |
|---|---|---|---|---|
| R-01 | servlet-api.md §4.2 `Request` 스케치 | `getServerName()`/`getServerPort()` 추가 | P1-01 | `[ ]` |
| R-02 | servlet-api.md §4.2 `HttpException` | `withHeader` 추가 | P1-01 | `[ ]` |
| R-03 | keep-alive.md §2.1 | KA-14 소유를 P1으로, `awaitWorkersIdle` 재정의 이유, "수정됨" 열 추가(P1에서 만듦) | P1-02, P1-15 | `[ ]` |
| R-04 | keep-alive.md §2.1 "수정됨" 열 | P2, P3 소유 KA를 채움 | P2, P3 단계 완료 | `[ ]` |
| R-05 | keep-alive.md §4.9 | lingering close 64 KB 상한 → EOF 또는 2 s | P1-15 | `[ ]` |
| R-06 | keep-alive.md §4.7 | header 타임아웃은 워커가 `soTimeout`으로 지킴, watchdog 주기 500 ms | P3-03 | `[ ]` |
| R-07 | servlet-api.md §4.2 빌더 예제 | `.sslServerSocketFactory` → `.tls(...)`, `.maxWorkers(48)`/`.maxEventStreams(8)`과 "SSE는 워커 점유" 주석 → 장수명 예산 설명 | P4-01, P3-01 | `[ ]` |
| R-08 | servlet-api.md §5 websocket 행, §6 리스크 1 | "업그레이드/SSE 연결이 워커를 점유" → 장수명 예산(P3 모델), 대안 P6-07 | P3-01, P6-01 | `[ ]` |
| R-09 | servlet-api.md §4.2 `sendFile` 스케치 | `sendFile(FileChannel…)`, `FileResource` 추가. transferTo 문장은 P5-04 결정에 맞춤 | P5-05 | `[ ]` |
| R-10 | servlet-api.md §4.2 "Range 파싱은 webserver" | core `FileResponses`로 | P5-06 | `[ ]` |
| R-11 | servlet-api.md §4.2 `EventStream`, 예제 | `awaitClosed` 추가, `hub.awaitClose` → `es.awaitClosed` | P6-03 | `[ ]` |
| R-12 | core-analysis.md §2.3 "TLS 별도 연구(예정)" | `tls.md` 링크 | P7-01 | `[ ]` |
| R-13 | CLAUDE.md "테스트 규칙"의 `junit/keepalive/RawConnection` 경로 | testFixtures 경로로 | P0-04 | `[x]` |
| R-14 | CLAUDE.md "저장소 구성"의 "클래스명은 아직 원본 그대로" | 삭제 | P8-05 | `[ ]` |

## 5. 기본값 표

여러 단계에서 쓰는 기본값을 한곳에 모았다. 값은 연구 문서의 제안값이며 **P10 측정 결과로 다시 정한다.** "소속"은 그 값을 바꾸는 곳이다. core 빌더(`TinyHttpd.Builder`)에 속한 값만 API-01이 확인한다.

| 이름 | 기본값 | 소속 | 정의하는 곳 | 근거 |
|---|---|---|---|---|
| `inputBufferBytes` / `outputBufferBytes` | 8192 B / 8192 B | core 빌더 | P1 / P2 | keep-alive.md §4.2, §4.5 |
| `maxRequestLineBytes` | 8190 B (`inputBufferBytes - 2`) | core 빌더 | P1 | §4.2. 빌더 검사 `inputBufferBytes ≥ maxRequestLineBytes + 2` |
| 헤더 줄 하나 상한 | `inputBufferBytes - 2` (옵션 아님) | – | P1 | P1-04 |
| `maxHeaderBytes` / `maxHeaderCount` | 16 KB / 100 | core 빌더 | P1 | §4.2 |
| `maxRequestBodyBytes` | -1 (없음, 핸들러가 정함) | core 빌더 | P1 | P1-08 |
| `maxDrainBytes` | 64 KB | core 빌더 | P1 | §4.4 |
| `maxChunkSizeLineBytes` / `maxTrailerBytes` | 1 KB / 8 KB | core 빌더 | P1 | §4.3 |
| `lingerTimeout` | 2 s (바이트 상한 없음, EOF 또는 시간까지 읽어 버림) | core 빌더 | P1 | §4.9를 고침: 64 KB 상한으로는 KA-14의 256 KB 잔여 데이터에서 RST가 남는다 |
| `maxRequestsPerConnection` | 1000 | core 빌더 | P2 | 이 계획에서 정함(§4.6은 조건만 제시) |
| `serverHeader` | 없음(보내지 않음) | core 빌더 | P2 | SEC-13 |
| `idleTimeout` / `idleTimeoutUnderLoad` | 15 s / 2 s | core 빌더 | P3 | §4.7, §4.8 |
| `headerTimeout` / `bodyReadTimeout` / `writeTimeout` | 10 s / 30 s / 30 s | core 빌더 | P3 | §4.7 |
| `maxWorkers` / `maxConnections` | 64 / 128 | core 빌더 | P3 | §4.8. 장수명 연결(SSE, WebSocket)은 두 값에 포함하지 않는다(P3-01) |
| `maxConnectionsPerAddress` | 32 | core 빌더 | P3 | 이 계획에서 정함 |
| `queueWaitTimeout` | 5 s | core 빌더 | P3 | 이 계획에서 정함 |
| `workerStackBytes` / `workerKeepAlive` / `daemon` | 256 KB / 60 s / `true` | core 빌더 | P3 | §4.8, 이 계획에서 정함 |
| watchdog 주기 | 500 ms (옵션 아님) | – | P3 | 이 계획에서 정함 |
| `shutdownGrace` | 5 s | core 빌더 | P3 | §4.9 |
| `sendFile` 복사 버퍼 풀 | 64 KB × 8, 처음 쓸 때 할당 | core 빌더 | P5 | 이 계획에서 정함 |
| `maxConcurrentGzip` | 8 | `GzipFilter` | P5 | 이 계획에서 정함 |
| multipart 파서 버퍼 / `maxPartHeaderBytes` | 16 KB / 8 KB | `MultipartLimits` | P5 | 구 코드 1 KB에서 확대 |
| `maxEventStreams` | 64 | core 빌더 | P6 | 동시 사용자 50명이 탭 하나씩 연결하는 경우(servlet-api.md §6). 2차 검토에서 8에서 올림 |
| `eventQueueEvents` / `eventQueueBytes` | 64개 / 64 KB | core 빌더 | P6 | 이 계획에서 정함 |
| `handshakeTimeout` / TLS 세션 캐시 | 10 s / P7-01 결과로 정함 | `TlsConfig` | P7 | core-analysis.md §2.3 |
| `maxUpgradedConnections` | 16 | core 빌더 | P8 | servlet-api.md §5 |
| `maxFramePayloadBytes` / `maxMessageBytes` / `wsPingInterval` | 1 MB / 1 MB / 30 s | `WebSocketHandler` | P8 | 이 계획에서 정함. 브라우저는 큰 메시지를 조각내지 않으므로 프레임 상한 = 메시지 상한 |
| `maxSessions` / `maxSessionsPerAddress` | 1000 / 50 | `SessionFilter` | P8 | 이 계획에서 정함 |

## 5.1 메모리 예산

"기본 메모리 최소화"(CLAUDE.md)를 숫자로 관리한다. 아래 목표치는 **초기 제안값**이다. P0-03(JVM 기준선)과 P10(실기기)의 측정 뒤에 확정하고, 확정한 값은 회귀 게이트가 된다.

| 항목 | 목표(제안) | 근거 / 확인 |
|---|---|---|
| 시작 직후 스레드 | 리스너당 acceptor 1개 + watchdog 1개 | P3-01, bench `idle-0` |
| 시작 직후 유지 힙(빈 JVM 대비 증가분) | ≤ 256 KB | 표·버퍼·풀을 처음 쓸 때 할당한다. bench `idle-0` |
| 유휴 keep-alive 연결 1개의 증분 | 힙 ≤ 12 KB(입력 버퍼 8 KB + 객체) + 워커 스레드 1개 | 유휴로 들어가면 출력 버퍼를 연결 공유 풀에 반납한다(P2-05). bench `idle-100` |
| 활성 요청 1개의 증분 | 힙 ≤ 24 KB(+ 출력 버퍼 8 KB, Exchange, 헤더 목록). multipart를 읽는 요청은 +24 KB(파서 버퍼 16 KB + 파트 헤더 8 KB) | bench `get-50`, `upload-1` |
| 장수명 연결 1개의 증분 | 힙 ≤ 입력 8 KB + 출력 8 KB + 대기열 상한 | P6, P8 |
| 최악 힙(모든 상한을 채움) | `maxConnections × 12 KB + maxWorkers × (24 KB + 24 KB multipart) + sendFile 풀 512 KB + maxEventStreams × (16 KB + eventQueueBytes) + maxUpgradedConnections × (16 KB + maxMessageBytes)` | 기본값으로 1,536 + 3,072 + 512 + 5,120 + 16,640 KB = 26,880 KB ≈ **26 MB**. 대부분이 WebSocket 메시지 상한이다. 구형 기기 앱 힙 한도(보통 64~128 MB)와 비교한다 |
| 산식에 아직 없는 힙 항목 | TLS 연결당 레코드 버퍼(P7-01, AND-09 결과로 추가), 세션 저장소(`maxSessions` × 세션 크기, P8-04에서 추정), WebSocket 텍스트 메시지의 `String` 변환(UTF-16, 최대 약 2배. P8-03은 프레임 페이로드를 메시지 버퍼에 바로 읽어 넣어 버퍼가 두 벌 생기지 않게 한다) | 해당 단계에서 산식에 추가 |
| 네이티브 메모리(힙 밖) | `GzipFilter`의 `Deflater`(zlib 기본 설정으로 1개 약 256 KB, Java API로는 창 크기를 줄일 수 없음) → 동시 압축 수 상한(P5-07), Conscrypt TLS 버퍼(P7-01) | AND-06, AND-09에서 PSS로 측정 |

- 회귀 게이트: CI에서 bench의 `idle-0`, `idle-100`을 짧게 돌린다. **새 엔진의 기준값**보다 10% 넘게 늘면 실패시킨다. 구 엔진 값은 비교 참고로만 쓴다(새 엔진은 일부 풀을 새로 둔다).
- 시점: P1~P2의 엔진은 연결당 스레드 모델이고 버퍼 풀도 없으므로 측정값을 **보고만** 한다. **P3 단계 완료 때** 기준값을 기록하고 게이트를 켠다. 위 목표치는 P10-04에서 실기기 측정으로 확정한다.
- 연결 공유 출력 버퍼 풀: 크기는 `maxWorkers + maxEventStreams + maxUpgradedConnections`까지. 비면 새로 만든다.

## 6. 소유자 확인이 필요한 일

- Android 실기기 목록: 측정에 쓸 기기와 OS 버전 (P10)
- 배포 경로: Maven Central, JitPack, 또는 로컬 배포만 (P12)
- `HostAllowlistFilter`(DNS rebinding 방어)를 **기본으로 켤지** (P4-12). 켜지 않으면 WebSocket 동일 출처 검사와 `CsrfFilter`가 DNS rebinding을 막지 못한다. 권장: 기본으로 켠다. IP 리터럴과 `localhost`는 기본 허용이므로 LAN 사용에는 설정이 필요 없다
- 메모리 목표치(§5.1 제안값)를 받아들일지, CI 게이트를 켤 시점(제안: P3 완료 시)
