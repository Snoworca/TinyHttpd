# P5. 업로드, 다운로드, 압축

- 목표: NAS의 핵심 경로를 메모리 상한 안에서 처리한다. 대용량 업로드는 최종 목적지로 바로 스트리밍하고, 다운로드는 Range와 조건부 요청을 정확히 처리한다.
- 선행: P4
- 관련: [core-analysis.md](../research/core-analysis.md) §2.1~2.2 · [servlet-api.md](../research/servlet-api.md) §4.2 (`MultipartReader`, `Part`, `sendFile`) · 업스트림 #545, #457, #279, #557, #425, #294, #463, #492 · RFC 7578, RFC 9110 §13~14, RFC 6266

## 작업

### P5-01 스트리밍 multipart 파서 `[ ]`
- `Request.readMultipart(MultipartLimits)` → `MultipartReader`. 풀(pull) 방식이다. 바디 전체를 메모리나 임시 파일에 담지 않는다. 메모리 매핑도 쓰지 않는다(#545, #457).
- boundary: `Content-Type`의 `boundary` 파라미터(1~70자, RFC 2046 §5.1.1). 없거나 형식이 틀리면 `HttpException(400)`. multipart가 아니면 `HttpException(415)`.
- 경계 탐색: 고정 크기 버퍼(기본 16 KB) 위에서 `\r\n--boundary`를 찾는다. 경계가 버퍼 끝에 걸치는 경우를 처리하려고 경계 길이만큼 겹치게 남긴다. 알고리즘은 KMP나 Horspool 중 하나로 한다. 버퍼가 고정이므로 메모리는 업로드 크기와 무관하다.
- 파트 헤더: `maxPartHeaderBytes`(기본 8 KB)까지 읽는다(구 코드는 1 KB). 파트 헤더 바이트는 **UTF-8로 디코딩**한다.
- `Content-Disposition`의 `name`, `filename`을 해석한다. 브라우저 규칙은 이렇다.
  - RFC 7578 §4.2는 `filename*`를 금지한다. 브라우저는 `filename="한.txt"`에 UTF-8 원시 바이트를 그대로 넣는다.
  - 따옴표 안의 `"`, CR, LF는 `%22`, `%0D`, `%0A`로 바꿔 보낸다(WHATWG HTML의 multipart/form-data 인코딩).
  - 그래서 이 세 이스케이프만 되돌린다. 다른 `%`는 그대로 둔다.
- `filename*`(RFC 8187)는 관용으로 처리한다. 브라우저가 아닌 클라이언트가 보낼 수 있으므로, 있으면 `filename`보다 우선한다.
- 한도(`MultipartLimits`): `maxParts`, `maxPartBytes`, `maxTotalBytes`, `maxPartHeaderBytes`. 넘으면 그 파트의 스트림이나 `next()`에서 `HttpException(413)` 또는 그에 해당하는 `IOException`을 던진다. 이후 연결은 닫는다(drain 한도를 넘을 가능성이 크기 때문이다).
- 앞 파트를 끝까지 읽지 않고 `next()`를 부르면 남은 바이트를 건너뛴다.
- 종료 경계(`--boundary--`) 없이 바디가 끝나면 `MalformedBodyException`이다. preamble과 epilogue는 무시한다.
- 완료 조건: IO-01~08 통과. 4 GB 업로드에서 힙 증가가 버퍼 크기 수준이다(IO-09).

### P5-02 `Part` 편의 기능과 파일 이름 `[ ]`
- `Part.readString(int maxBytes)`: 작은 form 필드를 문자열로 읽는다. 파트의 charset, 없으면 UTF-8을 쓴다. 넘으면 413.
- `getSubmittedFileName()`: 위 디코딩을 거친 이름에서 경로 성분을 제거한다(`/`와 `\` 모두 기준). `NUL`과 제어 문자를 지운다. 결과가 비었거나 `.`/`..`이면 `null`. **이 값을 그대로 파일 경로로 쓰지 말라고** javadoc에 적는다. 저장 위치 결정은 앱의 책임이다(P9 SEC-06).
- 업로드 중 끊김: 파트 스트림이 `TruncatedBodyException`을 던진다. 부분 파일 정리는 앱이 한다. 권장 패턴(임시 이름으로 쓰고 끝나면 `renameTo`)을 javadoc 예제로 둔다.
- 완료 조건: IO-10, IO-11 통과.

### P5-03 `readForm` `[ ]`
- `application/x-www-form-urlencoded`만 받는다. 다른 Content-Type이면 `HttpException(415)`.
- `maxBytes`를 넘으면 `HttpException(413)`. 디코딩은 P4-05의 form 모드 디코더를 쓴다. charset은 Content-Type의 값, 없으면 UTF-8.
- `getInputStream`/`readForm`/`readMultipart` 중 둘 이상을 부르면 `IllegalStateException`(servlet-api.md §4.2).
- 완료 조건: IO-12, IO-13 통과.

### P5-04 소켓 종류와 `sendFile` 결정 `[ ]`
`FileChannel.transferTo`를 쓰려면 소켓에 채널이 있어야 한다. 그런데 `ServerSocket.accept()`로 만든 소켓은 `getChannel()`이 `null`이다. 채널 기반 소켓(`ServerSocketChannel`)으로 바꾸면 다음이 달라질 수 있다. 그래서 먼저 확인한다.

| 확인 항목 | 왜 중요한가 |
|---|---|
| 채널 기반 소켓의 `getInputStream()` 읽기가 `setSoTimeout`을 따르는가 (JVM / Android API 21) | P3의 idle·body read 타임아웃이 이것에 의존한다 |
| 다른 스레드의 `close()`가 막힌 `transferTo`를 깨우는가 | P3의 쓰기 타임아웃 |
| `transferTo`가 Android에서 실제로 zero-copy(sendfile)인가, 그리고 64 KB 버퍼 복사보다 빠른가 | 이득이 없으면 채널로 바꿀 이유가 없다 |
| `transferTo`가 한 번에 보내는 최대 크기와 부분 전송 처리 | 반복 호출 루프가 필요하다 |

- JVM 쪽은 P0-07에서 이미 확인했다. 이 작업은 **Android 쪽 확인(AND-05)** 을 하고 최종 결정을 내린다. 그 전까지 엔진은 `ServerSocket`을 쓴다.
- 결과와 결정은 `docs/research/socket-io.md`(Android 절, 결정 절)에 기록한다.
- 완료 조건: 문서가 있고, 결정(채널 사용 여부, 복사 버퍼 크기)이 P5-05에 반영되었다.

### P5-05 `sendFile` 구현 `[ ]`
- 두 가지 형태를 둔다.
  - `Response.sendFile(File file, long offset, long length)`
  - `Response.sendFile(FileChannel channel, long offset, long length)`: Android 10 이상의 SAF(`content://`)에서 얻은 `ParcelFileDescriptor`를 `new FileInputStream(pfd.getFileDescriptor()).getChannel()`로 바꿔 넘길 수 있다(API 21에 있는 API만 쓴다). 채널은 호출자가 닫는다.
- 공통 규칙: 상태와 헤더(`Content-Range` 등)는 호출자가 정한다. 이 메서드는 `Content-Length: length`를 설정하고, 바디를 보내고, 응답을 끝낸다. 커밋 전에만 부를 수 있다.
- 경로 1 (P5-04 결과가 좋을 때): 평문 + 채널 기반 소켓이면 출력 버퍼를 flush한 뒤 `transferTo`를 반복 호출한다. 호출마다 쓰기 시작 시각을 기록해 watchdog이 감시하게 한다.
- 경로 2 (기본, TLS 포함): `RandomAccessFile`로 읽어 복사한다. 복사 버퍼는 연결 출력 버퍼(8 KB)를 쓰지 않고, 서버 전체가 공유하는 작은 풀에서 빌린다(기본 64 KB × 8개). 풀이 비면 출력 버퍼로 복사한다. 메모리 상한은 `64 KB × 풀 크기`로 고정된다.
- HEAD 요청이면 헤더만 보낸다.
- 파일이 전송 도중 짧아지면(다른 프로세스가 잘라 냄) 연결을 닫는다. 선언한 길이를 채울 수 없기 때문이다(P2-05와 같은 규칙).
- 완료 조건: IO-14~16, IO-31 통과.

### P5-06 Range와 조건부 요청 유틸 `[ ]`
- 위치: **core**의 `com.snoworca.tinyhttpd.http.FileResponses`. webserver 모듈이 없어도 NAS 앱이 파일을 정확히 보낼 수 있어야 하기 때문이다. servlet-api.md §4.2의 "Range 파싱은 webserver 모듈"이라는 문장은 이 결정으로 바꾼다(문서도 고친다).
- 리소스 추상화 `FileResource`: `length()`, `lastModified()`(밀리초), `openChannel()`(`FileChannel`), `getName()`. 구현은 `FileResource.of(File)`과, 앱이 SAF용으로 만드는 구현(길이·수정 시각은 `DocumentFile`이나 `ContentResolver` 질의로 얻는다)이다. core는 Android API에 의존하지 않으므로 SAF 구현 예제는 가이드(P12-04)에 둔다.
- `FileResponses.serve(Request, Response, FileResource, String contentType)`(그리고 `File` 편의 오버로드) 하나로 다음을 처리한다:
  - 검증자: `Last-Modified`(수정 시각, HTTP 날짜라 초 단위)와 **강한** `ETag`(`"<크기 16진>-<수정시각 밀리초 16진>"`). nginx는 초 단위를 쓰지만, 같은 1초 안에 같은 크기로 바뀐 파일을 구분하려고 밀리초를 쓴다(파일 시스템이 밀리초를 지원할 때 의미가 있다). 약한 ETag는 쓰지 않는다. RFC 9110 §13.1.5에 따라 `If-Range`와 `If-Match`(§13.1.1)는 강한 비교만 하므로, 약한 ETag로는 이어받기와 `If-Match`가 동작하지 않는다.
  - 조건부(RFC 9110 §13.2.2 순서): `If-Match` → `If-Unmodified-Since` → `If-None-Match` → `If-Modified-Since` → 304 또는 412
  - Range: `bytes=a-b`, `bytes=a-`, `bytes=-n` 하나만 지원한다. 여러 구간 요청은 무시하고 200 전체로 응답한다(RFC 9110 §14.2가 허용).
    - 만족할 수 없는 범위 → 416 + `Content-Range: bytes */<길이>`
    - `If-Range`가 현재 검증자와 다르면 Range를 무시한다
  - `Accept-Ranges: bytes`를 항상 붙인다. 바디 전송은 `sendFile`로 한다.
  - `X-Content-Type-Options: nosniff`를 항상 붙인다(P9 SEC-19).
- 개별 함수(`parseRange`, `evaluatePreconditions`)도 공개한다. 파일이 아닌 리소스(예: DB 블롭)에도 쓸 수 있게 하기 위해서다.
- 다운로드 파일 이름: `FileResponses.attachment(Response, String fileName)`이 RFC 6266 `Content-Disposition`을 만든다. ASCII 대체 이름(`filename="…"`, 비ASCII는 `_`)과 `filename*=UTF-8''<퍼센트 인코딩>`을 함께 쓴다. 따옴표, CR, LF, 경로 구분자는 제거한다.
- 완료 조건: IO-17~23, IO-28~30 통과.

### P5-07 gzip 정책 `[ ]`
- core는 **기본으로 압축하지 않는다.** 구 코드의 "text/*는 항상 gzip" 정책을 없앤다(#463, SSE 버퍼 문제).
- 선택 필터 `GzipFilter`(core `http.filter`):
  - `Accept-Encoding`에 `gzip`이 있고 q > 0일 때만 압축한다
  - 대상: 허용 목록(`text/*`, `application/json`, `application/javascript`, `image/svg+xml`)에 있는 타입만
  - 제외: 이미 `Content-Encoding`이 있음, `text/event-stream`, 206/304/204, HEAD, `sendFile`(미디어 파일은 이미 압축되어 있다)
  - 첫 1 KB를 버퍼에 모은 뒤 판단한다. 응답이 1 KB 미만으로 끝나면 압축하지 않는다
  - `Vary: Accept-Encoding`을 붙인다
  - 압축하면 핸들러가 지정한 `Content-Length`를 지운다. 강한 `ETag`는 약한 ETag(`W/` 접두)로 바꾼다. 압축본은 다른 표현이므로 강한 검증자를 그대로 쓰면 안 된다(nginx와 같은 처리)
  - `Deflater`는 `finally`에서 반드시 `end()`한다(#492)
  - 동시에 압축하는 응답 수에 상한을 둔다(`maxConcurrentGzip`, 기본 8). 넘으면 그 응답은 압축하지 않는다. `Deflater` 하나가 네이티브 메모리를 약 256 KB 쓰기 때문이다(README §5.1)
- 완료 조건: IO-24~27, IO-32, IO-33 통과.

## 새 테스트 목록 (IO)

| ID | 시나리오 | 기대 |
|---|---|---|
| IO-01 | 파일 2개 + 필드 2개 | 순서대로 4개 파트, 내용 일치 |
| IO-02 | 경계가 버퍼 경계에 걸치게 만든 바디 | 내용 일치 |
| IO-03 | 파트 내용에 경계와 비슷한 바이트(`\r\n--bounda`) | 내용 일치 |
| IO-04 | 종료 경계 없음 | `MalformedBodyException` |
| IO-05 | `maxParts` 초과 | 413, 연결 닫힘 |
| IO-06 | `maxPartBytes` 초과 | 413, 연결 닫힘 |
| IO-07 | 파트를 읽지 않고 `next()` | 다음 파트가 정확히 나옴 |
| IO-08 | chunked로 보낸 multipart | IO-01과 같음 |
| IO-09 | 4 GB 업로드 (`/dev/null`로 저장) | 힙 증가 ≤ 1 MB, 성공 |
| IO-10 | `filename="../../etc/passwd"`, `filename="C:\a\b.txt"` | `passwd`, `b.txt` |
| IO-11 | `filename="한.txt"`(UTF-8 원시 바이트) / `filename="a%22b.txt"` / `filename*=UTF-8''%ED%95%9C.txt` | `한.txt` / `a"b.txt` / `한.txt` |
| IO-12 | `readForm(1024)`에 2 KB 바디 | 413 |
| IO-13 | `getInputStream()` 다음 `readForm` | `IllegalStateException` |
| IO-14 | 10 MB 파일 `sendFile`(`File` / `FileChannel`) | 내용·길이 일치, 연결 유지 |
| IO-15 | HEAD + `sendFile` | 헤더만 |
| IO-16 | 다운로드 중 / 업로드 중 클라이언트 끊김 | SEVERE 없음, 워커 반환. 업로드는 파트 스트림이 `TruncatedBodyException` |
| IO-17 | `Range: bytes=0-99` | 206, `Content-Range: bytes 0-99/<len>`, 100바이트 |
| IO-18 | `Range: bytes=-100` | 마지막 100바이트 |
| IO-19 | `Range: bytes=<len>-` | 416, `Content-Range: bytes */<len>` |
| IO-20 | `Range: bytes=0-1,5-6` | 200 전체 |
| IO-21 | `If-None-Match`가 현재 ETag | 304, 바디 없음 |
| IO-22 | `If-Range`가 이전 ETag | 200 전체 |
| IO-23 | OkHttp로 이어받기(Range) | 합친 내용 일치 |
| IO-24 | `GzipFilter`, 10 KB JSON, `Accept-Encoding: gzip` | 압축됨, `Vary` 있음 |
| IO-25 | `GzipFilter`, `Content-Encoding: br`이 이미 있음 | 압축 안 함 |
| IO-26 | `GzipFilter`, `text/event-stream` | 압축 안 함, 이벤트가 즉시 도착 |
| IO-27 | `GzipFilter`, 100바이트 응답 | 압축 안 함 |
| IO-28 | `If-Range`가 현재 ETag + `Range: bytes=10-` | 206 |
| IO-29 | `If-Match`가 현재 ETag / 다른 ETag | 200 / 412 |
| IO-30 | `attachment(res, "보고서 \"최종\".pdf")` | 따옴표를 제거한 `보고서 최종.pdf` 기준으로 `filename="___ __.pdf"`(비ASCII → `_`)와 `filename*=UTF-8''%EB%B3%B4…` |
| IO-31 | 100 MB 파일 동시 다운로드 16개 | 힙 증가 ≤ 1 MB(복사 버퍼 풀 + 출력 버퍼), 모두 성공 |
| IO-32 | `GzipFilter` + 강한 ETag + `setContentLengthLong` | `Content-Length` 없음, `ETag`가 `W/` 접두 |
| IO-33 | `maxConcurrentGzip` 2에서 동시 응답 3개 | 2개 압축, 1개 비압축 |

## 완료 조건 (단계)
- IO 테스트가 전부 통과한다. `docs/research/socket-io.md`에 결정이 있다.
- servlet-api.md §4.2의 Range 위치 문장을 P5-06 결정에 맞게 고쳤다.
