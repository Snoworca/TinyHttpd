# Keep-alive 연구

- 작성일: 2026-10-17
- 대상 코드: 포크 기준점 `efb2ebf` (패키지명만 `com.snoworca.tinyhttpd`로 바꾼 상태)
- 관련 자료: [업스트림 결함 목록](upstream-issues.md)
- 재현 테스트: `core/src/test/java/com/snoworca/tinyhttpd/junit/keepalive/`
  - `KeepAliveDefectTest` (KA-nn): 올바른 동작을 단언하는 테스트. 현재 코드에서는 실패한다. `@Category(KnownDefect.class)`로 표시해 기본 `test`에서 제외했다.
  - `KeepAliveBaselineTest` (KB-nn): 현재도 올바른 동작. 재작성 중 회귀를 막는다. 기본 `test`에 포함된다.
  - 실행: `./gradlew :tinyhttpd-core:test`, `./gradlew :tinyhttpd-core:knownDefectTest`
- 측정 환경: macOS (Darwin 25.2), OpenJDK 17.0.20.1, 루프백, OkHttp 4.12.0, 서버 소켓 read 타임아웃 5000ms(기본값)

> 이 문서는 회사에서 했던 작업을 참고하지 않고 원본 코드와 공개 자료만으로 독립적으로 작성했다.

## 1. 요약

- 현재 keep-alive는 **정상 경로(바디를 다 읽는 GET/POST, 파이프라이닝, `Connection: close`)에서만** 동작한다(KB-01~06 통과).
- 조금만 벗어나면 연결이 깨진다. 결함 테스트 16개 중 **15개가 실패**했다.
- 근본 원인은 세 가지다.
  1. **요청 경계를 서버가 관리하지 않는다.** 바디의 끝을 핸들러에게 떠넘긴다(KA-01, 04, 10, 13).
  2. **응답 프레이밍이 메서드와 상태를 고려하지 않는다**(KA-02, 03, 05).
  3. **연결 루프가 오류와 EOF를 구분하지 못한다.** 무한 루프와 잘못된 `Connection` 헤더가 생긴다(KA-06, 07, 08).
- 재작성 방향: **연결(Connection)과 요청(Exchange)을 분리**한다. 서버가 요청 바디의 프레이밍을 소유하고, 응답 프레이밍과 연결 유지 여부를 한 곳에서 결정한다. 상세 설계는 §4.

## 2. 측정 결과

### 2.1 결함 테스트 (KnownDefect)

| ID | 시나리오 | 관측 결과 | 판정 | 업스트림 |
|---|---|---|---|---|
| KA-01 | 핸들러가 읽지 않은 POST 바디(CL 10) 다음에 GET | 두 번째 요청이 **400**. 바디가 다음 요청 라인 앞에 붙음 | 재현 | #314, #217, #356 |
| KA-02 | HEAD `/hello` 다음에 GET | 다음 상태 줄이 `helloHTTP/1.1 200 OK ` → **HEAD에 바디 전송** | 재현 | #608의 유력한 원인 |
| KA-03 | HEAD로 chunked 리소스 요청 | **`Content-Length: -1`** 헤더 | 재현 | |
| KA-04 | `Transfer-Encoding: chunked` 요청 바디 | `parseBody()` 결과가 **11바이트**(`5\r\nhello\r\n0`). 청크 프레이밍이 그대로 바디로 들어옴 | 재현 | #211, #617 |
| KA-05 | 응답 스트림이 선언한 Content-Length(10)보다 짧음(5) | 연결이 **열린 채 유지**. 클라이언트는 나머지 5바이트를 기다림 | 재현 | #498 |
| KA-06 | 16KB 헤더 | 클라이언트가 끊은 뒤 2초간 워커 스레드가 **CPU 1993ms 사용**(무한 루프). 431 응답 여부는 앞선 단언이 실패해 미검증 | 재현 | #621, #362 |
| KA-07 | 헤더 일부만 보내고 연결 종료 | 2초간 워커가 **CPU 1983ms 사용**(무한 루프) | 재현 | #529 |
| KA-08 | 요청 라인 오류(400) | 응답은 **`Connection: keep-alive`** 인데 서버는 연결을 닫음 | 재현 | |
| KA-09 | HTTP/1.0 + `Connection: keep-alive` | `Connection: close`로 응답하고 닫음 | 기능 부재 | #46 |
| KA-10 | 핸들러가 `getInputStream()`을 EOF까지 읽음(CL 5) | **5초(서버 read 타임아웃) 블록** 후 `SocketTimeoutException` | 재현 | |
| KA-11 | `Content-Length: abc` | 서버가 거부하지 않음. `parseBody()`에서 `NumberFormatException`이 핸들러로 새어 나옴 | 재현 | |
| KA-12 | 유휴 keep-alive 연결 100개 | 워커 스레드 **100개** | 재현 | #86, #139, #460 |
| KA-13 | 길이 정보 없는 GET에 `parseBody()` + 파이프라이닝 | 다음 요청을 **바디로 먹어 버려** 두 번째 응답이 오지 않음 | 재현 | |
| KA-14 | `Connection: close` 요청 뒤에 256KB 잔여 데이터 | 응답을 정상 수신 | **macOS 루프백에서는 미재현** | |
| KA-15 | `Expect: 100-continue` | 100 응답 없음. 클라이언트와 서버가 서로 기다림 | 재현 | |
| KA-16 | `?secret=1` 요청 다음에 쿼리 없는 요청 | 두 번째 요청에서도 `secret=1`이 보임 | 재현 | #311, #596, PR #657 |

