# 타임아웃·크기 설정의 "0 이하 = 무제한" 조사

- 날짜: 2026-10-19, 커밋 `46b973e` 기준
- 소유자 요구: 타임아웃은 모두 0 이하이면 무제한으로 둔다. 크기를 정하는 설정도 마찬가지다.
- 범위: core `TinyHttpd.Builder`·런타임 setter·`TlsConfig`, `http` API의 크기 인자, webserver·websocket·session 모듈의 설정.
- 이 문서는 조사 결과다. 구현은 아래 "결정이 필요한 항목"이 정해진 뒤에 한다.

## 1. 현재 규칙 요약

- 빌더 `build()`가 `positive`(0 이하 거부) 또는 `nonNegative`(음수 거부)로 검사한다. 런타임 setter는 `set(name, value, min)`으로 같은 하한을 적용한다(`TinyHttpd.java`).
- 0과 음수의 뜻이 설정마다 다르다.
  - 거부: 대부분의 타임아웃, 버퍼, 개수.
  - "끔/즉시": `lingerTimeout`, `shutdownGrace`, `maxDrainBytes`, `maxEventStreams`, `maxUpgradedConnections`.
  - 무제한: `maxRequestBodyBytes`와 `StaticFileHandler.maxFileBytes`는 -1이 무제한이다. TLS `sessionCacheSize`와 `sessionTimeout`은 0이 무제한이다.
- 소켓 타임아웃은 `setSoTimeout(0)`이면 무한 대기다. 음수는 `IllegalArgumentException`을 던진다. 그래서 무제한은 소켓에 0을 넘기는 것으로 끝나지 않는다. 마감 시각(`now + value`)이나 watchdog으로 구현한 곳은 0을 넣으면 **즉시 만료**된다.

## 2. 타임아웃 전수 조사

| 설정 | 기본 | 현재 검사 | 현재 0의 의미 | 쓰이는 곳 | 0 이하 = 무제한에 필요한 처리 |
|---|---|---|---|---|---|
| `headerTimeout` | 10 s | positive | 거부 | `Connection` 헤더 마감(`deadline = now + v`), watchdog 강제 종료(`v + 2000`) | 마감·watchdog 모두 건너뛰기. 끄면 slowloris 방어가 사라진다(SEC-07) |
| `bodyReadTimeout` | 30 s | positive | 거부 | `Connection.beforeRead` 소켓 타임아웃, `BodyInput.drain` 마감 | 소켓 0 + drain 마감 생략. 지금 0을 허용하면 drain이 바로 실패해 keep-alive가 깨지고, `minRequestBodyRate`를 켠 경우 타임아웃이 `Math.min(0, …)`로 1 ms가 된다 |
| `writeTimeout` | 30 s | positive | 거부 | watchdog(`now - writeSince > v`) | watchdog 검사 생략. 클라이언트가 읽지 않으면 워커가 무기한 묶인다 |
| `idleTimeout` | 15 s | positive | 거부 | keep-alive 대기 소켓 타임아웃, `Keep-Alive: timeout=` 헤더, `Upgraded` 초기 읽기 타임아웃 | 소켓 0. 헤더의 `timeout=`을 빼야 한다(지금은 `max(1, v/1000)`) |
| `idleTimeoutUnderLoad` | 2 s | positive | 거부 | 부하 시 keep-alive 대기, watchdog 유휴 종료, 헤더 | watchdog 검사 생략. 끄면 과부하 때 유휴 연결을 정리할 수 없다(퇴출은 따로 남는다) |
| `queueWaitTimeout` | 5 s | positive | 거부 | `WorkerPool` 대기열 만료(`now - since > v`) | 만료 생략. 연결은 `maxConnections`까지만 대기열에 쌓인다 |
| `lingerTimeout` | 2 s | nonNegative | **링거 생략(즉시 닫기)** | `lingerClose` 마감, 거절 소켓 lingering 목록, `closeIfGraceOver` | **의미가 뒤집힌다.** 무제한이면 lingering 목록과 링거 중인 워커가 상대가 닫을 때까지 남는다 |
| `workerKeepAlive` | 60 s | positive | 거부 | 유휴 워커 대기 마감 | 마감 생략. 워커가 `maxWorkers`까지 계속 상주한다(메모리) |
| `shutdownGrace` / `stop(long)` | 5 s | nonNegative | **즉시 종료** | `stop()` 유예 | **의미가 뒤집힌다.** 테스트 22곳이 `stop(0)`을 "바로 멈춤"으로 쓴다 |
| `TlsConfig.handshakeTimeout` | 10 s | positive(즉시 throw) | 거부 | `Tls` 핸드셰이크 마감, watchdog(`v + 2000`) | 마감·watchdog 생략. 끄면 핸드셰이크에서 멈춘 클라이언트가 워커를 잡는다 |
| `TlsConfig.sessionTimeout` | 3600 s | 없음 | JSSE: 무제한 | `SSLSessionContext` | 이미 0 = 무제한. 음수는 JSSE가 거부하므로 0으로 바꿔 넘겨야 한다 |
| `minRequestBodyRate` | 0 | nonNegative | 끔 | `Connection.bodyRead` | 하한이라 "무제한"이 곧 "끔"이다. 이미 0이 끔이다. 음수도 끔으로 받으면 된다 |
| `WebSocketHandler.pingInterval` | 30 s | 없음 | 소켓 0: ping 없음, 무한 대기 | `WebSocket` 읽기 타임아웃 | 사실상 이미 무제한이다. 음수는 `setSoTimeout`이 던지므로 0으로 바꿔야 한다 |
| `UpgradedConnection.setReadTimeout` | idleTimeout | 없음 | 소켓 0 | `Upgraded` | 위와 같다(음수 정규화) |
| `EventStream.awaitClosed(heartbeat)` | 호출자 | 없음 | **ping을 쉬지 않고 계속 보낸다(회전)** | `SseStream` | 현재 결함. 0 이하이면 heartbeat 없이 닫힐 때까지 기다리게 해야 한다 |
| `SessionFilter.maxInactive` | 30 min | 없음 | **모든 세션이 즉시 만료** | `now - lastAccess > v` | 0 이하이면 만료 검사를 생략한다 |
| `SessionFilter.cleanupInterval` | 60 s | 없음 | 요청마다 청소 | `now - lastCleanup > v` | 상한이 아니라 주기다. "무제한"을 "청소 안 함"으로 읽으면 만료 세션이 쌓인다. 결정 필요 |

