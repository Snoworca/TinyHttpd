# P7. TLS

- 목표: 구형 Android에서도 안전한 기본값으로 HTTPS를 제공한다. 핸드셰이크와 닫기 경로를 평문과 같은 수준으로 견고하게 만든다.
- 선행: P3(타임아웃, watchdog, lingering close), P0-07(평문 소켓을 닫아 TLS 입출력을 푸는 동작 확인)
- 관련: [core-analysis.md](../research/core-analysis.md) §2.3 · [upstream-issues.md](../research/upstream-issues.md) §2 · [keep-alive.md](../research/keep-alive.md) §4.8~4.9 · 업스트림 #264, #299, #361, #455, #514, #516, #523, #524, #550, #584

## 작업

### P7-01 TLS 연구 문서 `[ ]`
- 산출물: `docs/research/tls.md`. 구현 전에 쓴다. core-analysis.md §2.3의 "TLS 상세는 별도 연구(예정)"를 이 문서 링크로 바꾼다.
- 회사 자료는 쓰지 않는다. Android 소스(AOSP, Conscrypt), JDK 문서, RFC, 실기기 측정만 쓴다(CLAUDE.md "연구 자료").
- 조사할 질문:

| 질문 | 왜 중요한가 |
|---|---|
| API 21~28 기본 프로바이더의 활성/지원 프로토콜과 cipher suite. TLS 1.3이 언제부터 기본인가 | 안전한 기본값의 상한과 하한 |
| API 21에서 `SSLParameters`로 할 수 있는 것(서버 cipher 순서 우선 `setUseCipherSuitesOrder`, SNI 등) | 설정 API 설계 |
| `SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean)`(Java 8)이 Android 어느 버전부터 있는가 | 평문 요청 감지(P7-03) 가능 여부 |
| 지원 keystore 형식(PKCS12, BKS, BKS-V1)과 버전별 차이. JKS 불가 여부 | 키 로딩 API(P7-04) |
| 기기에서 자체 서명 인증서를 만드는 방법(BouncyCastle 경량 API, Android KeyStore의 키를 `KeyManager`로 쓸 수 있는가와 그 API 레벨) | NAS의 첫 실행 시 인증서 생성. 라이브러리 밖이지만 가이드가 필요하다 |
| `SSLSocket`의 닫기 동작: `close_notify` 송신, `shutdownOutput` 미지원, 읽지 않은 입력이 있을 때 RST | lingering close의 TLS 경로(P7-05) |
| 다른 스레드에서 `SSLSocket.close()`를 부르면 막힌 `write`의 락을 기다리는가. 아래 평문 소켓을 닫으면 막힘 없이 풀리는가(JDK, Conscrypt) | watchdog이 막히지 않는 설계(P3-04) |
| 연결당 메모리: 레코드 버퍼, 세션 캐시 항목 크기, Conscrypt 네이티브 메모리 | P10 측정 계획 |
| Conscrypt를 앱에 번들하는 선택지의 크기와 이득(TLS 1.3, 최신 cipher) | 선택 안내. core는 의존하지 않는다 |
| `#584`의 `BAD_WRITE_RETRY` 반복 로그의 원인과 재현 | P7-06 |
| LAN NAS의 신뢰 모델(자체 서명 + 앱 핀닝, 사설 CA, 공개 도메인 + DNS-01) | 사용 가이드 |

- 완료 조건: 문서가 있고, 각 질문에 [검증] 또는 [판단] 표기와 출처가 있다. 아래 작업의 기본값이 이 문서 결과로 확정되었다.

### P7-02 TLS 설정 API `[ ]`
- `TlsConfig` 빌더 → `TinyHttpd.Builder.tls(TlsConfig)`. 구 `makeSecure`, `SecureServerSocketFactory`, `makeSSLSocketFactory`를 대체한다.
- 소켓 구성: `SSLServerSocket`을 쓰지 않는다. 평문 `ServerSocket`으로 받은 뒤 워커에서 `SSLSocketFactory.createSocket(socket, host, port, true)`로 감싸고, **`setUseClientMode(false)`** 를 건다(감싼 소켓의 기본은 클라이언트 모드다). 프로토콜, cipher, 클라이언트 인증 설정도 이 소켓에 건다. 이유는 셋이다.
  1. watchdog이 아래 평문 소켓을 닫아 막힘 없이 연결을 끊을 수 있다(P3-04).
  2. acceptor가 핸드셰이크와 무관해진다(P3-01 거절 경로).
  3. 평문 요청 감지(P7-03)의 여지가 생긴다.
