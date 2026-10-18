# P6. Server-Sent Events

- 목표: 블로킹 워커 모델 안에서 SSE를 안전하게 제공한다. 느린 클라이언트 하나가 다른 구독자나 서버 전체를 막지 않아야 한다.
- 선행: P3(watchdog, 쓰기 타임아웃), P4
- 관련: [servlet-api.md](../research/servlet-api.md) §3.3 교훈 4, §4.2 `EventStream`, §6 리스크 1 · [keep-alive.md](../research/keep-alive.md) §4.5 · 업스트림 #236, #555 · WHATWG HTML "Server-sent events"

## 설계

servlet-api.md §4.2의 `EventStream` 스케치에 **송신 대기열과 소유 스레드 개념**을 더한다.

```
handler thread (워커)                       다른 스레드 (예: 파일 변경 감시)
  es = res.startEventStream()                 broadcaster.broadcast("changed", json)
  broadcaster.subscribe(es)                       └ es.send(...)  → 대기열에 넣고 즉시 반환
  es.awaitClosed(20_000)   ◀── 대기열을 꺼내 쓰고, 20초 동안 보낼 것이 없으면 ": ping"
  (반환 = 스트림 종료)
```

- **소유 스레드**: `startEventStream()`을 부른 워커 스레드다. 소켓에 쓰는 것은 이 스레드뿐이다.
- 다른 스레드의 `send()`는 대기열에 넣기만 한다. 그래서 느린 클라이언트가 있어도 방송하는 스레드가 막히지 않는다.
- 소유 스레드가 직접 `send()`를 부르면 바로 쓴다. 진행률처럼 핸들러가 루프를 돌며 이벤트를 보내는 경우다.
- 대기열이 차면(느린 소비자) 스트림을 닫는다. 클라이언트는 `retry` 후 `Last-Event-ID`로 다시 연결한다.

## 작업

### P6-01 `startEventStream()` `[ ]`
- 커밋 전에만 부를 수 있다. 커밋 후면 `IllegalStateException`.
- 응답 헤더: `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`(앞단 프록시의 버퍼링을 끈다). 길이가 없으므로 HTTP/1.1이면 chunked, HTTP/1.0이면 close-delimited다(P2-03).
- 헤더를 즉시 커밋하고 flush한다. 클라이언트의 `onopen`이 바로 불리게 하기 위해서다.
- HEAD 요청이면 헤더만 보내고, 닫힌 `EventStream`을 돌려준다.
- `GzipFilter`는 이 Content-Type을 압축하지 않는다(P5-07).
- `maxEventStreams`(기본 64)에 도달했으면 `new HttpException(503, …).withHeader("Retry-After", "5")`를 던진다(P1-01).
- 스트림을 연 스레드는 워커 풀 계산에서 빠져 장수명 예산으로 옮겨진다(P3 "모델"). 그래서 SSE가 많아도 일반 요청용 워커는 줄지 않는다.
- 완료 조건: SSE-01, SSE-08, SSE-09 통과.

### P6-02 이벤트 직렬화 `[ ]`
- `send(event, data, id)`:
  - `event`와 `id`는 `null`일 수 있다. 값에 CR/LF가 있으면 `IllegalArgumentException`이다. `id`에 `NUL`이 있어도 같다(브라우저가 무시하는 값이다).
  - `data`는 `\r\n`, `\r`, `\n` 어느 것으로든 나눠 `data:` 줄 여러 개로 쓴다.
  - 끝에 빈 줄을 쓴다. 인코딩은 항상 UTF-8이다.
- `comment(text)` → `: text\n\n`. `retry(millis)` → `retry: n\n\n`.
- 이벤트 하나를 쓸 때마다 flush한다. chunked라면 이벤트 하나가 청크 하나가 된다(P2-05).
- 소유 스레드가 직접 `send()`할 때는 **대기열에 남은 이벤트를 먼저 쓰고** 이번 이벤트를 쓴다. 이벤트 순서를 보장하기 위해서다.
- 완료 조건: SSE-02, SSE-03 통과.

### P6-03 대기열과 `awaitClosed` `[ ]`
- 스트림별 대기열: 최대 `eventQueueEvents`(기본 64개) 또는 `eventQueueBytes`(기본 64 KB). 넘으면 스트림을 닫고 FINE 로그와 통계(`slowConsumerCloses`)를 남긴다.
- `awaitClosed(long heartbeatMillis)`: 소유 스레드에서만 부를 수 있다. 다른 스레드에서 부르면 `IllegalStateException`. 대기열을 비우며 쓰고, `heartbeatMillis` 동안 보낼 것이 없으면 `comment("ping")`을 쓴다. 쓰기에 실패하거나 `close()`가 불리면 반환한다.
- 끊김 감지는 하트비트 쓰기 실패와 쓰기 타임아웃(watchdog)에 의존한다. 그래서 감지는 최대 `heartbeatMillis + writeTimeout`만큼 늦을 수 있다. 이 한계를 javadoc에 적는다.
- `close()`: 어느 스레드에서든 부를 수 있다. 대기열에 남은 이벤트를 버리고 소유 스레드를 깨운다. 종료 청크는 **소유 스레드가** 쓴다("소켓에 쓰는 것은 소유 스레드뿐" 원칙). 소유 스레드가 `awaitClosed` 중이 아니면(직접 송신 루프 중) 다음 `send()`가 `IOException`(스트림 닫힘)을 던지고, 핸들러가 반환할 때 종료 청크가 쓰인다.
- 대기열 초과로 닫을 때는 종료 청크를 쓰지 않고 **연결을 바로 끊는다**. 느린 소비자에게 종료 청크를 쓰면 그 쓰기도 막히기 때문이다.
- 핸들러가 `awaitClosed` 없이 반환하면 스트림도 끝난다(응답 종료).
- servlet-api.md §4.2의 `EventStream` 인터페이스에 `awaitClosed`를 추가하고, 예제의 `hub.awaitClose(es, 20_000)`을 `es.awaitClosed(20_000)`으로 고친다.
- 완료 조건: SSE-04~07, SSE-12, SSE-13 통과.

