# P10. Android 실기기 검증과 측정

- 목표: JVM에서 세운 가정을 실기기에서 확인한다. 기본값 표(README §5)를 측정값으로 다시 정한다.
- 선행: P3 이후 단계마다 반복한다. 최종 측정은 P8 뒤에 한다.
- 관련: [keep-alive.md](../research/keep-alive.md) §4.8, §5, §6 · [servlet-api.md](../research/servlet-api.md) §2 "조사하지 못한 것" · P0-03 기준선

## 작업

### P10-01 측정용 Android 앱 `[ ]`
- 위치: `tools/android-harness/`. **루트 빌드와 분리된 독립 Gradle 빌드**로 둔다. 라이브러리 빌드가 Android Gradle Plugin과 SDK를 요구하지 않게 하기 위해서다. 라이브러리는 composite build(`includeBuild("../..")`)로 가져온다.
- `minSdk 21`, 현재 `targetSdk`. 서버를 foreground service로 돌린다. `WakeLock`과 `WifiLock`을 잡는다.
- 화면: `ServerStats`(P3-09), 힙/PSS/네이티브 힙, 스레드 수. 같은 값을 `/__stats` JSON으로도 제공한다.
- 실행 방식은 두 가지다.
  - **원격 부하**: 데스크톱의 `tools/bench`(P0-03)가 기기 IP로 요청을 보낸다. 시나리오가 같으므로 JVM 기준선과 비교할 수 있다(AND-01, 06~10).
  - **기기 내 실행**: 하네스의 instrumented test(`androidTest`)가 기기 안에서 돌린다. 대상은 소켓 동작 probe(AND-03~05)와, core 테스트 중 기기에서 의미 있는 일부(RQ/RS/TH 스위트를 루프백으로)다. API 21 에뮬레이터와 실기기에서 실행한다(AND-13).
- 비교 대상: P0-03 기준선과 같은 코드여야 비교가 성립한다. 그래서 업스트림 2.3.1 아티팩트(2016년, 포크 기준점 `efb2ebf`와 다름)를 쓰지 않는다. P8-05 전에 P0-01 커밋에서 구 core를 빌드한 jar(`tools/android-harness/libs/legacy-core.jar`)를 만들어 별도 모드로 넣는다. 만드는 스크립트도 함께 둔다.
- 완료 조건: 앱이 API 21 에뮬레이터와 실기기 한 대에서 동작한다.

### P10-02 기기 목록 `[ ]` (소유자 확인 필요)
- 최소 구성(제안): API 21 또는 22(5.x) 실기기 1대 이상(32비트면 더 좋다), API 23~28 1대, API 29 이상 1대. 실기기가 없는 버전은 에뮬레이터로 보완하고 결과에 표시한다.
- 기기마다 기록: 모델, Android 버전, ABI, RAM, 앱 힙 한도(`ActivityManager.getMemoryClass()`).
- 완료 조건: 기기 목록이 `android-measurements.md`에 있다. 이 결정은 P5-04를 막으므로 P3 착수 전에 정하기를 권한다.

### P10-03 확인 항목 `[ ]`

| ID | 확인 | 영향 받는 결정 |
|---|---|---|
| AND-01 | 서버 시작, 기본 GET, keep-alive (KB 시나리오 원격 실행) | 전체 |
| AND-02 | KA-14: 읽지 않은 입력이 남은 상태에서 닫을 때 RST로 응답이 유실되는가, lingering close가 막는가 | P1-15, P3-07 |
| AND-03 | 다른 스레드의 `Socket.close()`가 막힌 `read`/`write`를 깨우는가. TLS: 평문 소켓 위에 감싼 `SSLSocket`에서 **아래 평문 소켓**을 닫으면 막힌 TLS `write`가 막힘 없이 풀리는가 | P3-03 쓰기 타임아웃, P3-04 watchdog |
| AND-04 | `new Thread(…, stackSize)`의 스택 크기가 반영되는가(`/proc/self/maps`, `/proc/self/status`) | P3-06 기본 256 KB |
| AND-05 | 채널 기반 소켓의 `setSoTimeout` 동작, `transferTo`의 zero-copy 여부와 속도 | P5-04 |
| AND-06 | 유휴 연결 50/100/128개의 메모리(PSS, 자바 힙, 네이티브 힙)와 스레드 수 | P3-10 2단계 판단, `maxWorkers` |
| AND-07 | 1 GB 업로드, 1 GB 다운로드 4개 동시의 처리량과 메모리(Wi-Fi) | P5 버퍼 크기 |
| AND-08 | SSE 50개 + 업로드 동시: 스레드 상주 메모리, 방송 지연 | `maxEventStreams`, P6-07 대안 판단 |
| AND-09 | TLS: 프로토콜·cipher 협상 결과, 연결당 메모리, 핸드셰이크 시간 | P7 기본값 |
| AND-10 | 24시간 연속 부하(낮은 강도). 통과 기준(제안): 처음 1시간을 뺀 PSS 증가 기울기 ≤ 100 KB/h, 스레드 수와 열린 fd 수가 늘지 않음, 서버가 멈추지 않음 | 메모리 누수, Doze 중 동작 |
| AND-11 | 측정 앱을 R8 minify로 빌드해 동작 | P12-03 소비자 규칙 |
| AND-12 | `coreLibraryDesugaring`을 끈 측정 앱을 `minSdk 21`로 D8 빌드하고, API 21 기기에서 AND-13 스위트가 통과한다. Lint `NewApi`는 앱 소스만 보고 의존성 jar의 바이트코드는 검사하지 않으므로 이것으로 대신한다 | animal-sniffer가 놓친 API |
| AND-13 | instrumented test로 core 스위트 일부를 API 21 에뮬레이터와 실기기에서 실행 | 전체 |

- 완료 조건(P10-03): 각 AND 항목의 결과와 판정이 `android-measurements.md`에 있다.

### P10-04 결과 기록과 기본값 재결정 `[ ]`
- 산출물: `docs/research/android-measurements.md`(기기별 표, 원자료 위치, 측정 방법).
- 결과에 따라 고친다: README §5 기본값 표와 §5.1 메모리 예산(목표치 확정 → CI 회귀 게이트 값), P6-07 판단, P3-10(Selector 주차) 판단, P5-05 복사 버퍼 풀 크기, keep-alive.md §6 미해결 질문의 답.
- 완료 조건: AND-01~13 결과가 기록되었고, 바뀐 기본값이 코드와 문서에 반영되었다.

## 완료 조건 (단계)
- `android-measurements.md`가 있다. 최소 기기 구성에서 AND-01~13을 확인했다.