## 3. 크기·개수 전수 조사

| 설정 | 기본 | 현재 검사 | 현재 0의 의미 | 0 이하 = 무제한에 대한 판단 |
|---|---|---|---|---|
| `maxRequestBodyBytes` | -1(없음) | 없음 | **바디 금지(413)** | 0의 의미가 바뀐다. -1은 이미 무제한이다 |
| `maxDrainBytes` | 64 KB | nonNegative | **drain 안 함(항상 닫기)** | 의미가 바뀐다. 무제한 drain은 `bodyReadTimeout`에만 묶이고, 둘 다 무제한이면 워커가 무기한 잡힌다 |
| `maxRequestLineBytes` | 8190 | positive, `≤ inputBufferBytes - 2` | 거부 | **무제한 불가.** 줄이 입력 버퍼 안에 있어야 한다. 0 이하를 "버퍼 최대(`inputBufferBytes - 2`)"로 읽는 것이 한계다 |
| `maxChunkSizeLineBytes` | 1 KB | positive, `≤ inputBufferBytes - 2` | 거부 | 위와 같다 |
| 헤더 줄 하나 | `inputBufferBytes - 2` | – | – | 설정이 아니다. 버퍼 크기가 곧 상한이다 |
| `maxHeaderBytes` / `maxHeaderCount` | 16 KB / 100 | positive | 거부 | 무제한은 가능하지만 헤더 전체를 메모리에 둔다. 최우선 목표인 메모리 최소화와 부딪친다 |
| `maxTrailerBytes` | 8 KB | positive | 거부 | 위와 같다(메모리) |
| `maxRequestsPerConnection` | 1000 | positive | 거부 | 문제없다 |
| `maxWorkers` | 64 | positive | 거부 | **CLAUDE.md와 충돌**: "스레드를 무한히 만드는 `DefaultAsyncRunner`를 크기 제한 풀(32~64)로 교체". 부하 판정(`busy*4 > maxWorkers*3`)과 출력 버퍼 풀 크기(`maxWorkers + maxEventStreams + maxUpgraded`)도 유한한 값이 필요하다 |
| `maxConnections` | 128 | positive, `≥ maxWorkers` | 거부 | lingering 목록 상한, 대기열 상한으로도 쓴다. 무제한이면 연결 폭주 때 메모리·디스크립터가 무한히 늘어난다 |
| `maxConnectionsPerAddress` | 32 | positive | 거부 | 문제없다(끄기) |
| `maxEventStreams` | 64 | nonNegative | **SSE 끄기(모두 503)** | 의미가 바뀐다. 장수명 스레드 예산과 출력 버퍼 풀 크기에 더해진다 |
| `maxUpgradedConnections` | 16 | nonNegative | **WebSocket 끄기(모두 503)** | 위와 같다 |
| `eventQueueEvents` / `eventQueueBytes` | 64 / 64 KB | positive | 거부 | 느린 구독자의 대기열이 메모리에 무한히 쌓인다 |
| `inputBufferBytes` / `outputBufferBytes` | 8 KB / 8 KB | positive | 거부 | **해당 없음.** 할당 크기라 "무제한"이 성립하지 않는다 |
| `sendFileBufferBytes` / `sendFileBuffers` | 64 KB / 8 | positive / nonNegative | 버퍼 수 0 = 풀 없음 | 할당 크기라 해당 없다. 버퍼 수 0은 "풀 없음"으로 이미 쓰고 있다 |
| `workerStackBytes` | 256 KB | nonNegative | 플랫폼 기본값 | 해당 없다(0은 "지정 안 함"이다) |
| `TlsConfig.sessionCacheSize` | 128 | 없음 | JSSE: 무제한 | 이미 맞다. 음수만 정규화하면 된다 |
| `StaticFileHandler.maxFileBytes` | -1 | 없음 | **빈 파일만 허용(403)** | 0의 의미가 바뀐다(`WebServerTest`가 0 → 403을 단언한다) |
| `WebSocketHandler.maxFramePayloadBytes` / `maxMessageBytes` | 1 MB / 1 MB | 없음 | 페이로드가 있는 프레임은 1009로 닫힘 | `int`이고 메시지를 `ByteArrayOutputStream`에 모으므로 실제 한계는 약 2 GB와 힙이다 |
| `Request.readForm(int maxBytes)` | 호출자 | 없음 | 비지 않은 form은 413 | 바디를 `byte[]`에 모으므로 한계는 약 2 GB와 힙이다 |
| `MultipartLimits` 총량·파트 수·파트 크기·파트 헤더 | 호출자 | 생성자가 0 이하에서 throw | 거부 | 총량·파트 크기는 디스크 스트리밍이라 무제한이 가능하다. 파트 헤더는 메모리다. `bufferBytes`는 할당 크기라 해당 없다 |
| `SessionFilter.maxSessions` / `maxSessionsPerAddress` | 1000 / 50 | 없음 | 요청마다 퇴출하거나 503 | 0 이하이면 검사를 생략한다 |

