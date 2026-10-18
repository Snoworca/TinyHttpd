# 소켓 입출력 동작 (P0-07)

## JVM

- 재현 코드: `core/src/test/java/com/snoworca/tinyhttpd/junit/spike/SocketIoSpikeTest.java` (기본 `test`에 포함, 약 2초). 각 테스트는 관찰한 동작을 단언한다.
- 환경(2026-10-18): macOS 26.2, Homebrew OpenJDK 17.0.20.1. **JDK 8은 설치되어 있지 않아 확인하지 못했다**(`/usr/libexec/java_home -V`에 없음). 특히 전이중(4)은 JDK 8 `SocketAdaptor`에서 다를 수 있으니 JDK 8과 Android(P5-04, P10)에서 다시 확인한다.

| 항목 | JDK 17 결과 | JDK 8 | 테스트 |
|---|---|---|---|
| 1. `ServerSocketChannel`로 받은 소켓의 `socket().getInputStream().read()`가 `setSoTimeout` 준수 | 예. 200 ms 뒤 `SocketTimeoutException`, 이후에도 채널 사용 가능 | 미확인 | `channelSocketReadHonorsSoTimeout` |
| 2a. 평문 소켓을 `createSocket(socket, host, port, true)` + `setUseClientMode(false)`로 감싼 서버 핸드셰이크 | 정상. 양방향 데이터 교환 확인 | 미확인 | `wrappedPlainSocketHandshakesAsServer` |
| 2b. 다른 스레드가 원본 평문 소켓을 닫으면 막힌 TLS `read`가 풀림 | 예. `SocketException: Socket closed` | 미확인 | `closingPlainSocketUnblocksTlsRead` |
| 2c. 같은 조건에서 막힌 TLS `write`가 풀림 | 예. `SocketException: Socket closed` | 미확인 | `closingPlainSocketUnblocksTlsWrite` |
| 3a. 다른 스레드의 `close()`가 막힌 평문 `write`를 깨움 | 예. `SocketException: Socket closed` | 미확인 | `closeUnblocksPlainWrite` |
| 3b. 다른 스레드의 `close()`가 막힌 `FileChannel.transferTo`(→ `SocketChannel`)를 깨움 | 예. `ClosedChannelException`(macOS). Linux JDK 17(CI)은 2초 안에 깨지 않음 | 미확인 | `closeUnblocksTransferTo`(깨지 않으면 skip) |
| 4. 전이중: 채널 소켓에서 한 스레드가 `read`에 막혀 있어도 다른 스레드의 adaptor `write`가 진행 | 예 | 미확인 (JDK 8은 `blockingLock` 공유로 막힌다고 알려짐) | `channelSocketIsFullDuplex` |

- "막힘" 판정: 상대가 읽지 않고 수신 버퍼를 4 KB로 줄인 뒤 300 ms 동안 작업이 끝나지 않으면 막힌 것으로 본다. 닫은 뒤 2초 안에 스레드가 끝나야 통과다.

## 결정 (P5-04, 2026-10-18)

- 엔진은 `ServerSocket`을 유지한다. `sendFile`은 서버 공유 풀(64 KB × 8)의 버퍼로 복사한다(`server/FileTransfer.java`). TLS는 어차피 복사가 필요하고, JVM 측정에서 복사 경로로도 다운로드 4개 동시 약 2.9 GB/s가 나왔다(baseline.md).
- `transferTo`(채널 소켓) 전환은 Android 실기기 확인(AND-05: soTimeout, close로 깨우기, sendfile 이득) 뒤 다시 판단한다.
