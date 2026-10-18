# P3. 스레드 모델, 타임아웃, 수명주기

- 목표: 연결과 스레드 수에 상한을 둔다. 타임아웃을 단계별로 나눈다. 시작과 정지를 결정적으로 만든다.
- 선행: P2
- 관련: [keep-alive.md](../research/keep-alive.md) §3.6, §4.7~4.9 · [core-analysis.md](../research/core-analysis.md) §2.5 · 업스트림 #86, #139, #355, #555, PR #658
- 이 단계가 통과시킬 결함 테스트: **KA-12**. KA-14는 P1-15(평문 lingering close)로 옮겼다.

## 모델

keep-alive.md §4.8의 **1단계**(블로킹 워커 풀)를 구현한다. Selector 주차(2단계)는 P10 측정 결과를 보고 결정한다(P3-10).

블로킹 모델에서는 유휴 keep-alive 연결도 워커 하나를 점유한다. 그래서 `maxConnections`(128)가 `maxWorkers`(64)보다 크다는 것은 **워커를 기다리는 대기열이 있다**는 뜻이다. 규칙은 다음과 같다.

```
accept
 ├ 같은 원격 주소의 연결 ≥ maxConnectionsPerAddress → 거절
 ├ 활성 연결 < maxWorkers                           → 바로 워커에 배정
 ├ 활성 + 대기 < maxConnections                     → 대기열에 넣고, 가장 오래 유휴인 연결 하나를 닫아 워커를 비운다
 └ 그 이상                                          → 거절
거절 = 평문 리스너: 503 + Retry-After: 1 + 닫기 / TLS 리스너: 응답 없이 닫기
```

- 유휴 연결을 닫는 것은 정상 동작이다. 클라이언트(OkHttp, 브라우저)는 새 연결로 재시도한다(KB-05).
- 진행 중인 요청이 있는 연결은 대기열 때문에 닫지 않는다. 이를 보장하려고 연결 단계 전이를 CAS로 한다(P3-02).
- TLS 리스너에서 거절할 때 응답을 쓰지 않는 이유: acceptor는 평문 소켓을 가지므로 평문 503은 TLS 클라이언트에게 의미가 없다. 그렇다고 감싸서 핸드셰이크하면 acceptor 스레드가 막혀 그 리스너 전체가 접수를 멈춘다.
- **장수명 연결**(SSE 스트림, 업그레이드된 WebSocket)은 `maxWorkers`와 `maxConnections`에 넣지 않는다. 핸들러가 `startEventStream()`이나 `upgrade()`를 부르는 순간 그 스레드는 워커 풀의 계산에서 빠지고, 장수명 예산(`maxEventStreams`, `maxUpgradedConnections`)으로 옮겨진다. 풀은 필요하면 새 워커를 만든다. 그래서 최대 스레드 수는 `acceptor + watchdog + maxWorkers + maxEventStreams + maxUpgradedConnections`다. 장수명 연결은 축출 대상이 아니다.
  - 이유: 동시 사용자 50명이 알림용 SSE를 하나씩 열어도 일반 요청이 막히지 않아야 한다. 스레드 하나의 실제 상주 메모리는 스택 예약(256 KB)보다 훨씬 작을 것으로 본다. 이 가정은 AND-06/AND-08에서 측정한다. 50개 스트림에서 스레드 비용이 메모리 예산(README §5.1)을 넘으면 P6-07의 대안으로 간다.
- 용어: **바쁜 워커**는 연결 단계가 `IDLE`이 아닌 연결(헤더 읽기, 처리, 쓰기, drain, linger)을 맡은 워커다. 유휴 keep-alive 연결을 기다리는 워커는 바쁘지 않다.

## 작업

### P3-01 워커 풀과 연결 접수 `[ ]`
- `server.WorkerPool`: `ThreadPoolExecutor`를 쓰지 않는다. 직접 구현한다. 위의 "대기열에 넣고 유휴 연결을 닫는다" 정책을 넣기 어렵기 때문이다.
  - 워커는 필요할 때 만들고, `workerKeepAlive`(60 s) 동안 일이 없으면 끝낸다. 시작 직후 스레드 수는 acceptor + watchdog 두 개뿐이다(메모리 최소화).
  - 대기열은 `ArrayDeque<Socket>` + 락 하나. 크기는 `maxConnections - maxWorkers`.
  - 대기열에서 `queueWaitTimeout`(5 s) 넘게 기다린 소켓은 거절 규칙대로 닫는다. 축출할 유휴 연결이 없을 때(워커가 모두 바쁠 때) 무한히 기다리지 않게 하기 위해서다.
