# TLS (P7-01)

- 범위: Android API 21 이상의 기본 프로바이더(Conscrypt 계열)와 JDK 8~17. 회사 자료는 쓰지 않았다. 공개 문서(Android `SSLSocket` 레퍼런스, AOSP/Conscrypt 소스, JDK 문서, RFC)와 이 저장소의 테스트만 근거로 한다.
- [검증]은 이 저장소 테스트(JDK 17)나 animal-sniffer(API 21 시그니처)로 확인한 것, 또는 공식 문서의 표를 그대로 옮긴 것이다. [판단]은 문서·소스를 읽고 내린 결론으로, 실기기 측정(P10)에서 다시 확인한다.

| 질문 | 답 | 근거 | 출처 |
|---|---|---|---|
| API 21~28 기본 프로토콜, TLS 1.3 시작 | TLS 1.1/1.2는 API 16부터 지원, 20부터 기본 활성. TLS 1.3은 API 29부터. SSLv3는 API 25까지 지원 | [검증] | Android `SSLSocket` 레퍼런스의 프로토콜 표 |
| 같은 범위의 cipher suite | ECDHE + AES-GCM은 API 20+, ECDHE + ChaCha20-Poly1305는 API 24+, TLS 1.3 suite는 API 29+ | [검증] | 같은 문서의 cipher suite 표 |
| API 21의 `SSLParameters` | `setUseCipherSuitesOrder`, SNI(`setServerNames`)는 API 24. API 21에서는 `SSLSocket`의 protocols, cipher suites, need/want client auth만 쓴다. 서버 순서를 못 정하므로 허용 목록에 강한 suite만 둔다 | [검증] | animal-sniffer `android-api-level-21`, Android API 레퍼런스 |
| `createSocket(Socket, InputStream, boolean)` | Java 8, Android API 24. API 21에서는 엿본 바이트를 되돌려 TLS로 감쌀 공개 API가 없다 | [검증] | 같은 근거 |
| keystore 형식 | PKCS12와 BKS(BouncyCastle)를 지원하고 JKS는 없다. JDK 12+(8u301+) `keytool`과 OpenSSL 3는 PKCS12를 PBES2/AES로 만든다. 구형 기기에서 못 읽으면 `openssl pkcs12 -legacy`나 BKS를 쓴다 | 형식: [검증], 구형 기기의 PBES2: [판단] | Android `KeyStore` 레퍼런스, JDK-8076190 |
| 기기에서 자체 서명 인증서 | Android에는 X.509를 만드는 공개 API가 없다. AndroidKeyStore `KeyPairGenerator`(API 18+, `KeyGenParameterSpec`은 23+)가 자체 서명 인증서와 키를 만든다. 이 키는 직접 구현한 `X509KeyManager`로 Conscrypt에 넘길 수 있다. 다른 방법은 앱에 BouncyCastle 경량 API를 번들하는 것이다. 둘 다 앱 쪽 일이다 | [판단] | Android Keystore 문서, Conscrypt `OpenSSLKey` 소스 |
| `SSLSocket` 닫기 | `close()`가 close_notify를 보낸다. `shutdownOutput()`은 Conscrypt에서 `UnsupportedOperationException`이고 JDK 11+는 지원한다(TLS half-close). 읽지 않은 입력이 남은 채 닫으면 평문과 같이 RST가 난다 | JDK: [검증] TLS-11, KA/KB TLS 스위트. Conscrypt: [판단] 소스 | Conscrypt `ConscryptFileDescriptorSocket`, RFC 8446 §6.1 |
| 다른 스레드의 `close()`와 막힌 write | `SSLSocket.close()`는 close_notify를 쓰려고 쓰기 락을 기다릴 수 있다. 아래 평문 소켓을 닫으면 막힌 TLS read/write가 바로 풀린다 | 평문 소켓 닫기: [검증] socket-io.md 2b/2c. 락 대기: [판단] | `SocketIoSpikeTest`, JDK `SSLSocketImpl` 소스 |
| 연결당 메모리 | 레코드 하나가 최대 16 KB 평문 + 오버헤드라 방향마다 약 16~17 KB 버퍼를 쓴다. 세션 캐시 항목은 수 KB다. Conscrypt는 네이티브 `SSL` 객체를 따로 쓴다 | [판단] | RFC 8446 §5.1, JDK `SSLSocketImpl`. P10에서 측정한다 |
| Conscrypt 번들 | `conscrypt-android`는 ABI당 수 MB의 네이티브 라이브러리를 추가한다. 대신 API 21~28에서도 TLS 1.3과 ChaCha20을 쓸 수 있다. `Security.insertProviderAt`로 등록하면 `TlsConfig`의 `SSLContext.getInstance("TLS")`가 그 프로바이더를 고른다. core는 이것에 의존하지 않는다 | [판단] | Conscrypt README |
| #584 `BAD_WRITE_RETRY` | OpenSSL은 실패한 `SSL_write`를 다른 버퍼로 다시 호출하면 이 오류를 낸다. 실패한 연결에 계속 써서 생긴 로그 폭주다. JDK 구현은 OpenSSL이 아니라서 JVM에서는 재현할 수 없다 | [판단] | OpenSSL `SSL_write` 문서, 업스트림 #584 |
| LAN NAS 신뢰 모델 | (1) 자체 서명 + 전용 앱의 인증서 핀닝, (2) 사설 CA를 기기에 설치(Android 7+ 앱은 network-security-config 없이 사용자 CA를 믿지 않는다), (3) 공개 도메인 + DNS-01 발급 + 내부 DNS. 브라우저 사용이 많으면 (3)이 가장 낫다 | [판단] | Android Network security configuration 문서, RFC 8555 |

## 정한 기본값

- 프로토콜: `TLSv1.3`, `TLSv1.2` 중 지원되는 것만 쓴다(API 21~28에서는 1.2만). SSLv3, TLS 1.0, 1.1을 지정하면 경고 로그를 남긴다.
- cipher suite: TLS 1.3 suite 3개와 ECDHE(ECDSA/RSA) + AES-GCM/ChaCha20-Poly1305를 명시적으로 나열하고 지원 목록과의 교집합만 켠다. 교집합이 비면 `start()`가 실패한다.
- 클라이언트 인증 `NONE`, `handshakeTimeout` 10 s, 세션 캐시 128개·3600 s(메모리 우선).
- 키 로딩: `PKCS12`가 기본이고 구형 기기에는 BKS를 쓴다. 예외는 원인을 보존한다.
- 평문 요청이 TLS 포트로 오면 그냥 닫는다(TLS-06). API 21에서 소켓을 다시 감쌀 방법이 없기 때문이다.
- 닫기(P7-05): flush 후 `shutdownOutput()`을 시도하고(JDK는 close_notify를 보냄, Android는 지원하지 않아 건너뜀), 입력을 EOF 또는 `lingerTimeout`까지 버린 뒤 `close()`한다. Android에서는 EOF까지 읽는 클라이언트가 최대 `lingerTimeout` 동안 기다릴 수 있다. 쓰기가 실패했으면 close_notify 없이 평문 소켓만 닫는다(P7-06). watchdog은 언제나 평문 소켓만 닫는다.
