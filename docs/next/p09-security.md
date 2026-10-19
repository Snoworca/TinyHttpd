# P9. 보안 점검표

- 목표: 보안 요구를 한곳에 모은다. 각 항목이 어느 단계의 어떤 작업과 테스트로 충족되는지 추적한다. 마지막에 전체 감사를 한다.
- 선행: 단계마다 해당 항목을 확인한다. 최종 감사(P9-03)는 P8 이후에 한다.
- 위협 모델(NAS 기준):
  - 서버는 집이나 사무실 LAN의 구형 폰에서 돈다. 공격자는 같은 LAN의 다른 기기이거나, 사용자의 브라우저에서 실행되는 **악성 웹 페이지**다(DNS rebinding, CSRF, CSWSH).
  - 포트 포워딩으로 인터넷에 노출되는 경우도 가정한다.
  - 자원이 작은 기기이므로 **자원 고갈이 곧 서비스 거부**다.

## P9-01 점검표 `[x]` (2026-10-18, docs/research/security-audit.md)

| ID | 항목 | 충족 작업 | 확인 테스트 |
|---|---|---|---|
| SEC-01 | 요청 스머글링: TE+CL, 잘못된 TE, CL 목록 불일치, 콜론 앞 공백, obs-fold, 단독 CR | P1-05, P1-06, P1-08 | RQ-02, 04, 05, 08~12 |
| SEC-02 | 자원 고갈: 모든 입력의 **기본값**에 상한이 있다(아래 P9-02 표). 앱이 `TinyHttpd.UNLIMITED`로 명시해 풀 수 있다(docs/research/unlimited-settings.md) | 각 단계 | P9-02 표의 테스트 |
| SEC-03 | 응답 분할: 헤더 이름·값의 CR/LF/NUL 거부, 쿠키 값 검증, `Location` 검증 | P2-02, P2-07, P4-06 | RS-10, API-16 |
| SEC-04 | 경로 탐색: 세그먼트 단위 디코딩, `%2F`·점 세그먼트·NUL 거부, 정규 경로가 루트 아래인지, 심볼릭 링크 | P4-05, P8-02 | API-09~11, MOD-08 |
| SEC-05 | 생성 HTML의 XSS: 오류 페이지, 디렉터리 목록. 구 `GeneralHandler` 제거. core의 `Html.escape` | P4-04, P4-11, P8-01, P8-02 | API-19, MOD-09 |
| SEC-06 | 업로드 파일 이름: 경로 성분·제어 문자 제거. 저장 경로는 앱이 정한다고 문서화 | P5-02 | IO-10 |
| SEC-07 | slowloris와 느린 읽기/쓰기: 헤더 마감, 바디 읽기, 쓰기 타임아웃 | P3-03, P3-04 | TH-04~06 |
| SEC-08 | 프록시 헤더(`X-Forwarded-*`)를 기본으로 믿지 않는다. 신뢰할 프록시 목록을 받는 선택 필터만 제공한다 | P4-03, P4-14 | SEC-T01, API-23 |
| SEC-09 | WebSocket 출처 검사(CSWSH): 기본 동일 출처만 허용. DNS rebinding은 SEC-14에 의존 | P8-03 | MOD-26 |
| SEC-10 | 세션: 128비트 이상 무작위 ID, `HttpOnly`/`SameSite`/`Secure`, 로그인 후 ID 재발급, 만료, 상한 | P8-04 | MOD-20~23 |
| SEC-11 | TLS 기본값: TLS 1.2 이상, AEAD cipher만, 핸드셰이크 타임아웃 | P7-02, P7-03 | TLS-01~05 |
| SEC-12 | 임시 파일: core는 임시 파일을 만들지 않는다. 구 `tempfiles`(공용 `java.io.tmpdir` 사용)를 삭제한다. 앱 가이드에 앱 전용 디렉터리(`Context.getCacheDir()`) 사용을 적는다 | P5-01, P8-05, P12-04 | – |
| SEC-13 | 정보 노출: 스택 트레이스를 응답에 넣지 않는다. `Server` 헤더는 기본으로 끈다. 디렉터리 목록은 기본으로 끈다 | P2-04, P2-07, P8-02 | RS-13, SEC-T02 |
| SEC-14 | **DNS rebinding**: `Host` 허용 목록 필터(`HostAllowlistFilter`, IP 리터럴은 기본 허용). 허용 목록에 없는 `Host`는 421. NAS 앱 가이드에서 사용을 권장한다 | P4-12, P12-04 | API-20, API-21 |
| SEC-15 | CSRF: 상태를 바꾸는 요청에 `Origin`/`Referer` 검사 또는 토큰. session 모듈의 선택 `CsrfFilter`. DNS rebinding은 SEC-14에 의존 | P8-04 | MOD-25 |
| SEC-16 | 의존성: core 런타임 의존성 0개 유지. 다른 모듈의 런타임 의존성은 core뿐이다(servlet 어댑터 제외) | 빌드 | `checkRuntimeDependencies`(`check`에 연결) |
| SEC-17 | 퍼징: 요청 파서, chunked, multipart, WebSocket 프레임 | P1-14, P9-05 | SEC-T03 |
| SEC-18 | 코드 출처: 라이선스가 불분명한 코드(예: 구 `NanoWSD.encodeBase64`의 Stack Overflow 코드)를 옮겨 오지 않는다 | P4-13 | API-22 |
| SEC-19 | 사용자가 올린 파일의 저장형 XSS: `nosniff` 상시, 신뢰하지 않는 루트는 HTML/SVG를 `attachment` + `CSP: sandbox`로 | P5-06, P8-02 | MOD-30 |

