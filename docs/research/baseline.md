# 기준선 측정 (구 엔진, P0-03)

- 도구: `tools/bench` (`:bench`). 서버와 클라이언트(평문 `java.net.Socket`)가 한 JVM에서 돈다. 코드: `tools/bench/src/main/java/com/snoworca/tinyhttpd/bench/Bench.java`
- 명령: `./gradlew -q :bench:run --args='<scenario> [size]'` (size 기본 64m, 예: `--args='download-4 1g'`). get-50 시간은 `-Dbench.seconds`(기본 3초).
- 환경(2026-10-18): Apple M4, 32 GB, macOS 26.2, Homebrew OpenJDK 17.0.20.1, G1 기본값, `-Xmx256m`(`applicationDefaultJvmArgs`).
- heapKB: 연결을 연 채로 `System.gc()` 뒤 `totalMemory - freeMemory`. base는 엔진 시작 전 값(1023 KB, 스레드 6개).
- 각 시나리오 1회 실행. 처리량·지연은 실행마다 크게 흔들린다(get-50은 44k~78k req/s). 메모리·스레드 수가 비교 대상이다.

| 시나리오 | heapKB | threads | 처리량 | p50 ms | p99 ms |
|---|---|---|---|---|---|
| idle-0 | 1221 | 7 | - | - | - |
| idle-50 | 3185 | 57 | - | - | - |
| idle-100 | 4344 | 107 | - | - | - |
| idle-128 | 5002 | 135 | - | - | - |
| get-50 (3 s) | 3194 | 57 | 44286 req/s | 0.24 | 5.34 |
| upload-1 (64 MB) | 2030 | 8 | 2043 MB/s | 29.97 | 29.97 |
| download-4 (64 MB) | 2107 | 11 | 2293 MB/s | 109.23 | 109.54 |
| upload-1 (1 GB) | 2038 | 8 | 8082 MB/s | 125.34 | 125.34 |
| download-4 (1 GB) | 2116 | 11 | 4791 MB/s | 852.82 | 852.83 |

- 구 엔진은 연결마다 스레드 1개(idle-N의 threads ≈ 7 + N)와 힙 약 30 KB를 쓴다. 스레드 스택(네이티브 메모리)은 heapKB에 들어가지 않는다.
- 연결별 할당 바이트(JFR)는 아직 측정하지 않았다.

## 새 엔진 (P3 완료 시점, 2026-10-18)

같은 환경·명령. 기본은 새 엔진이고 `-Dbench.engine=old`로 구 엔진을 고른다. bench용 엔진은 `maxConnectionsPerAddress`와 `maxRequestsPerConnection`만 늘렸다(클라이언트가 모두 127.0.0.1이고 재연결하지 않는다).

| 시나리오 | heapKB | threads | 처리량 | p50 ms | p99 ms |
|---|---|---|---|---|---|
| idle-0 | 1204 | 8 | - | - | - |
| idle-50 | 2713 | 58 | - | - | - |
| idle-100 | 2949 | 72 | - | - | - |
| idle-128 | 2984 | 72 | - | - | - |
| get-50 (3 s) | 3137 | 58 | 171835 req/s | 0.08 | 1.25 |
| upload-1 (64 MB) | 2020 | 9 | 1815 MB/s | 34.17 | 34.17 |
| download-4 (64 MB) | 2080 | 11 | 2947 MB/s | 85.07 | 85.08 |

- 스레드 = acceptor + watchdog + 워커(최대 `maxWorkers` 64). idle-100/128에서 64를 넘는 연결은 대기열에 들어가고 가장 오래된 유휴 연결이 축출된다.
- 메모리 회귀 게이트(CI): 힙 증가분(heapKB - baseHeapKB)이 idle-0 200 KB, idle-100 2120 KB(이 기준값 + 10%)를 넘으면 실패한다. `-Dbench.maxHeapDeltaKB`로 건다.
