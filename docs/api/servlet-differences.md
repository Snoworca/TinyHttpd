# Servlet API와 다른 점

원칙: 이름이 같으면 의미도 같다. 의미가 다르면 이름도 다르다. 자세한 계약은 `com.snoworca.tinyhttpd.http` javadoc에 있다.

## 같은 이름, 다른 기본값

| 메서드 | TinyHttpd | Servlet |
|---|---|---|
| `Response.getWriter()` | charset이 없으면 UTF-8을 쓰고 `;charset=UTF-8`을 붙인다 | ISO-8859-1 |
| `Response.setStatus(1xx)` | `IllegalArgumentException`. 100은 서버가 보내고 101은 `upgrade()`만 쓴다 | 허용 |
| `Response.setHeader("Transfer-Encoding", …)` | `IllegalArgumentException`. 프레이밍은 서버가 정한다 | 컨테이너마다 다름 |
| `Request.getInputStream().close()` | 연결을 닫지 않는다. 남은 바디는 응답 뒤 서버가 버린다 | 같음(명시) |

## Servlet에 있고 여기 없는 것

- `getParameter*` (D-04): 쿼리는 `getQueryParameter*`, 바디는 `readForm`/`readMultipart`로 명시적으로 읽는다.
- `HttpSession` (D-03): 선택 모듈 `tinyhttpd-session`의 필터로 제공한다.
- `RequestDispatcher`, `ServletContext`, JSP, 보안 역할, `Locale`, `AsyncContext`/`ReadListener`, `Enumeration` 반환(→ `List`/`Collection`).

## 여기에만 있는 것

- `Request.getPath()`: 디코딩·정규화한 경로. 점 세그먼트, `%2F`, NUL, 잘못된 이스케이프는 핸들러 전에 400.
- `Request.getServerName()/getServerPort()`: absolute-form 대상의 호스트가 `Host`보다 우선한다.
- `Response.sendFile(File|FileChannel, offset, length)`, `FileResponses`(Range, 조건부 요청).
- `Response.startEventStream()`(SSE), `Response.upgrade(protocol)`(WebSocket 등).
- `Request.readMultipart(MultipartLimits)`: 버퍼링 없는 풀 방식 multipart.
- `Response.setCloseConnection(boolean)`, `HttpException.withHeader(...)`.