KA-14는 Linux 커널(Android 포함)에서 동작이 다를 수 있다. 수신 버퍼에 읽지 않은 데이터가 남은 채 소켓을 닫으면 RST가 나가고, 클라이언트가 아직 읽지 않은 응답이 버려질 수 있다. **Android 실기기 또는 Linux에서 다시 측정해야 한다.** 그때까지 이 테스트는 가드로 남겨 둔다.

### 2.2 기준선 테스트 (현재도 통과)

| ID | 시나리오 | 결과 |
|---|---|---|
| KB-01 | OkHttp로 GET 20회 | 연결 1개 재사용 |
| KB-02 | GET 두 개를 한 번에 전송(파이프라이닝) | 순서대로 두 응답 |
| KB-03 | 클라이언트 `Connection: close` | `Connection: close` 응답 후 서버가 닫음 |
| KB-04 | 유휴 연결 | **5003ms** 후 서버가 닫음(read 타임아웃 = 유휴 타임아웃) |
| KB-05 | OkHttp POST → 6초 유휴 → POST | 성공. 새 연결로 재시도됨(accept 2회) |
| KB-06 | `parseBody()`로 바디를 읽는 POST 20회 | 연결 1개 재사용 |

KB-05는 서버가 FIN으로 정상 종료한 경우다. 클라이언트가 막 요청을 보내는 순간 서버가 닫는 경합은 이 테스트로 재현되지 않는다. 경합을 줄이려면 §4.6의 유휴 타임아웃 정책이 필요하다.

## 3. 원인 분석 (코드 위치)

경로는 `core/src/main/java/com/snoworca/tinyhttpd/protocols/http/` 기준이다.

### 3.1 연결 루프와 세션 재사용
- `ClientHandler.run()`은 `while (!acceptSocket.isClosed()) session.execute();`로 **같은 `HTTPSession` 객체를 재사용**한다. 요청별 필드(`queryParameterString`, `parms`, `splitbyte`, `rlen` 등)가 초기화되지 않으면 다음 요청으로 넘어간다(KA-16).
- 연결을 끝내는 신호가 `SocketException("NanoHttpd Shutdown")`이라는 **예외 메시지 문자열 비교**다(`ClientHandler.java:85`). EOF, 타임아웃, 오류, 정상 종료가 모두 예외 경로를 탄다.

