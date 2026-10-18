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