- 항목:
  - 키 재료: `KeyManager[]`, 또는 `KeyStore` + 비밀번호
  - 프로토콜: 기본값은 `TLSv1.3`, `TLSv1.2` 중 **지원되는 것만**. 교집합이 비면 `start()`에서 실패한다
  - cipher suite: 기본 허용 목록은 TLS 1.3 suite(`TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384`, `TLS_CHACHA20_POLY1305_SHA256`)와 TLS 1.2의 ECDHE + AEAD(AES-GCM, ChaCha20-Poly1305)다. TLS 1.3 suite 이름에는 `ECDHE`가 없으므로 이름 패턴이 아니라 **명시적 목록**으로 둔다. 지원 목록과의 교집합만 켠다. 교집합이 비면 실패한다. 사용자가 목록을 직접 지정할 수도 있다(#361)
  - 클라이언트 인증: `NONE`(기본), `WANT`, `NEED`(#550)
  - `handshakeTimeout`(기본 10 s)
  - 세션 캐시 크기와 수명(`SSLSessionContext`)
- 구 코드처럼 `getSupportedProtocols()` 전부를 켜지 않는다. SSLv3, TLS 1.0, 1.1은 사용자가 명시적으로 지정해도 경고 로그를 남긴다.
- 완료 조건: TLS-01~04 통과.

### P7-03 핸드셰이크 `[ ]`
- 워커가 첫 요청을 읽기 전에 `startHandshake()`를 명시적으로 부른다. 연결 단계 `HANDSHAKING`을 추가한다. 헤더 타임아웃(P3-03)과 같이 워커가 `setSoTimeout(handshakeTimeout)`으로 마감을 지키고, watchdog은 마감을 크게 넘긴 연결의 평문 소켓을 닫는 보조 수단이다.
- 실패(`SSLException`)는 FINE 로그를 남기고 바로 닫는다. **TLS 스트림에 평문 HTTP 오류를 쓰지 않는다**(core-analysis.md §2.3의 구 동작).
- 평문 HTTP 요청이 TLS 포트로 온 경우(#264): P7-01 결과에 따라 정한다. 첫 바이트를 엿보고 소켓을 다시 감쌀 수 있으면 평문 `400 "use https"`를 보낸다. 없으면 그냥 닫는다.
- 클라이언트 인증서는 요청 속성 `tinyhttpd.tls.peerCertificates`(`X509Certificate[]`)로 제공한다. 프로토콜과 cipher는 `tinyhttpd.tls.protocol`, `tinyhttpd.tls.cipherSuite`다.
- 완료 조건: TLS-05~08 통과.

### P7-04 키 로딩 `[ ]`
- `TlsConfig.keyStore(InputStream, String type, char[] password)`와 `File` 버전. classpath 경로 방식(#523)은 두지 않는다.
- `type` 기본값은 `PKCS12`. Android 구버전용으로 `BKS`를 안내한다(P7-01 결과에 따름).
- 예외는 원인을 보존한다. 구 코드는 `new IOException(e.getMessage())`로 원인을 버렸다(#516).
- 테스트용 인증서: 테스트 실행 시 `keytool`로 PKCS12를 만들지 않는다. 미리 만든 테스트 전용 인증서(유효기간 100년, `CN=localhost`, SAN `localhost`, `127.0.0.1`)를 `core/src/testFixtures/resources`에 둔다. 구 `keystore.jks`는 P8-05에서 삭제한다.
- 완료 조건: TLS-09, TLS-10 통과.

### P7-05 TLS 닫기 경로 `[ ]`
- `SSLSocket`은 `shutdownOutput()`을 지원하지 않는다. 서버가 먼저 닫을 때는 이렇게 한다(P7-01 결과로 확정한다).
  1. flush
  2. 입력을 TLS 스트림으로 EOF 또는 `lingerTimeout`까지 읽어 버린다(바이트 상한 없음, P1-15와 같음)
  3. `close()` (`close_notify` 송신)
- 쓰기 실패 후에는 linger 없이 바로 닫는다.
- 완료 조건: TLS 연결에서 KA-14 시나리오 통과(TLS-11).

### P7-06 로그 폭주 방지 `[ ]`
- TLS 쓰기 실패(`SSLProtocolException`, `BAD_WRITE_RETRY`)가 나면 연결을 즉시 닫는다. 같은 연결에서 다시 쓰지 않는다(P2-05 규칙의 TLS 확인). 로그 수준은 FINE이다.
- 완료 조건: TLS-12(포트 스캔 흉내: 무작위 바이트, 핸드셰이크 도중 끊기 1천 회)에서 연결당 로그가 1줄 이하이고, 워커가 회전하지 않는다.

### P7-07 HTTP와 HTTPS 동시 제공 `[ ]`
- 서버 하나가 여러 리스너를 가진다: `builder.listen(8080)`, `builder.listen(8443, tlsConfig)`. 워커 풀, 레지스트리, watchdog, 한도는 공유한다(#455).
- 리스너마다 acceptor 스레드 하나.
- TLS 리스너에서 상한을 넘으면 응답 없이 닫는다(P3-01 거절 규칙).
- HTTP→HTTPS 리디렉션은 core가 하지 않는다. `isSecure()`를 보는 필터 예제로 안내한다.
- 완료 조건: TLS-13, TLS-14 통과.

### P7-08 TLS 회귀 스위트 `[ ]`
- KA/KB/RQ/RS/TH 테스트를 **TLS 위에서 한 번 더** 돌린다. JUnit `Parameterized`로 전송 방식(평문/TLS)을 매개변수로 받는다. `RawConnection`과 OkHttp 헬퍼에 TLS 옵션을 추가한다(P0-04 도구 확장).
- 평문에만 해당하는 테스트(`transferTo` 경로 등)는 제외 목록에 이유를 적는다.
- 완료 조건: TLS 매개변수로 위 스위트가 통과한다.

## 새 테스트 목록 (TLS)

| ID | 시나리오 | 기대 |
|---|---|---|
| TLS-01 | 기본 설정, OkHttp(TLS 1.2+) | 성공, 협상 프로토콜 ≥ TLS 1.2 |
| TLS-02 | 클라이언트가 TLS 1.0만 제시 | 핸드셰이크 실패, 서버 로그 FINE. JDK 17은 `jdk.tls.disabledAlgorithms`로 TLS 1.0/1.1을 이미 막으므로, 테스트 JVM에서 이 보안 속성의 TLSv1을 풀어 **TinyHttpd 설정이** 거부함을 확인한다 |
| TLS-03 | cipher 허용 목록과 클라이언트 목록의 교집합 없음 | 핸드셰이크 실패 |
| TLS-04 | 지원되지 않는 프로토콜만 설정 | `start()` 실패 |
| TLS-05 | TCP 연결만 하고 핸드셰이크 안 함 (timeout 500 ms) | 약 500 ms 뒤 닫힘 |
| TLS-06 | 평문 `GET /`을 TLS 포트로 | P7-03 결정대로(400 또는 닫힘), 평문 500 아님 |
| TLS-07 | `NEED` 클라이언트 인증, 인증서 없음 | 핸드셰이크 실패 |
| TLS-08 | `WANT` + 클라이언트 인증서 | 속성으로 인증서 조회 |
| TLS-09 | PKCS12 로딩 | 성공 |
| TLS-10 | 비밀번호 틀림 | 예외의 cause가 원래 예외 |
| TLS-11 | TLS에서 KA-14 시나리오 | 응답 정상 수신 |
| TLS-12 | 무작위 바이트·중도 절단 1천 회 | 회전 없음, 연결당 로그 ≤ 1줄 |
| TLS-13 | 8080 평문 + 8443 TLS 동시 | 둘 다 응답, 워커 풀 공유 |
| TLS-14 | TLS 리스너에서 `maxConnections`+1번째 연결 | 응답 없이 닫힘, acceptor가 막히지 않음(이어서 다른 연결이 즉시 접수됨) |

## 완료 조건 (단계)
- `docs/research/tls.md`가 있다. TLS 테스트와 TLS 매개변수 회귀 스위트가 통과한다.