- 원격 주소별 연결 수를 센다. `maxConnectionsPerAddress`(기본 32)를 넘으면 거절한다. 클라이언트 하나가 연결을 모두 차지하지 못하게 한다. 브라우저는 호스트당 최대 6개 연결을 쓰므로 정상 사용자는 닿지 않는다.
- 평문 리스너의 503: acceptor 스레드가 미리 만든 바이트(P1-13 방식, `Date` 포함)를 쓰고, `shutdownOutput()` 후 watchdog의 linger 목록으로 넘긴다(P3-07). 새 소켓의 송신 버퍼는 비어 있으므로 이 작은 쓰기가 막히지 않는다.
- 빌더 옵션: `maxWorkers`, `maxConnections`, `maxConnectionsPerAddress`, `queueWaitTimeout`, `workerKeepAlive`.
- 완료 조건: KA-12, TH-01~03, TH-09, TH-16, TH-17 통과.

### P3-02 연결 레지스트리와 단계 전이 `[ ]`
- 살아 있는 모든 `Connection`을 담는 집합. `ConcurrentHashMap.newKeySet()`은 Java 8 API라 쓰지 않는다. `Collections.newSetFromMap(new ConcurrentHashMap<…>())`을 쓴다.
- 연결 단계: `IDLE`, `READING_HEADERS`, `HANDLING`, `WRITING`, `DRAINING`, `LINGERING`, `STREAMING`(SSE), `UPGRADED`(WebSocket), `CLOSING`(TLS는 P7에서 `HANDSHAKING` 추가). 단계별 규칙:

| 단계 | 바쁜 워커로 셈 | 축출 대상 | watchdog 쓰기 마감 | 적응형 idle |
|---|---|---|---|---|
| `IDLE` | 아니오 | 예 | – | 예 |
| `READING_HEADERS` ~ `LINGERING` | 예 | 아니오 | 예 | – |
| `STREAMING`, `UPGRADED` | 아니오(장수명 예산) | 아니오 | 예 | – |

- 연결 단계는 `AtomicInteger`로 두고 **CAS로만 전이**한다. P1-03의 `volatile` 필드를 바꾼다.
  - 워커: 다음 요청의 첫 바이트를 받으면 `IDLE → READING_HEADERS`
  - 축출(acceptor/watchdog): `IDLE → CLOSING`
  - 두 CAS 중 하나만 성공한다. 축출이 지면 그 연결은 건드리지 않는다. 워커가 지면 받은 바이트를 버리고 닫는다. 축출이 먼저 성공했으므로 그 요청은 아직 처리되지 않았고, 클라이언트가 재시도한다.
- 연결마다 `volatile` 필드: 단계 시작 시각, 현재 쓰기 시작 시각(쓰는 중이 아니면 0).
- watchdog, 유휴 연결 축출, 종료가 이 레지스트리를 쓴다.
- 완료 조건: 연결이 끝나면 레지스트리에서 빠진다(TH-14에서 누수 검사). 축출 경합 단위 테스트(두 스레드가 동시에 CAS)가 통과한다.

### P3-03 타임아웃 분리 `[ ]`

| 타임아웃 | 시작 | 구현 | 만료 시 | 기본값 |
|---|---|---|---|---|
| idle | 응답을 끝내고 다음 요청을 기다릴 때 | 워커의 `setSoTimeout(idle)` | 응답 없이 닫기 | 15 s |
| header | 요청의 첫 바이트를 받았을 때 | 워커가 `read`마다 `setSoTimeout(마감까지 남은 시간)`을 다시 건다 | **워커가** 408을 쓰고 닫기 | 10 s |
| body read | 바디를 읽을 때(핸들러, drain) | 워커의 `setSoTimeout(bodyRead)` | 핸들러에 `SocketTimeoutException`, 연결 닫기 | 30 s |
| write | `write`/`flush` 호출 하나가 시작될 때 | watchdog이 소켓을 닫는다 | 핸들러에 `SocketException`, 연결 닫기 | 30 s |

