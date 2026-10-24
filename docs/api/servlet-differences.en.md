[English](servlet-differences.en.md) | [한국어](servlet-differences.md)

# Differences from the Servlet API

TinyHttpd uses familiar names where semantics are shared and explicit alternatives where they differ. It supplies its own `Request`, `Response`, `Handler`, and `Filter` interfaces; it is not a Servlet container. Exact contracts are in the `com.snoworca.tinyhttpd.http` Javadoc.

## Response and body behavior

| API | TinyHttpd behavior |
|---|---|
| `Response.getWriter()` | Uses the content type's charset; otherwise UTF-8 and appends `charset=UTF-8`. The Servlet API traditionally defaults to ISO-8859-1. |
| `Response.setStatus(1xx)` | Throws `IllegalArgumentException`. The server handles 100; use `upgrade()` for 101. |
| `Response.setHeader("Transfer-Encoding", ...)` | Rejected; the server owns response framing. |
| `Request.getInputStream().close()` | Does not close the connection. The engine handles unread request-body bytes after the response, subject to drain limits and connection handling. |
| Response writer / stream | Choose one; using the other after the first throws `IllegalStateException`. |

## Container features not provided

- No combined `getParameter*()` API: use `getQueryParameter*()` for query parameters and `readForm()` or `readMultipart()` for bodies.
- No core `HttpSession`: use the optional `tinyhttpd-session` module and install `SessionFilter`.
- No `RequestDispatcher`, `ServletContext`, JSP, container security roles, `AsyncContext`, or `ReadListener` APIs.
- Header/parameter collections use `List` or `Collection` instead of Servlet-style `Enumeration`.

## TinyHttpd-specific APIs

- `Request.getPath()` returns a decoded path after invalid escapes, encoded slashes, NUL, and dot segments are rejected before the handler.
- `getServerName()` and `getServerPort()` prefer an absolute-form request target's authority over Host.
- `Response.sendFile(File|FileChannel, offset, length)` sends a file region. `FileResponses` adds Range and conditional-request handling.
- `Response.startEventStream()` supports SSE; `upgrade(protocol)` hands over an upgraded connection.
- `Request.readMultipart(MultipartLimits)` pulls parts incrementally without buffering the whole body.
- `Response.setCloseConnection(boolean)` and `HttpException.withHeader(...)` make connection/error handling explicit.

See the [English user guide](../guide/en.md) for runnable examples and [한국어 가이드](../guide/ko.md) for the Korean edition.