## 4. 요구사항과 충돌하는 부분

1. **CLAUDE.md "개선 방향"과 주 용도.**
   - "크기가 제한된 스레드 풀(32~64개)"을 요구한다. `maxWorkers` 무제한은 이 요구를 정면으로 거스른다.
   - 최우선 목표인 "기본 메모리 사용량 최소화"는 *기본값*에 관한 것이다. 무제한을 앱이 명시적으로 고를 때만 허용하면 기본값은 그대로 유지할 수 있다.
2. **P9 보안 점검표.**
   - SEC-02와 P9-02는 "모든 입력에 상한이 있다. 빈 칸이 있으면 감사 실패"라고 적는다. 0 이하 = 무제한은 이 문장과 충돌한다.
   - 감사 기준을 "**기본값**에 상한이 있다. 0 이하는 앱이 명시한 해제"로 고쳐야 한다.
   - SEC-07(slowloris, 느린 읽기·쓰기)은 `headerTimeout`, `bodyReadTimeout`, `writeTimeout`에 의존한다. 이 셋을 해제하면 워커 64개를 느린 연결이 모두 잡을 수 있다.
3. **0의 기존 의미가 뒤집히는 설정** (깨는 변경, 0.x에서는 허용).
   - 대상: `lingerTimeout`, `shutdownGrace`/`stop(0)`, `maxDrainBytes`, `maxEventStreams`, `maxUpgradedConnections`, `maxRequestBodyBytes`(0 = 바디 금지), `StaticFileHandler.maxFileBytes`(0 = 빈 파일만).
   - 기능을 "끄는" 방법이 사라지므로 대체 수단이 필요하다. 예를 들어 SSE를 끄려면 라우트를 두지 않는다.