- 단계가 바뀔 때, 그리고 헤더를 읽는 동안에는 `read`마다 `setSoTimeout`을 다시 부른다. 비용은 `setsockopt` 시스템 호출 하나다.
- 헤더 타임아웃은 "바이트 사이 간격"이 아니라 **헤더 전체의 마감 시각**이다. 1초에 1바이트씩 보내는 slowloris를 막는다. 남은 시간이 0 이하가 되면 워커가 408을 쓴다. 408은 작은 응답이고 이 연결은 아직 응답을 쓰지 않은 상태라 송신 버퍼가 비어 있다. 그래서 이 쓰기는 사실상 막히지 않는다. 막히더라도 쓰기 타임아웃(watchdog)이 닫는다.
- 핸들러 실행 시간 자체에는 타임아웃을 두지 않는다. SSE와 대용량 전송이 정상적으로 오래 걸리기 때문이다. 대신 읽기와 쓰기가 멈춘 상태를 위 타임아웃으로 잡는다.
- 쓰기 타임아웃만 watchdog이 필요하다. 블로킹 쓰기를 깨울 API가 없기 때문이다(keep-alive.md §4.7). 다른 스레드에서 소켓을 닫으면 막힌 쓰기가 예외로 풀린다. **Android에서도 그런지 P10에서 확인한다**(AND-03).
- 완료 조건: TH-04~07, TH-15 통과. KB-04와 KB-05는 설정한 idle 값으로 다시 맞춘다(테스트는 500 ms를 설정해 빨리 끝낸다). KB-05는 idle보다 오래 기다려야 "서버 유휴 종료 뒤 복구"라는 의도가 유지된다.

### P3-04 watchdog `[ ]`
- 데몬 스레드 하나(`tinyhttpd-watchdog`). 500 ms마다(옵션 아님) 레지스트리를 훑는다.
- 하는 일:
  1. 쓰기 마감을 넘긴 연결을 닫는다.
  2. 헤더 마감을 크게 넘긴 연결을 닫는다(워커의 408 경로가 실패했을 때의 보조 수단).
  3. 대기열이 있으면 유휴 연결을 축출한다(P3-01, P3-02의 CAS).
  4. 대기열에서 `queueWaitTimeout`을 넘긴 소켓을 거절한다(TH-09).
  5. linger 목록의 소켓에서 `available()`만큼 읽어 버리고, `lingerTimeout`이 지난 소켓을 닫는다(P3-07).
  6. 적응형 유휴 타임아웃 상태를 갱신한다(P3-05).
- **watchdog은 절대 막히지 않아야 한다.** watchdog이 멈추면 모든 쓰기 타임아웃이 사라진다.
  - 소켓을 닫을 때 `SSLSocket.close()`를 부르지 않는다. 이 호출은 `close_notify`를 보내려고 막힌 쓰기의 락을 기다릴 수 있다.
  - 그래서 TLS 연결은 평문 소켓 위에 `SSLSocketFactory.createSocket(socket, …)`으로 감싸 만들고(P7-02), watchdog은 **아래의 평문 소켓**을 닫는다.
  - 이 방식이 막힌 TLS 쓰기를 깨우는지는 P0-07(JVM)과 AND-03(Android)에서 확인한다.
- 연결 수가 128개 이하이므로 매번 전부 훑어도 비용이 작다. 우선순위 큐를 쓰지 않는다.
- 완료 조건: TH-06 통과. 연결이 없을 때 watchdog의 CPU 사용량이 무시할 만하다(10초 동안 10 ms 미만).

### P3-05 적응형 유휴 타임아웃 `[ ]`
- 바쁜 워커(위 용어 정의) 수가 `maxWorkers`의 75%를 넘으면, 그때부터 유휴 상태로 들어가는 연결의 idle 타임아웃을 `idleTimeoutUnderLoad`(2 s)로 줄인다. 60% 아래로 내려가면 원래 값으로 돌린다(진동 방지를 위한 이력 구간).
- 이미 유휴 중인 연결은 다음 watchdog 주기에 새 마감 시각을 적용한다(watchdog이 CAS 후 닫는다).
- 완료 조건: TH-08 통과.

### P3-06 스레드 생성 `[ ]`
- 기본 스레드 팩토리: 이름 `tinyhttpd-worker-<n>`, `new Thread(group, r, name, workerStackBytes)`(기본 256 KB), 데몬 여부는 빌더 옵션 `daemon`(기본 `true`).
- `UncaughtExceptionHandler`: SEVERE 로그. 워커가 죽어도 풀은 계속 동작한다(다음 접수 때 새 워커를 만든다).
- 빌더 옵션 `threadFactory(ThreadFactory)`(P1-01에서 이미 만듦)로 교체할 수 있다.
- Android가 스택 크기를 실제로 반영하는지는 P10에서 측정한다(AND-04).
- 완료 조건: TH-13 통과.

