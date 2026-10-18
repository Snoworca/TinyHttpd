# P2. 응답 직렬화와 연결 유지 결정

- 목표: 응답 프레이밍(RFC 9112 §6~7)을 서버가 한곳에서 결정한다. 연결 유지 여부도 한 함수에서 정한다.
- 선행: P1
- 관련: [keep-alive.md](../research/keep-alive.md) §3.4~3.5, §4.5~4.6 · [servlet-api.md](../research/servlet-api.md) §4.2 `Response` · [core-analysis.md](../research/core-analysis.md) §2.2 · RFC 9110 §6.4, §15, RFC 9112 §6.3, §9
- 이 단계가 통과시킬 결함 테스트: **KA-02, KA-03, KA-05, KA-09**. KA-08은 P1이 소유한다. 이 단계는 그 일반화(서버가 닫기로 한 모든 응답이 `Connection: close`를 알림)를 RS-23으로 확인한다.

## 작업

### P2-01 `Response` 구현 `[ ]`
- `server.ExchangeResponse implements Response`. P1-03의 임시 응답 경로를 교체한다.
- 상태: 기본 200. `setStatus`에 1xx를 주면 `IllegalArgumentException`이다. 100은 서버가 자동으로 보내고(P1-11), 101은 `upgrade()`만 쓴다(P8-03).
- 커밋: 헤더를 처음 소켓에 쓰는 순간이다. 원인은 출력 버퍼가 넘침, `flushBuffer()`, 핸들러 반환 중 하나다. 커밋 뒤의 `setStatus`/`setHeader`는 **Servlet과 같이 무시한다**(이름이 같으면 의미도 같다). 무시할 때 FINE 로그를 남긴다.
- `getOutputStream()`과 `getWriter()`는 하나만 쓸 수 있다. 섞으면 `IllegalStateException`이다(Servlet과 같음).
- `getWriter()` 인코딩: Content-Type의 charset을 쓰고, 없으면 UTF-8로 쓴다. 이때 Content-Type에 `; charset=UTF-8`을 붙인다. **Servlet 기본값(ISO-8859-1)과 다르므로** javadoc에 적는다.
- `reset()`/`resetBuffer()`: 커밋 전에만 쓸 수 있다. 커밋 후에는 `IllegalStateException`이다.
- 완료 조건: RS-17~19 통과.

### P2-02 헤더 검증과 서버 관리 헤더 `[ ]`
- 이름은 `tchar`만 허용한다. 값에 `CR`, `LF`, `NUL`이 있으면 `IllegalArgumentException`이다(응답 분할 방지, P9 SEC-03).
- 서버가 관리하는 헤더는 핸들러가 직접 쓰지 않는다.

| 헤더 | 핸들러가 `setHeader`로 지정하면 |
|---|---|
| `Content-Length` | `setContentLengthLong`으로 바꿔 처리한다. 숫자가 아니면 `IllegalArgumentException` |
| `Transfer-Encoding` | `IllegalArgumentException`. 프레이밍은 서버가 정한다 |
| `Connection` | 값이 `close`면 `setCloseConnection(true)`로 처리한다. 그 밖의 값은 무시하고 FINE 로그를 남긴다 |
| `Keep-Alive`, `Date` | 서버가 덮어쓴다 |