### P6-04 `EventBroadcaster` `[ ]`
- core의 작은 유틸: `subscribe(es)`, `unsubscribe(es)`, `broadcast(event, data, id)`, `size()`. 내부 목록은 `CopyOnWriteArrayList`다(구독 변경보다 방송이 훨씬 잦다).
- 닫힌 스트림은 다음 방송 때 목록에서 뺀다.
- 완료 조건: SSE-06 통과.

### P6-05 `Last-Event-ID`와 재연결 `[ ]`
- `getLastEventId()`: 요청 헤더 `Last-Event-ID`. 없으면 `null`.
- 이벤트 재전송(놓친 이벤트 다시 보내기)은 앱의 책임이다. core는 저장하지 않는다. javadoc에 이벤트 링 버퍼 예제를 둔다.
- 완료 조건: SSE-10 통과.

### P6-06 서버 정지와 SSE `[ ]`
- `stop(grace)`가 시작되면 모든 열린 `EventStream`을 `close()`한다. 그러면 소유 스레드가 반환하고 응답이 정상으로 끝난다. 그 뒤 연결은 P3-08 규칙대로 닫힌다.
- 완료 조건: SSE-11 통과.

### P6-07 50개 스트림 규모와 대안 `[ ]`
- 목표 규모: 동시 스트림 50개(동시 사용자 50명 × 알림 탭 1개). 기본 상한 64는 여유분을 둔 값이다.
- AND-08에서 스트림 50개 + 업로드 동시 시나리오를 측정한다. 다음 중 하나라도 해당하면 대안으로 간다.
  - 스트림 스레드 50개 때문에 늘어난 상주 메모리(PSS에서 스트림 버퍼·대기열을 뺀 값)가 5 MB(스레드당 100 KB)를 넘는다.
  - 방송 지연 p95가 100 ms를 넘는다.
- 대안: 장수명 연결의 송신을 소수(기본 2개)의 펌프 스레드로 모은다. 핸들러는 `startEventStream()` 뒤에 반환하고, 펌프가 대기열을 비운다. 느린 소비자가 펌프를 막지 않도록 펌프의 쓰기에도 watchdog 쓰기 마감을 적용한다. 평문이면 Selector 기반 비블로킹 쓰기도 검토한다(P3-10과 함께 판단).
- 산출물: 측정 결과와 결정을 `docs/research/thread-model.md`(P3-10과 같은 문서)에 기록한다.
- 완료 조건: AND-08 결과와 판단(유지/대안 착수)이 문서에 있다. 대안에 착수하면 SSE-01~13이 펌프 구조에서도 통과한다.

## 새 테스트 목록 (SSE)

| ID | 시나리오 | 기대 |
|---|---|---|
| SSE-01 | `startEventStream()` | 헤더 즉시 도착, chunked, gzip 아님 |
| SSE-02 | `data`에 `a\nb\r\nc` | `data: a`, `data: b`, `data: c` 줄 |
| SSE-03 | `event`에 `\n` | `IllegalArgumentException` |
| SSE-04 | 하트비트 300 ms, 보낼 것 없음 | 약 300 ms 간격으로 `: ping` |
| SSE-05 | 클라이언트가 연결을 끊음 | `awaitClosed`가 `heartbeat + writeTimeout` 안에 반환, 워커 반환 |
| SSE-06 | 구독자 3명 중 1명이 읽지 않음 | 나머지 2명은 지연 없이 수신, 느린 1명은 대기열 초과로 닫힘 |
| SSE-07 | 다른 스레드에서 `awaitClosed` | `IllegalStateException` |
| SSE-08 | `maxEventStreams` + 1번째 | 503 + `Retry-After` |
| SSE-09 | HEAD | 헤더만, 연결 유지 |
| SSE-10 | `Last-Event-ID: 42`로 요청 | `getLastEventId()` = `42` |
| SSE-11 | 스트림 2개가 열린 상태에서 `stop(1000)` | 1 s 안에 정상 종료(종료 청크 수신) |
| SSE-12 | `okhttp-sse` 클라이언트로 100개 이벤트 | 순서와 내용 일치 |
| SSE-13 | 다른 스레드가 이벤트 5개를 넣은 직후 소유 스레드가 `send` | 6개가 넣은 순서대로 도착 |

SSE-12에는 테스트 의존성 `com.squareup.okhttp3:okhttp-sse:4.12.0`을 추가한다.

## 완료 조건 (단계)
- SSE 테스트가 전부 통과한다. servlet-api.md의 `EventStream` 스케치를 고쳤다.