### P3-07 워커 밖의 닫기 경로 `[ ]`
- 평문 lingering close는 P1-15에서 구현했다. 이 작업은 워커가 없는 경로를 다룬다.
- acceptor의 503 거절(P3-01): acceptor가 503을 쓰고 `shutdownOutput()`한 뒤 소켓을 watchdog의 "linger 목록"에 넘긴다. watchdog은 주기마다 `available()`만큼만 읽어 버리고(막히지 않음), `lingerTimeout`이 지나면 닫는다. acceptor와 watchdog 모두 막히지 않는다.
- linger 중인 워커 연결(`LINGERING`)은 바쁜 워커로 센다(최대 2 s). 측정에서 문제가 되면 이 연결도 watchdog의 linger 목록으로 넘긴다.
- 완료 조건: TH-02에서 클라이언트가 요청을 보낸 뒤 503을 읽을 수 있다(Linux CI).

### P3-08 시작과 정지 `[ ]`
- `start()`: 호출한 스레드에서 `bind`까지 끝낸 뒤 acceptor를 시작한다. bind 실패(포트 사용 중 등)는 `start()`가 `IOException`으로 던진다. 구 코드의 `Thread.sleep(10)` 폴링을 없앤다(PR #658).
- 상태: `NEW → STARTED → STOPPING → STOPPED`. `volatile` 필드 하나로 관리한다. 다시 시작할 수 없다(새 인스턴스를 만든다).
- `stop(long graceMillis)`:
  1. ServerSocket을 닫아 accept를 멈춘다. 대기열의 소켓은 닫는다.
  2. `stopping = true`. 이후 응답은 모두 `Connection: close`가 된다(P2-06).
  3. 유휴 연결은 즉시 닫는다(CAS). SSE 스트림은 `close()`한다(P6-06). WebSocket에는 1001(Going Away) Close 프레임을 보낸다(P8-03).
  4. 진행 중인 요청은 `graceMillis`(기본 5 s)까지 기다린다.
  5. 남은 연결의 소켓을 닫고, 워커 스레드를 `interrupt()`한다.
  6. `stop`은 5단계 직후 반환한다. Java에는 스레드를 강제로 끝내는 API가 없으므로, 소켓 입출력도 interrupt도 하지 않는 핸들러가 붙잡은 워커는 남을 수 있다. 남은 워커 수는 WARNING 로그와 `ServerStats`로 알린다.
- `stop()`은 `stop(shutdownGrace)`와 같다. `awaitTermination(timeout)`을 둔다(JVM `main`에서 서버를 띄울 때 쓴다).
- 완료 조건: TH-10~12, TH-14 통과. 구 `ShutdownTest`에 해당하는 시나리오를 새 엔진으로 옮긴다.

### P3-09 서버 통계 `[ ]`
- P1-01의 최소 `ServerStats`를 넓힌다: 열린 연결, 대기 연결, 바쁜 워커, 전체 워커, 누적 요청 수, 거절 수(사유별), 타임아웃 종류별 횟수, 축출된 유휴 연결 수, 정지 후 남은 워커 수.
- 카운터는 `AtomicInteger`/`AtomicLong`만 쓴다(`LongAdder`는 API 21에 없다).
- P10 측정과 NAS 앱의 상태 화면이 쓴다.
- 완료 조건: 각 TH 테스트가 통계 값도 함께 확인한다. TH-18은 P6 이후에 활성화한다(그 전에는 `@Ignore`와 이유).

### P3-10 2단계(Selector 주차) 판단 `[ ]`
- P10의 메모리·스레드 측정 결과로 결정한다. 다음 중 하나라도 만족하면 착수한다(제안 기준).
  - AND-06에서 유휴 연결 128개일 때, 워커 스레드 때문에 늘어난 메모리(PSS 증가분)가 50개 연결 부하 시나리오 전체 증가분의 절반을 넘는다.
  - 브라우저 50개 탭이 30초마다 페이지를 다시 읽는 시나리오에서 유휴 연결 축출이 초당 1회를 넘거나, 축출 때문에 생긴 재연결로 페이지 로드 p95가 200 ms 이상 늘어난다.
- 착수하면 평문만 대상으로 한다. TLS는 `SSLEngine`이 필요하므로 별도 판단한다(keep-alive.md §4.8).
- 산출물: 판단과 측정 근거를 `docs/research/thread-model.md`에 기록한다.
- 완료 조건: 문서에 판단(착수/보류)과 기준별 측정값이 있다.

## 새 테스트 목록 (TH)

테스트에서는 타임아웃을 수백 ms로 줄여 설정한다.

| ID | 시나리오 | 기대 |
|---|---|---|
| TH-01 | 유휴 keep-alive 연결 100개 (`maxWorkers` 64) | 워커 ≤ 64 (KA-12) |
| TH-02 | `maxConnections`+1번째 연결 (평문) | 503 + `Retry-After`. TLS 리스너의 거절은 TLS-14(P7-07) |
| TH-03 | 워커가 모두 유휴 연결에 묶인 상태에서 새 요청 | 유휴 연결 하나가 닫히고 새 요청이 1 s 안에 응답 |
| TH-04 | 헤더를 100 ms마다 1바이트씩 (header 500 ms) | 약 500 ms 뒤 408 + 닫기, 워커 반환 |
| TH-05 | CL 10, 5바이트 후 정지 (bodyRead 500 ms) | 핸들러가 `SocketTimeoutException`, 연결 닫힘 |
| TH-06 | 클라이언트가 10 MB 응답을 읽지 않음, 수신 버퍼 4 KB (write 500 ms) | 1.5 s 안에 연결 닫힘, 워커 반환 |
| TH-07 | idle 500 ms | 약 500 ms 뒤 서버가 닫음 (KB-04 갱신) |
| TH-08 | 바쁜 워커 80%인 상태에서 유휴 연결 | idle이 `idleTimeoutUnderLoad`로 줄어듦, 60% 아래로 내려가면 복귀 |
| TH-09 | 워커가 모두 바쁜(처리 중) 상태에서 새 연결 (`queueWaitTimeout` 500 ms) | 약 500 ms 뒤 503 |
| TH-10 | 처리 중 요청 1개 + 유휴 연결 3개에서 `stop(2000)` | 처리 중 요청은 `Connection: close`로 완료, 유휴 연결은 즉시 닫힘 |
| TH-11 | 소켓 `read`에서 막힌 핸들러 / `Thread.sleep` 중인 핸들러에서 `stop(500)` | 둘 다 `stop`이 약 500 ms 뒤 반환하고 워커가 끝남(소켓 닫기 / interrupt) |
| TH-12 | 이미 쓰는 포트로 `start()` | `start()`가 `BindException` |
| TH-13 | 기본 스레드 팩토리 | 이름 규칙, 스택 크기 인자 전달 |
| TH-14 | `start`/`stop` 100회 반복 | 스레드와 레지스트리 누수 없음 |
| TH-15 | OkHttp `retryOnConnectionFailure(false)`, 서버 idle 종료 직전·직후에 요청 100회 | 실패 횟수를 기록한다(정상 동작의 한계 측정). `retryOnConnectionFailure(true)`에서는 실패 0회 |
| TH-16 | 같은 주소에서 연결 33개 (`maxConnectionsPerAddress` 32) | 33번째 거절 |
| TH-17 | `HttpURLConnection`(JDK)으로 KB-01, KB-05 시나리오 | 연결 재사용, 유휴 종료 후 복구 |
| TH-18 | SSE 스트림 `maxWorkers`개를 연 상태에서 일반 GET | 정상 응답(장수명 연결이 워커 예산을 쓰지 않음) |

accept 오류 백오프는 P1-03의 단위 테스트(가짜 `ServerSocket`)로 다룬다. Android `HttpURLConnection`(OkHttp 기반)은 P10의 AND-01에서 확인한다.

## 완료 조건 (단계)
- KA-12에서 `KnownDefect`를 제거했다. **`knownDefectTest`가 비었다** → keep-alive 연구 범위 종료(keep-alive.md §5).
- TH 테스트가 전부 통과한다.
- 새 엔진의 메모리 기준값(bench `idle-0`, `idle-100`)을 `docs/research/baseline.md`에 기록하고 CI 메모리 회귀 게이트를 켰다(README §5.1).
- keep-alive.md §2.1의 "수정됨" 열에 KA-12를 채웠다(R-04).