4. **원리적으로 무제한이 안 되는 설정.**
   - 할당 크기: `inputBufferBytes`, `outputBufferBytes`, `sendFileBufferBytes`, `MultipartLimits.bufferBytes`, `workerStackBytes`.
   - 버퍼 크기에 묶인 줄 길이: `maxRequestLineBytes`, `maxChunkSizeLineBytes`, 헤더 줄.
   - 줄 길이를 정말 무제한으로 하려면 입력 버퍼를 늘려야 하고, 그러면 메모리 목표와 부딪친다.
5. **"무제한"이 실제로는 `int`/배열 한계(약 2 GB)와 힙 크기인 설정.**
   - 대상: WebSocket 프레임·메시지, `readForm`, 헤더·trailer·SSE 대기열.
   - OOM이 나면 연결 하나가 아니라 앱 전체가 죽는다.
6. **프로토콜 값이라 0에 정해진 뜻이 있는 설정.** 규칙에서 제외해야 한다.
   - `Cookie.setMaxAge`: 0은 삭제, -1은 세션 쿠키.
   - `CorsFilter.maxAge`: `Access-Control-Max-Age: 0`은 캐시 안 함.
   - `Keep-Alive: timeout=`, `Retry-After`.
7. **마감 시각과 watchdog으로 구현한 타임아웃.**
   - 대상: header, linger, handshake, drain, queue, workerKeepAlive, write, `closeIfGraceOver`.
   - 지금은 0이 즉시 만료다. 규칙을 넣으려면 곳곳에 "≤0이면 건너뜀" 분기가 필요하다. 음수는 소켓에 넘기기 전에 0으로 바꿔야 한다(`setSoTimeout`이 던짐).
8. **기존 테스트와 문서.**
   - 기대를 바꿔야 하는 테스트:
     - `ThreadingTest.runtimeSettersValidateAndApply`: 타임아웃 7개의 0·-1 거부를 단언한다.
     - `PublicApiTest`: `maxWorkers(0)` 거부를 단언한다.
     - `GracefulCloseTest.lingeringRejectDrainsBeforeDeadlineClose`: `lingerTimeout(0)`을 "링거 없음"으로 쓴다.
     - `WebServerTest`: `maxFileBytes(0)` → 403을 단언한다.
     - 테스트 22곳의 `stop(0)`.
   - 고칠 문서: 각 설정의 javadoc, `docs/next/README.md` 기본값 표, `p03`, `p09` 표.
9. **함께 드러난 기존 결함.** `EventStream.awaitClosed(0)`은 ping을 쉬지 않고 보내며 회전한다.

## 5. 결정이 필요한 항목

| # | 질문 | 제안 |
|---|---|---|
| D1 | `maxWorkers`와 `maxConnections`도 무제한을 허용하는가 | 허용하지 않는다(CLAUDE.md의 크기 제한 풀). 0 이하는 거부를 유지한다 |
| D2 | 할당 크기와 버퍼에 묶인 줄 길이 | 규칙에서 제외한다. 줄 길이는 0 이하를 "버퍼 최대"로 읽는다 |
| D3 | 0의 의미가 뒤집히는 7개 설정 | 규칙을 따른다(깨는 변경). 기능을 끄는 수단은 문서로 안내한다 |
| D4 | `stop(long)` 인자 | 설정이 아니라 호출 인자다. `stop(0)`은 "즉시"로 둔다. `shutdownGrace` 설정만 0 이하 = 무기한 대기로 한다 |
| D5 | 프로토콜 값(`Cookie`, `CorsFilter.maxAge`) | 제외한다 |
| D6 | `SessionFilter.cleanupInterval` | 주기라 제외한다. 0 이하는 요청마다 청소로 둔다 |
| D7 | P9 감사 기준 | "기본값에 상한이 있다"로 고친다. 무제한은 javadoc에 위험을 적는다 |