## P9-02 자원 상한 표 `[x]` (2026-10-18, docs/research/security-audit.md)

공격자가 조절할 수 있는 모든 입력의 기본값에 상한이 있는지 확인하는 표다. 빈 칸이 있으면 감사에서 실패로 본다. 앱이 `TinyHttpd.UNLIMITED`/`UNLIMITED_LONG`을 넣어 상한을 푸는 것은 앱의 선택이다(`UnlimitedSettingsTest`).

완료 조건(P9-01, P9-02 공통): 각 행의 "충족 작업"이 끝났고 "확인 테스트"가 통과한다. 단계를 끝낼 때마다 그 단계 소유 행을 점검한다.

| 입력 | 상한 | 기본값 | 넘으면 | 테스트 |
|---|---|---|---|---|
| 동시 연결 | `maxConnections` | 128 | 503 | TH-02 |
| 주소별 연결 | `maxConnectionsPerAddress` | 32 | 거절 | TH-16 |
| 대기열 대기 시간 | `queueWaitTimeout` | 5 s | 거절 | TH-09 |
| 워커 스레드 | `maxWorkers` | 64 | 대기열 | TH-01 |
| 요청 라인 | `maxRequestLineBytes` | 8190 B | 414 | RQ-20 |
| 헤더 줄 하나 / 전체 / 개수 | `inputBufferBytes - 2` / `maxHeaderBytes` / `maxHeaderCount` | 8190 B / 16 KB / 100 | 431 | KA-06, RQ-26 / RQ-27 / RQ-26 |
| 헤더 수신 시간 | `headerTimeout` | 10 s | 408 | TH-04 |
| 요청 바디 | `maxRequestBodyBytes`(전역, 선택, CL과 chunked 모두). 지정하지 않으면 핸들러가 `readForm`/`MultipartLimits`로 정한다 | 없음 | 413 | RS-23, RQ-28, IO-06, IO-12 |
| 미소비 바디 | `maxDrainBytes` | 64 KB | 닫기 | RQ-17 |
| 바디 전송률(느린 POST) | `minRequestBodyRate`(클라이언트를 기다린 시간만 계산, 5 s 유예) | 0(끔). 인터넷 노출 시 240 B/s 권장 | 408 | ReviewDefectsTest |
| 청크 크기 줄 / trailer | `maxChunkSizeLineBytes` / `maxTrailerBytes` | 1 KB / 8 KB | 400 | RQ-14, 15 |
| form 바디 | `readForm(maxBytes)` | 호출자 | 413 | IO-12 |
| multipart 파트 수 / 크기 / 헤더 | `MultipartLimits` | 호출자 | 413 | IO-05, 06 |
| 연결당 요청 수 | `maxRequestsPerConnection` | 1000 | close | RS-16 |
| SSE 스트림 / 대기열 | `maxEventStreams` / `eventQueue*` | 64 / 64개·64 KB | 503 / 닫기 | SSE-06, 08 |
| WebSocket 연결 / 프레임 / 메시지 | `maxUpgradedConnections` / `maxFramePayloadBytes` / `maxMessageBytes` | 16 / 1 MB / 1 MB | 503 / 1009 | MOD-16, 19 |
| 장수명 연결 스레드 | `maxEventStreams + maxUpgradedConnections` | 80 | 503 | SSE-08, MOD-19 |
| 세션 수 / 주소별 | `maxSessions` / `maxSessionsPerAddress` | 1000 / 50 | 미인증 세션 제거 / 거부 | MOD-23, MOD-24 |
| TLS 핸드셰이크 시간 | `handshakeTimeout` | 10 s | 닫기 | TLS-05 |
| `sendFile` 복사 버퍼 | 공유 풀 | 64 KB × 8 | 출력 버퍼 사용 | IO-31 |