### 3.2 헤더 읽기 (`HTTPSession.execute()`)
- 8192바이트 버퍼에 헤더를 읽는다. 버퍼가 차도 헤더 끝을 못 찾으면 `read(buf, 8192, 0)`이 0을 돌려주고 `while (read > 0)` 루프를 빠져나간다. 그 상태로 **잘린 헤더를 파싱하고, 나머지 헤더 바이트는 다음 "요청"이 된다.** 이 과정이 반복되면서 무한 루프가 된다(KA-06).
- 헤더 도중에 EOF(`read == -1`)가 오면 역시 루프를 빠져나가 부분 헤더를 파싱한다. `decodeHeader()`는 `readLine()`이 null이면 그냥 return하고, `Method.lookup(null)`에서 400이 난다. 응답을 쓰고 outputStream을 닫아도 `acceptSocket.isClosed()`가 false인 경로가 남아 루프가 계속 돈다(KA-07).

### 3.3 요청 바디
- 서버는 바디 경계를 추적하지 않는다. 코드에 TODO가 그대로 남아 있다(`HTTPSession.java:415-420`: "long body_size = getBodySize(); ... this.inputStream.skip(...)").
- `getInputStream()`은 **연결 전체의 BufferedInputStream**을 그대로 노출한다. 핸들러가 EOF까지 읽으면 다음 요청이나 타임아웃까지 블록된다(KA-10).
- `getBodySize()`는 Content-Length가 없으면 `rlen - splitbyte`, 즉 **이미 버퍼에 들어온 바이트 수**를 바디 크기로 쓴다. 파이프라이닝된 다음 요청이 바디가 된다(KA-13). chunked 요청도 같은 경로를 타서 원시 프레이밍이 바디가 된다(KA-04).
- `Long.parseLong(content-length)`의 예외를 처리하지 않는다(KA-11).
- `Expect: 100-continue`를 처리하지 않는다(KA-15).