- 같은 이름을 여러 번 `addHeader`하면 줄을 여러 개 쓴다(업스트림 #629). `Set-Cookie`를 별도로 처리하지 않는다. `addCookie`는 `addHeader("Set-Cookie", …)`로 구현한다(P4-06).
- 완료 조건: RS-10, RS-11 통과.

### P2-03 응답 프레이밍 결정 `[ ]`
커밋할 때 한 함수에서 정한다. 위에 있는 행이 먼저 적용된다.

| 조건 | 헤더 | 바디 | 테스트 |
|---|---|---|---|
| 상태 1xx, 204, 304 | `Content-Length`와 `Transfer-Encoding`을 쓰지 않는다 | 쓰지 않는다. 핸들러가 쓴 바이트는 버린다 | RS-02, RS-03 |
| 상태 205 | `Content-Length: 0` | 쓰지 않는다 | RS-22 |
| HEAD, 길이를 앎(지정했거나 핸들러 반환 시 버퍼 안에 다 들어감) | GET과 같은 `Content-Length` | 쓰지 않는다 | KA-02, RS-07 |
| HEAD, 길이를 모름 | `Content-Length`, `Transfer-Encoding` 없음 | 쓰지 않는다 | KA-03, RS-08 |
| 길이를 지정함(`setContentLengthLong`) | `Content-Length: n` | 정확히 n바이트(P2-05) | KA-05, RS-09 |
| 길이를 지정하지 않았고, 핸들러가 반환할 때까지 출력이 버퍼 안에 다 들어감 | `Content-Length: 버퍼 크기` | 버퍼 | RS-04 |
| 길이를 모름, 요청이 HTTP/1.1 | `Transfer-Encoding: chunked` | chunked | RS-05 |
| 길이를 모름, 요청이 HTTP/1.0 | `Connection: close` | 닫아서 끝을 표시 | RS-06 |

- HEAD 요청에서도 핸들러는 GET처럼 바디를 쓴다. 서버는 그 바이트 수만 세고 버린다. 핸들러가 HEAD를 미리 알고 바디 생성을 건너뛰고 싶으면 `getMethod()`를 확인한다.
- 상태 줄: `HTTP/1.1 <code> <reason>\r\n`. 끝에 공백을 붙이지 않는다(구 코드 결함). 이유 문구를 모르는 코드는 빈 문구로 쓴다(`HTTP/1.1 299 \r\n`; 상태 코드 뒤 공백은 문법상 필수다).
- HTTP/1.0 요청에도 응답 버전은 `HTTP/1.1`로 쓴다(RFC 9110 §2.5). 대신 chunked를 쓰지 않는다.
- 완료 조건: 표의 테스트와 RS-01, RS-21 통과.

### P2-04 `Date` 헤더 `[ ]`
- 모든 응답에 `Date`를 붙인다(RFC 9110 §6.6.1). 캐시(1초마다 한 번 문자열을 만들고 `volatile` 참조로 공유)는 P1-13에서 먼저 만들었다. 이 작업은 일반 응답 경로에 연결한다. 응답마다 `SimpleDateFormat`을 만들지 않는다(core-analysis.md §2.2).
- `Server` 헤더는 기본으로 보내지 않는다. 빌더 옵션 `serverHeader(String)`로 켠다.
- 완료 조건: RS-12 통과.

### P2-05 출력 스트림과 버퍼 `[ ]`
- 출력 버퍼(`outputBufferBytes`, 기본 8 KB)는 응답을 쓰는 동안만 연결이 가진다. 연결이 유휴로 들어가면 서버 공유 풀에 반납하고, 다음 응답에서 다시 빌린다(README §5.1 메모리 예산). 헤더와 작은 바디를 한 번에 쓰고, 응답이 끝날 때 한 번 flush한다.
- 소켓에 `setTcpNoDelay(true)`를 설정한다. 버퍼링과 함께 써서 write-write-read 지연을 막는다(keep-alive.md §3.4).
- chunked 출력: 버퍼가 찰 때마다 청크 하나를 쓴다. 핸들러의 작은 `write` 호출이 작은 청크가 되지 않게 한다. `flush()`를 부르면 그때까지의 데이터로 청크를 하나 만든다(SSE가 쓴다).
- 길이 검사(바디를 보내는 응답만 대상이다. HEAD, 1xx/204/304, 205는 **검사하지 않는다**. 이 응답들은 `Content-Length`를 알려도 바디를 보내지 않기 때문이다):
  - 지정한 길이보다 많이 쓰려 하면 그 `write`에서 `IOException`(`ContentLengthExceededException`)을 던지고 연결을 닫기로 표시한다.
  - 핸들러가 반환했는데 지정한 길이보다 적게 썼으면 연결을 닫는다(KA-05). 클라이언트는 짧은 바디를 오류로 감지한다.
- 쓰기 실패는 삼키지 않는다. `IOException`을 핸들러에 그대로 전달하고, 연결을 닫기로 표시한다(keep-alive.md §3.4).
- 핸들러가 바디 스트림을 `close()`하면 응답 바디를 끝낸다(chunked 종료 청크). 연결은 닫지 않는다. 그 뒤의 쓰기는 `IOException`이다.
- 테스트 훅: 엔진 내부의 소켓 출력 스트림 쓰기 호출 수를 셀 수 있게 한다(테스트에서만 쓰는 package-private 생성자). 작은 응답이 한 번의 쓰기로 나가는지 확인한다.
- 완료 조건: KA-05, RS-09, RS-20, RS-24 통과.

### P2-06 연결 유지 결정 `[ ]`
- `server.Persistence.decide(exchange)` 한 곳에서만 정한다. keep-alive.md §4.6의 조건을 따른다.

```
persistent = 요청이 유지를 원함        // 1.1: Connection에 close 토큰 없음 / 1.0: keep-alive 토큰 있음
          && !response.closeRequested   // setCloseConnection, Connection: close
          && 응답 프레이밍이 정상 완료됨  // 길이 불일치·쓰기 실패·커밋 후 예외가 아님
          && 프레이밍이 close-delimited가 아님
          && 요청 바디가 끝까지 소비됨    // drain 성공, 100-continue 미전송 아님
          && requestCount < maxRequestsPerConnection
          && !server.stopping
```

- `Connection` 헤더는 쉼표로 나눈 토큰을 대소문자 무시로 비교한다. 여러 줄의 `Connection` 헤더도 합쳐서 본다.
- 결정 결과를 헤더에 반영한다. 응답 헤더를 쓰는 시점(커밋)에 판단할 수 없는 조건(drain 실패)이 있으므로 두 단계로 나눈다.
  1. 커밋 시점: 그때까지 알 수 있는 조건으로 판단한다. 닫을 예정이면 `Connection: close`를 쓴다. HTTP/1.0 keep-alive면 `Connection: keep-alive`와 `Keep-Alive: timeout=<idle 초>`를 쓴다. HTTP/1.1 유지에는 `Connection` 헤더를 쓰지 않는다.
  2. 응답 후: drain 실패 같은 이유로 닫는다. 이미 유지한다고 알렸어도 닫는 것은 허용된다(클라이언트가 재시도한다).
- `maxRequestsPerConnection`(기본 1000)에 도달한 요청의 응답에는 `Connection: close`를 쓴다. 한 연결이 워커를 오래 독점하지 못하게 하려는 값이다. 연구 문서에 없는 값이므로 P10에서 다시 정한다.
- 완료 조건: KA-09, RS-16, RS-23 통과. KA-08, KB-03 유지.

### P2-07 핸들러 예외와 오류 응답 `[ ]`
- 미커밋 상태에서 `HttpException(status)`: 버퍼와 헤더를 지우고 그 상태로 짧은 `text/plain` 오류 응답을 보낸다. 연결은 위 규칙대로 유지할 수 있다.
- 미커밋 상태에서 다른 예외: 500. 예외는 SEVERE로 로그를 남긴다. 클라이언트에는 스택 트레이스를 보내지 않는다.
- 커밋 후 예외: 연결을 닫는다. chunked였다면 종료 청크를 보내지 않는다. 그래야 클라이언트가 잘린 응답을 감지한다.
- `sendError(status, message)`: 미커밋이면 버퍼를 지우고 오류 응답을 보낸 뒤 커밋한다. 이후 쓰기는 무시한다(Servlet과 같음). 커밋 후면 `IllegalStateException`.
- `sendRedirect(location)`: 302 + `Location`. 상대 URI를 그대로 쓴다(RFC 9110 §10.2.2가 허용). 값 검증은 P2-02와 같다.
- 오류 본문 형식(HTML/JSON)을 바꾸는 확장점은 P4-04에서 정한다.
- 완료 조건: RS-13~15 통과.

## 새 테스트 목록 (RS)

| ID | 시나리오 | 기대 |
|---|---|---|
| RS-01 | 일반 GET | 상태 줄이 정확히 `HTTP/1.1 200 OK\r\n` |
| RS-02 | 핸들러가 204를 설정하고 5바이트 씀 | `Content-Length`, `Transfer-Encoding` 없음, 바디 없음, 다음 요청 정상 |
| RS-03 | 304 | RS-02와 같음 |
| RS-04 | 길이 미지정, 100바이트 | `Content-Length: 100` |
| RS-05 | 길이 미지정, 100 KB, HTTP/1.1 | chunked, 연결 유지 |
| RS-06 | 길이 미지정, 100 KB, HTTP/1.0 | `Connection: close`, 서버가 닫아서 끝을 알림 |
| RS-07 | HEAD, 핸들러가 100바이트 씀 / `setContentLengthLong(100)` 후 0바이트 씀 | 둘 다 `Content-Length: 100`, 바디 없음, 연결 유지, 다음 요청 정상 |
| RS-08 | HEAD, 핸들러가 100 KB 씀 | 길이 헤더 없음, 바디 없음, 다음 요청 정상 |
| RS-09 | CL 5 지정 후 10바이트 쓰기 | 핸들러가 `ContentLengthExceededException`, 클라이언트는 5바이트 받고 연결 닫힘 |
| RS-10 | `setHeader("X", "a\r\nY: b")` | `IllegalArgumentException` |
| RS-11 | `addHeader("X", "1")`, `addHeader("X", "2")` | `X` 줄 두 개 |
| RS-12 | 모든 응답 | IMF-fixdate 형식의 `Date` 헤더 |
| RS-13 | 커밋 전에 `RuntimeException` | 500, 연결 유지, 다음 요청 정상 |
| RS-14 | 64 KB를 쓴 뒤(커밋 후) 예외 | 종료 청크 없이 연결 닫힘 |
| RS-15 | `throw new HttpException(404, …)` | 404 |
| RS-16 | 같은 연결에서 요청 `maxRequestsPerConnection`개 | 마지막 응답에 `Connection: close` |
| RS-17 | `getWriter()` 다음 `getOutputStream()` | `IllegalStateException` |
| RS-18 | `getWriter()`로 "한글" | UTF-8 바이트, Content-Type에 `charset=UTF-8` |
| RS-19 | `flushBuffer()` 후 `setHeader` | 헤더가 응답에 없음, 예외 없음 |
| RS-20 | 1 KB 응답 | 소켓 쓰기 1회 |
| RS-21 | HTTP/1.0 요청 | 상태 줄은 `HTTP/1.1`, chunked가 아님 |
| RS-22 | 205 | `Content-Length: 0`, 바디 없음 |
| RS-23 | `setCloseConnection(true)`, 전역 `maxRequestBodyBytes` 초과(413), `maxRequestsPerConnection` 도달 | 세 경우 모두 응답에 `Connection: close`가 있고 서버가 닫는다 |
| RS-24 | 304 응답에 `setContentLengthLong(1000)` 후 0바이트 | `Content-Length` 없음, 연결 유지 |

## 완료 조건 (단계)
- KA-02, 03, 05, 09에서 `KnownDefect`를 제거했다. RS 테스트가 전부 통과한다.
- 남은 KA는 KA-12(P3)뿐이다. keep-alive.md §2.1의 "수정됨" 열에 KA-02, 03, 05, 09를 채웠다(R-04).