## P9-03 최종 감사 `[x]` (2026-10-18, 독립 검토 서브에이전트, docs/research/security-audit.md)
- P8 뒤에, 그리고 릴리스(P12) 전에 한다.
- 방법: 점검표와 상한 표를 한 줄씩 코드와 테스트로 확인한다. 별도 검토자(사람 또는 서브에이전트)가 코드만 보고 SEC 항목을 다시 찾는다. 점검표에 없는 발견은 항목으로 추가한다.
- 산출물: `docs/research/security-audit.md`(날짜, 커밋, 항목별 결과, 발견 사항과 처리).
- 완료 조건: 모든 항목이 "충족" 또는 "수용(이유)"이다.

## P9-04 보안 유틸의 위치 `[x]` (2026-10-18)
- 보안 유틸은 그것을 쓰는 단계에서 만든다. 이 작업은 위치를 추적만 한다.
  - `Html.escape` → P4-11
  - `HostAllowlistFilter` → P4-12
  - `Base64` → P4-13
  - `CsrfFilter` → P8-04
- 완료 조건: 위 작업들이 끝났다.

## P9-05 퍼징 확장 `[x]` (2026-10-18: `BodyFuzzTest`, `WebSocketFuzzTest`, `-Dfuzz.cases=10000`에서 각 1만 건 통과)
- P1-14의 무작위 변형 테스트를 chunked 바디, multipart 바디, WebSocket 프레임으로 넓힌다.
- 검사 항목은 같다: 미처리 예외 없음, 회전 없음, 힙 증가가 상한 이내.
- 완료 조건: SEC-T03(각 대상 1만 건, CI는 1천 건) 통과.

## 테스트 (SEC-T)

다른 단계의 테스트로 확인하는 항목은 점검표의 "확인 테스트" 열에 있다. 여기에는 이 단계에서만 정의하는 테스트를 둔다.

| ID | 시나리오 | 기대 |
|---|---|---|
| SEC-T01 | 필터 없이 `X-Forwarded-For: 1.2.3.4` | `getRemoteAddr()`는 실제 주소 |
| SEC-T02 | 디렉터리 목록 기본 설정 | 403 |
| SEC-T03 | 퍼징 4종 | 위 검사 통과 |

## 완료 조건 (단계)
- 점검표와 상한 표의 모든 행이 충족되었다. `docs/research/security-audit.md`가 있다.