### 3.4 응답 프레이밍 (`response/Response.java`)
- `sendBodyWithCorrectTransferAndEncoding()`은 HEAD일 때 chunked 분기만 건너뛰고 **else 분기에서 바디를 그대로 보낸다**(KA-02). chunked 리소스의 HEAD는 `contentLength = -1`이 `Content-Length: -1`로 출력되고, `pending == -1`이 "전부 전송"으로 해석된다(KA-03).
- 1xx/204/304 응답에도 바디와 Content-Length를 보낸다(업스트림 #577).
- `sendBody()`는 데이터가 일찍 끝나면 `break`만 하고, 선언한 길이를 채우지 못했다는 사실을 호출자에게 알리지 않는다. 연결은 keep-alive로 유지된다(KA-05).
- `send()`는 `IOException`을 로그만 남기고 삼킨다. 쓰기에 실패해도 세션은 다음 요청을 읽으러 간다.
- 상태 줄 끝에 공백이 하나 더 붙는다(`"HTTP/1.1 " + "200 OK" + " \r\n"`).
- 헤더는 `PrintWriter`로 flush하고 바디는 소켓 스트림에 따로 쓴다. 작은 응답 하나가 TCP 쓰기 두 번 이상이 된다. `setTcpNoDelay`도 설정하지 않는다. Nagle과 delayed ACK가 겹치면 지연이 생기는 전형적인 write-write-read 패턴이다(이번 루프백 측정에서는 지연이 관측되지 않음).

### 3.5 연결 유지 판단
- `keepAlive = "HTTP/1.1".equals(protocolVersion) && !connection.matches("(?i).*close.*")`(`HTTPSession.java:411`). HTTP/1.0 keep-alive를 지원하지 않고, `Connection` 헤더를 토큰 단위가 아니라 부분 문자열로 검사한다.
- 오류 응답(`ResponseException`, `IOException`)도 `Response` 기본값 `keepAlive = true`로 나가서 `Connection: keep-alive`라고 쓴 뒤 닫는다(KA-08).

### 3.6 스레드와 타임아웃
- `DefaultAsyncRunner.exec()`는 연결마다 `new Thread()`이고 상한이 없다(KA-12). 스레드는 연결이 끝날 때까지 살아 있으므로, 유휴 keep-alive 연결도 스레드 하나를 점유한다.
- 타임아웃은 `setSoTimeout(5000)` 하나뿐이다. 이 값이 **유휴 타임아웃, 헤더 읽기 타임아웃, 바디 읽기 타임아웃을 겸한다.** 쓰기 타임아웃은 없다. Java의 블로킹 소켓에는 쓰기 타임아웃 API가 없어서, 응답을 받지 않는 클라이언트가 있으면 쓰기가 무기한 블록된다(업스트림 #555).
- `stop()`은 ServerSocket을 닫고 `closeAll()`로 소켓을 강제로 닫는다. 진행 중인 응답을 마무리하는 graceful shutdown이 없다.

## 4. 재설계 제안

목표는 **"서버가 HTTP/1.1 메시지 프레이밍(RFC 9112)을 전부 소유하고, 핸들러는 경계가 정해진 스트림만 본다"** 는 것이다.

### 4.1 Connection과 Exchange 분리
- `Connection`: 소켓, 입력 버퍼, 출력 버퍼, 연결 상태(요청 수, 마지막 활동 시각, 현재 단계)를 소유한다. 연결 하나에 하나.
- `Exchange`(요청 하나): 요청 라인, 헤더, 바디 스트림, 응답 상태를 가진다. **요청마다 새로 만든다.** 이것만으로 요청 간 상태 누수(KA-16, 업스트림 #126/#150)가 구조적으로 사라진다.
- 연결 루프는 예외가 아니라 명시적인 상태로 끝낸다.
  ```
  while (connection.readNextRequest(exchange))   // EOF·타임아웃·파싱 오류면 false
      handle(exchange); finish(exchange);         // finish가 keep-alive 여부를 결정
  connection.close(graceful)
  ```

### 4.2 요청 파싱
- 자체 입력 버퍼(예: 8KB 고정 `byte[]`)로 줄 단위로 증분 파싱한다. `mark/reset`에 의존하지 않는다. 남은 바이트는 그대로 다음 요청에 쓰인다(파이프라이닝 유지, KB-02).
- 한도와 응답:
  - 요청 라인 > 8KB → 414
  - 헤더 전체 > 16KB 또는 헤더 개수 > 100 → 431
  - 모두 응답 후 연결을 닫는다(KA-06).
- 헤더 도중 EOF → 응답 없이 조용히 닫는다(KA-07). 첫 바이트 전 EOF는 정상 종료로 처리한다.
- 같은 이름의 헤더는 여러 값을 보존한다(업스트림 #630).

### 4.3 요청 바디 프레이밍 (RFC 9112 §6.3)

| 조건 | 처리 |
|---|---|
| `Transfer-Encoding` 마지막 코딩이 `chunked` | `ChunkedInputStream`으로 디코딩. 청크 크기와 trailer 크기에 한도를 둔다 |
| `Transfer-Encoding`이 있지만 chunked가 아님 | 400 + 닫기 |
| `Transfer-Encoding`과 `Content-Length`가 함께 있음 | 400 + 닫기 (요청 스머글링 방지) |
| `Content-Length`가 숫자가 아니거나, 서로 다른 값이 여러 개 | 400 + 닫기 (KA-11) |
| `Content-Length: n` | `BoundedInputStream(n)`. n바이트에서 EOF (KA-10) |
| 둘 다 없음 | 빈 바디 (KA-13) |

- 핸들러는 이 바디 스트림만 받는다. 연결 스트림은 노출하지 않는다.
- `Expect: 100-continue`: 핸들러가 **처음으로 바디를 읽을 때** `100 Continue`를 보낸다. 핸들러가 바디를 읽지 않고 응답하면 100을 보내지 않고, 응답 후 연결을 닫는다(KA-15).

### 4.4 핸들러 이후 바디 처리 (drain)
- 응답을 보낸 뒤 바디에 남은 바이트를 **최대 N바이트(기본 64KB)까지 읽어 버린다**(KA-01).
- N을 넘거나 drain 중 타임아웃이 나면 keep-alive를 포기하고 닫는다. 원치 않는 대용량 업로드를 끝까지 받지 않기 위해서다(Tomcat의 `maxSwallowSize`와 같은 발상).

### 4.5 응답 프레이밍

| 조건 | 헤더 | 바디 |
|---|---|---|
| HEAD | 길이를 알면 GET과 같은 `Content-Length`, 모르면 생략 | **절대 쓰지 않음** (KA-02, 03) |
| 1xx, 204, 304 | `Content-Length`와 `Transfer-Encoding` 금지 | 쓰지 않음 (#577) |
| 길이를 앎 | `Content-Length: n` | 정확히 n바이트. 소스가 짧으면 **연결을 끊는다**(KA-05) |
| 길이를 모름, HTTP/1.1 | `Transfer-Encoding: chunked` | chunked |
| 길이를 모름, HTTP/1.0 | `Connection: close` | 닫아서 끝을 표시 |

- 출력은 연결당 `BufferedOutputStream`(8KB) 하나로 모은다. 헤더와 작은 바디가 한 번의 쓰기로 나가고, 응답 끝에서 한 번 flush한다. `setTcpNoDelay(true)`를 설정한다.
- 쓰기 실패는 삼키지 않는다. 실패하면 즉시 연결을 닫는다.
- 스트리밍(SSE)용으로 명시적 `flush()`를 제공한다. gzip은 `text/event-stream`에 기본 적용하지 않는다. 현재 기본 정책은 `text/*`를 gzip하므로 SSE 이벤트가 GZIPOutputStream 버퍼에 갇힌다.

### 4.6 연결 유지 결정 (한 곳에서)

```
persistent = 요청이 유지를 원함             // 1.1: close 토큰 없음, 1.0: keep-alive 토큰 있음
          && 응답이 닫기를 요구하지 않음
          && 응답 프레이밍이 정상 완료됨     // KA-05
          && 요청 바디가 끝까지 소비됨       // drain 성공, KA-01
          && 요청 수 < maxRequestsPerConnection
          && 서버가 종료 중이 아님
```

- `Connection` 헤더는 쉼표로 나눈 토큰을 대소문자 무시로 비교한다.
- 닫을 때는 반드시 `Connection: close`를 보낸다(KA-08). HTTP/1.0 keep-alive일 때는 `Connection: keep-alive`를 보낸다(KA-09). `Keep-Alive: timeout=N`도 함께 보낸다(클라이언트에 따라 무시될 수 있다).

### 4.7 타임아웃 분리

| 타임아웃 | 의미 | 기본값(제안) | 구현 |
|---|---|---|---|
| idle | 요청 사이의 대기 | 15초. 부하가 높으면 짧게 줄임(§4.8) | `soTimeout` |
| header | 첫 바이트부터 헤더 끝까지 | 10초 (slowloris 대응) | 감시 스레드 |
| body read | 바디 읽기 중 무활동 | 30초 | `soTimeout` |
| write | 쓰기 한 번이 막혀 있는 시간 | 30초 (SSE 포함) | 감시 스레드가 소켓을 닫음 |

- **감시 스레드(watchdog) 하나**가 1초마다 연결 목록을 훑어, 기한을 넘긴 연결의 소켓을 닫는다. 블로킹 쓰기에는 타임아웃 API가 없으므로 이것이 유일하게 확실한 방법이다.

### 4.8 스레드 모델 (KA-12)

1단계(권장): **상한이 있는 워커 풀 + 연결 수 제한 + 적응형 유휴 타임아웃**
- 워커 최대 64개, 동시 연결 최대 128개(설정 가능).
- 워커 사용률이 75%를 넘으면 유휴 연결부터 닫고, 새 유휴 타임아웃을 1~2초로 줄인다.
- 워커 스레드는 `new Thread(group, r, name, stackSize)`로 **작은 스택(예: 256KB)** 을 지정한다. 32비트 구형 기기에서는 스레드당 예약되는 가상 주소 공간이 더 아깝다. Android가 이 값을 실제로 반영하는지는 실기기에서 확인해야 한다.
- 동시 사용자 50명이고 브라우저가 호스트당 최대 6개 연결을 연다는 점을 감안해도, 실제로 동시에 활성인 연결은 훨씬 적다고 본다. **측정으로 확인해야 하는 가정이다.**

2단계(필요할 때만): **유휴 연결을 Selector에 주차**
- 평문 HTTP에서는 유휴 keep-alive 소켓을 `SocketChannel` + `Selector`(API 21에서 사용 가능)에 등록한다. 읽을 데이터가 생기면 워커에 넘긴다. 스레드는 활성 요청 수만큼만 필요하다(Tomcat NIO, Jetty와 같은 구조).
- TLS는 `SSLSocket`을 Selector에 등록할 수 없어서 `SSLEngine`으로 다시 구현해야 한다. 복잡도가 크게 늘어나므로 1단계 측정 결과를 보고 결정한다.

### 4.9 종료
- **정상 종료(lingering close)**: `shutdownOutput()` → 입력을 최대 2초 또는 64KB까지 읽어 버림 → `close()`. RST로 응답이 유실되는 것을 막는다(KA-14). `SSLSocket`은 `shutdownOutput()`을 지원하지 않으므로 TLS는 별도 경로가 필요하다.
- **서버 정지(graceful shutdown)**: accept를 멈추고 → 유휴 연결은 즉시 닫고 → 진행 중인 요청은 `Connection: close`로 마무리하게 기한까지 기다리고 → 남은 연결은 강제로 닫는다(업스트림 #57, #355).

## 5. 테스트 전략

- 결함 테스트는 수정될 때마다 `@Category(KnownDefect.class)`를 제거해 기본 스위트로 옮긴다. `knownDefectTest`가 비면 이 연구의 범위는 끝난다.
- 앞으로 추가할 시나리오:
  - **OkHttp**: 연결 풀 재사용, 서버 유휴 종료 직후의 GET/POST, `retryOnConnectionFailure(false)`일 때의 동작, 대용량 업로드/다운로드 중 취소
  - **HttpURLConnection**: Android 기본 클라이언트. Android 4.4부터 내부 구현이 OkHttp 기반이다.
  - **장애 주입**: slowloris(헤더를 1초에 1바이트), 업로드/다운로드 중 클라이언트 abort, half-close, 응답을 읽지 않는 클라이언트(쓰기 타임아웃)
  - **TLS**: 위 시나리오를 `SSLSocket`으로 반복 (TLS 연구에서 다룸)
- KA-14와 스레드 스택 크기는 **Android 실기기에서** 다시 측정한다.

## 6. 미해결 질문

1. 1단계 스레드 모델로 충분한가? 구형 기기에서 연결 128개 기준 메모리를 측정해야 한다.
2. 기본 유휴 타임아웃: 15초면 브라우저와 OkHttp(풀 유지 5분) 모두에서 재연결 비용과 스레드 점유 사이의 균형이 맞는가?
3. HTTP 파이프라이닝은 지금처럼 순차 처리만 지원하면 되는가? (브라우저는 사용하지 않는다.)

## 6. 새 엔진 결과 (2026-10-18)

- KA-01~16, KB-01~06이 새 엔진(`com.snoworca.tinyhttpd.server`)에서 기본 `test`로 통과한다. `KnownDefect`가 붙은 KA 테스트는 없다.
- `awaitWorkersIdle`은 "`busyWorkers == 0` 그리고 `openConnections == 0`"으로 바꿨다. 풀 워커는 `workerKeepAlive` 동안 살아 있으므로 스레드 종료로는 셀 수 없다. 단언의 의도(연결이 정리되고 워커가 돌지 않음)는 같다.
- lingering close(§4.9)는 64 KB 상한 대신 "EOF 또는 `lingerTimeout`(2 s)까지 읽어 버림"으로 구현했다(KA-14 256 KB 잔여).
- header 타임아웃은 워커가 `read`마다 남은 시간으로 `soTimeout`을 다시 걸어 지킨다. watchdog 주기는 500 ms이고 쓰기 타임아웃만 맡는다(§4.7).
