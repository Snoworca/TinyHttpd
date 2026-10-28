[English](en.md) | [한국어](ko.md) | [README](../../README.md)

# TinyHttpd 3.1.1 사용 가이드

로컬 HTTP 서비스를 만든 뒤 폼, 파일 전송, SSE, WebSocket, 세션, TLS를 추가합니다. JVM·Android 앱에 TinyHttpd를 탑재하려는 Java 개발자를 위한 문서입니다. 명령은 macOS/Linux의 Bash 기준이며 JDK 17과 인터넷 연결이 필요합니다. 라이브러리는 Java 8 바이트코드를 사용하고 Android API 21 시그니처로 API 호환성을 검사합니다. Android 실기기 검증은 아직 진행 전입니다.

TinyHttpd는 NanoHttpd의 개발 커밋 `efb2ebf`(`2.3.2-SNAPSHOT`, 2019-07-03)에서 출발해 독립적으로 개발을 이어갑니다. 엔진과 API를 재설계했으므로 기존 앱에서는 의존성만 교체하지 말고 코드를 이식해야 합니다.

## 1. 설치와 모듈 선택

Maven Central에서 groupId `com.snoworca`, 버전 `3.1.1`으로 제공합니다. 선택 모듈은 코어를 전이 의존성으로 가져옵니다. 이 예제는 5개 모듈을 모두 사용하며, 최소 서버에는 `tinyhttpd-core`만 있으면 됩니다.

| 모듈 | 기능 |
|---|---|
| `tinyhttpd-core` | HTTP 엔진, 핸들러·필터, multipart, 파일 응답, SSE, TLS |
| `tinyhttpd-router` | HTTP 메서드·경로 라우팅과 핸들러 마운트 |
| `tinyhttpd-webserver` | 정적 파일, 디렉터리 목록, CORS |
| `tinyhttpd-websocket` | WebSocket 핸드셰이크, 메시지, ping·종료 처리 |
| `tinyhttpd-session` | 메모리 세션과 CSRF 필터 |

라이브러리 체크아웃 옆에 별도 앱을 만듭니다. 기존 Gradle wrapper로 실행하며 TinyHttpd 소스를 먼저 빌드할 필요는 없습니다.

```bash
git clone https://github.com/Snoworca/TinyHttpd.git
cd TinyHttpd
mkdir -p ../tinyhttpd-guide/src/main/java
```

다음을 `../tinyhttpd-guide/build.gradle`에 저장합니다.

```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca:tinyhttpd-core:3.1.1'
    implementation 'com.snoworca:tinyhttpd-router:3.1.1'
    implementation 'com.snoworca:tinyhttpd-webserver:3.1.1'
    implementation 'com.snoworca:tinyhttpd-websocket:3.1.1'
    implementation 'com.snoworca:tinyhttpd-session:3.1.1'
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass = providers.gradleProperty('mainClass').getOrElse('GuideServer')
}
```

`../tinyhttpd-guide/settings.gradle`을 저장합니다.

```groovy
rootProject.name = 'tinyhttpd-guide'
```

Kotlin DSL이나 기존 Android 프로젝트 설정은 [README 의존성 예제](../../README.md#gradle-의존성-추가)를 참고하세요. 저장소를 settings에서 관리한다면 모듈 대신 그곳에 `mavenCentral()`을 넣습니다. 여기의 `application` 플러그인은 독립 JVM 예제용이며 Android 모듈에 적용하는 설정이 아닙니다.

## 2. 서비스 만들기

다음 전체 프로그램을 `../tinyhttpd-guide/src/main/java/GuideServer.java`에 저장합니다.

```java
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.http.*;
import com.snoworca.tinyhttpd.router.PathParams;
import com.snoworca.tinyhttpd.router.Router;
import com.snoworca.tinyhttpd.session.CsrfFilter;
import com.snoworca.tinyhttpd.session.Session;
import com.snoworca.tinyhttpd.session.SessionFilter;
import com.snoworca.tinyhttpd.webserver.StaticFileHandler;
import com.snoworca.tinyhttpd.websocket.*;
import java.io.*;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GuideServer {
    public static void main(String[] args) throws Exception {
        File publicDir = directory("public");
        File uploadDir = directory("uploads");
        Router router = new Router();

        router.get("/hello", (req, res) -> {
            String name = req.getQueryParameter("name");
            text(res, "Hello, " + (name == null ? "world" : name) + "!\n");
        });
        router.get("/users/{name}", (req, res) ->
                text(res, "User: " + PathParams.get(req, "name") + "\n"));
        router.post("/form", (req, res) -> {
            Map<String, List<String>> fields = req.readForm(16 * 1024);
            List<String> values = fields.get("name");
            text(res, "Form: " + (values == null ? "" : values.get(0)) + "\n");
        });
        router.mount("/files", new StaticFileHandler(publicDir)
                .directoryListing(true));
        router.post("/upload", (req, res) -> upload(req, res, uploadDir));
        router.get("/events", (req, res) -> {
            try (EventStream stream = res.startEventStream()) {
                stream.send("ready", "connected", "1");
            }
        });
        router.get("/session", (req, res) -> {
            Session session = Session.create(req, res);
            text(res, session.getCsrfToken() + "\n");
        });
        router.get("/ws", new WebSocketHandler(req -> new WebSocketAdapter() {
            @Override
            public void onText(WebSocket ws, String message) {
                try {
                    ws.sendText(message);
                } catch (IOException failure) {
                    ws.close(1011, "write failed");
                }
            }
        }));

        TinyHttpd server = TinyHttpd.builder()
                .bindAddress(InetAddress.getByName("127.0.0.1"))
                .port(8080)
                .daemon(false)
                .maxWorkers(16)
                .maxConnections(32)
                .maxRequestBodyBytes(10L * 1024 * 1024)
                .filter(new SessionFilter())
                .filter(new CsrfFilter())
                .filter((req, res, chain) -> {
                    res.setHeader("X-Guide", "TinyHttpd");
                    chain.doFilter(req, res);
                })
                .handler(router)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Listening on http://127.0.0.1:" + server.getPort());
    }

    private static void text(Response response, String value) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.getWriter().write(value);
    }

    private static File directory(String name) throws IOException {
        File dir = new File(name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory: " + dir);
        }
        return dir;
    }

    private static void upload(Request request, Response response, File directory)
            throws IOException, HttpException {
        File partial = null;
        boolean stored = false;
        MultipartLimits limits = MultipartLimits.of(10L * 1024 * 1024, 8)
                .withMaxPartBytes(8L * 1024 * 1024);
        try (MultipartReader parts = request.readMultipart(limits)) {
            Part part;
            while ((part = parts.next()) != null) {
                if (partial != null || !"file".equals(part.getName())
                        || part.getSubmittedFileName() == null) {
                    continue;
                }
                partial = File.createTempFile("upload-", ".part", directory);
                try (OutputStream output = new FileOutputStream(partial)) {
                    InputStream input = part.getInputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }
            }
            if (partial == null) {
                throw new HttpException(400, "file field required");
            }
            File destination = new File(directory, UUID.randomUUID() + ".bin");
            if (!partial.renameTo(destination)) {
                throw new IOException("Cannot finalize upload");
            }
            stored = true;
            response.setStatus(201);
            text(response, destination.getName() + "\n");
        } finally {
            if (!stored && partial != null && !partial.delete()) {
                partial.deleteOnExit();
            }
        }
    }
}
```

`TinyHttpd` 체크아웃에서 실행합니다.

```bash
./gradlew -p ../tinyhttpd-guide run
```

`Listening on http://127.0.0.1:8080`이 표시됩니다. 앱은 옆 프로젝트 디렉터리에 `public/`과 `uploads/`를 만듭니다. 이 터미널은 켜 두고 아래 명령은 `TinyHttpd` 체크아웃을 작업 디렉터리로 하는 다른 터미널에서 실행하세요. Ctrl+C를 누르면 종료 훅이 서버를 중지합니다.

예제는 루프백에만 바인딩합니다. 다른 장치에 제공하려면 적절한 인터페이스 주소에 바인딩하고 그 주소로 접속해야 합니다. `127.0.0.1`은 각 장치 자신을 가리킵니다. Host 허용 목록은 유지하고 사용자 지정 DNS 이름을 쓸 때는 `allowedHosts("nas.example")`로 등록하세요.

## 3. 요청·라우팅·응답 처리

```bash
curl --fail 'http://127.0.0.1:8080/hello?name=TinyHttpd'
# Hello, TinyHttpd!
curl --fail http://127.0.0.1:8080/users/alice
# User: alice
curl --fail -H 'Origin: http://127.0.0.1:8080' \
  --data-urlencode 'name=Alice' http://127.0.0.1:8080/form
# Form: Alice
```

`/hello`는 쿼리만 읽습니다. `/users/{name}`은 라우터의 디코딩된 경로 파라미터를 읽습니다. `/form`은 URL 인코딩 폼을 최대 16 KiB까지 메모리로 읽습니다. 이 예제는 `CsrfFilter`를 설치하므로 Origin 헤더가 필요합니다. 아래에는 세션·토큰을 쓰는 방법도 있습니다.

| API | 동작 |
|---|---|
| `getRequestURI()` | 쿼리를 제외한 원시 경로 |
| `getPath()` | 엄격하게 디코딩한 경로. STRICT는 실행 전, RAW는 이 접근자를 호출할 때 검증 |
| `getQueryParameter(name)` | 첫 쿼리 값. 바디는 읽지 않음 |
| `readForm(maxBytes)` | URL 인코딩 폼. 값 목록을 담은 맵 반환 |
| `getInputStream()` / `readMultipart(limits)` | 바디를 읽는 다른 방식 |
| `Response.getWriter()` | 콘텐츠 타입에 charset이 없으면 UTF-8 사용 |

한 요청에서는 바디 읽기 방식을 하나만 선택합니다. 응답도 writer와 output stream을 섞지 마세요. Content-Length·청크 프레이밍과 연결 재사용은 엔진이 관리하므로 Transfer-Encoding을 직접 지정하지 않습니다. 응답이 커밋되기 전에는 `throw new HttpException(status, message)`나 `response.sendError(...)`로 오류를 보낼 수 있습니다.

하나의 핸들러가 요청을 동시에 처리합니다. 공유 상태는 스레드 안전하게 관리하고 Request·Response는 소유 스레드에서 사용하세요. 필터가 `chain.doFilter(...)`를 호출하지 않으면 처리가 거기서 끝납니다. 라우트와 필터는 서비스를 시작하기 전에 등록하세요. 라우터는 HEAD를 GET으로 처리하고, 경로는 있지만 메서드가 다르면 Allow 헤더와 405를 반환합니다.

프레임워크 연동 시 `TinyHttpd.builder().requestTargetPolicy(com.snoworca.tinyhttpd.RequestTargetPolicy.RAW)`를 명시적으로 선택할 수 있습니다(3.1.1 이상). 이 모드의 `getRequestURI()`·`getQueryString()`은 `%2F`, `%00`, 점 세그먼트, 잘못된 이스케이프, 중복 슬래시를 포함한 원본 경로·쿼리를 보존하므로 프레임워크가 라우팅·검증을 담당합니다. 기본값은 `STRICT`입니다. `getPath()`는 여전히 엄격히 디코딩하며 잘못된 경로에는 unchecked `IllegalArgumentException`을 던집니다. 엔진은 응답 확정 전에는 400을 보내고, 이미 확정한 응답은 중단합니다. 기본 라우터·정적 파일의 경로 격리·WebSocket 경로 검증은 유지하며 HTTP 요청행·Host·바디 framing 검사도 두 모드 모두 적용합니다. 원본 경로는 라우팅·파일 접근 전에 검증하십시오.

## 4. 파일 다운로드와 업로드

서버가 디렉터리를 만든 뒤 실행합니다.

```bash
printf 'hello files\n' > ../tinyhttpd-guide/public/hello.txt
curl --fail http://127.0.0.1:8080/files/hello.txt
# hello files
curl --fail -i -H 'Range: bytes=0-3' http://127.0.0.1:8080/files/hello.txt
# HTTP/1.1 206 Partial Content; body: hell
curl --fail -H 'Origin: http://127.0.0.1:8080' \
  -F 'file=@../tinyhttpd-guide/public/hello.txt' http://127.0.0.1:8080/upload
# A generated UUID filename ending in .bin
```

`/files/`는 지정한 `public/` 트리만 제공합니다. 디렉터리 목록은 설명을 위해 명시적으로 켰으며 기본값은 꺼짐입니다. 파일은 Range·ETag·Last-Modified·조건부 응답을 지원합니다. 전송은 크기가 제한된 복사 버퍼를 사용하며 zero-copy를 보장하지 않습니다. 앱이 파일을 직접 선택할 때는 코어의 `FileResponses.serve(request, response, file, contentType)`를 쓰세요. 저수준 `sendFile`은 지정 영역을 보내지만 Range·조건부 요청 판단은 호출자가 해야 합니다.

`/upload`는 이름이 `file`인 첫 파일 파트만 저장하고 나머지는 무시하며, multipart의 종료 경계를 확인한 뒤 최종 파일로 바꿉니다. 최대 8개 파트, 파트당 8 MiB, 전체 10 MiB로 제한합니다. 서버가 UUID 파일명을 정하고 부분 데이터는 `.part`에 보관합니다. 실패 시 부분 파일을 지우고 즉시 삭제가 실패하면 프로세스 종료 시 삭제하도록 등록합니다. 업로드 파일은 `public/` 밖에 남아 자동으로 다운로드에 노출되지 않습니다. 예제에는 인증·할당량·비정상 종료 후 정리가 없으므로 업로드 서비스를 공개하기 전에 앱의 저장소 정책으로 추가하세요.

클라이언트가 보낸 파일명을 경로로 직접 사용하지 마세요. `getSubmittedFileName()`은 정리된 이름을 제공하지만 저장 위치는 앱이 선택해야 합니다.

## 5. SSE·WebSocket·세션

SSE 예제는 이벤트 하나를 보내고 의도적으로 연결을 끝냅니다.

```bash
curl --fail -N http://127.0.0.1:8080/events
# id: 1, event: ready, data: connected
```

계속 유지하려면 소유 핸들러에서 `EventStream.awaitClosed(heartbeatMillis)`로 대기하고 인터럽트를 처리하며, 끝날 때 스트림을 닫으세요. 다른 스레드가 보낸 이벤트는 대기열에 들어가며 소유 스레드가 전송해야 합니다. 대기열이 넘치면 느린 클라이언트의 스트림이 닫힙니다. 빠진 이벤트의 재전송은 앱이 담당합니다. SSE·업그레이드 연결은 일반 워커·연결과 별도 상한을 사용합니다.

`http://127.0.0.1:8080/files/`를 열고 같은 출처의 해당 페이지 개발자 콘솔에서 WebSocket echo를 확인합니다.

```javascript
const scheme = location.protocol === "https:" ? "wss:" : "ws:";
const socket = new WebSocket(scheme + "//" + location.host + "/ws");
socket.onopen = () => socket.send("hello");
socket.onmessage = event => console.log(event.data); // hello
```

WebSocket 콜백은 연결의 읽기 스레드에서 실행됩니다. 오래 걸리는 콜백은 읽기와 pong 응답을 지연시킵니다. 브라우저 검사를 피하려고 모든 Origin을 허용하지 말고 실제 사용할 출처를 지정하세요.

`SessionFilter` 다음에 `CsrfFilter`가 실행됩니다. 세션은 메모리에 저장하며 요청할 때만 생성합니다. `/session`은 현재 세션의 토큰을 반환하고 이후 토큰 검증 요청에는 같은 쿠키를 함께 보내야 합니다.

```bash
token=$(curl --fail -s -c /tmp/tinyhttpd-guide.cookies http://127.0.0.1:8080/session)
curl --fail -b /tmp/tinyhttpd-guide.cookies -H "X-CSRF-Token: $token" \
  --data-urlencode 'name=Alice' http://127.0.0.1:8080/form
```

CSRF 필터는 상태를 바꾸는 메서드에서 같은 출처·허용 출처의 Origin 또는 Referer를 검사합니다. 둘 다 없으면 세션의 X-CSRF-Token을 요구합니다. 인증 기능은 아닙니다. 로그인에 성공하면 `Session.rotate(request, response)`로 ID를 바꾸고 필요에 따라 인증된 세션으로 표시하며 로그아웃 때는 무효화하세요. 속성 저장소는 동시 접근을 지원하지만 여러 연산을 묶는 앱 로직에는 별도 동기화가 필요합니다. 기본 세션 저장소는 프로세스를 다시 시작하면 비워집니다.

## 6. 제한과 생명주기 설정

| 설정 | 라이브러리 기본값 | 예제 변경값 |
|---|---|---|
| 일반 워커 / 연결 | 64 / 128 | 16 / 32 |
| 요청 바디 크기 상한 | 상한 없음(`-1`) | 10 MiB |
| 헤더 크기 / 수 | 16 KiB / 100 | 변경 없음 |
| 헤더 / 바디 읽기 / 쓰기 타임아웃 | 10 / 30 / 30초 | 변경 없음 |
| 유휴 타임아웃 / 부하 시 | 15 / 2초 | 변경 없음 |
| 최대 SSE / 업그레이드 연결 | 64 / 16 | 변경 없음 |
| 정상 종료 대기 | 5초 | 변경 없음 |

빌더의 타임아웃 단위는 밀리초입니다. API에서 명시한 경우에만 `TinyHttpd.UNLIMITED`·`UNLIMITED_LONG`을 사용하세요. 0이나 음수가 모든 설정에서 제한 해제를 뜻하지는 않습니다. 버퍼는 실제 메모리를 할당하므로 크기에 무제한 상수를 넣지 마세요. 이 가이드의 폼·업로드 제한은 의도적으로 유한하게 설정했습니다.

빌더는 기본으로 데몬 스레드를 만듭니다. JVM 예제는 프로세스를 유지하기 위해 `daemon(false)`를 사용합니다. `start()`는 호출 스레드에서 바인딩하고 `stop()`은 설정된 정상 종료 시간을 기다려 호출 스레드를 막을 수 있습니다. UI 앱에서는 생명주기용 실행기에서 시작·종료하고 호스트가 끝날 때 stop을 호출하세요. 메모리·처리량은 설정과 플랫폼에 따라 달라집니다. [측정 자료](../research/baseline.md)를 참고하세요.

`Response.abort()`는 GET·HEAD를 포함한 활성 연결을 즉시 중단하며 오류 응답이나 청크 종료를 만들어 보내지 않습니다. 다른 스레드에서도 호출할 수 있고 막힌 쓰기를 깨웁니다. 중단 후 바디 스트림·Writer의 쓰기와 flush는 실패하며, 완료된 응답으로 재사용 연결의 다음 요청을 취소할 수는 없습니다. `ResponseWrapper`도 중단을 전달합니다. 과거 사용자 정의 Response의 기본 구현은 UnsupportedOperationException을 던집니다.

## 7. HTTPS 활성화

서버 TLS 키·인증서는 Maven 배포에 서명하는 PGP 키와 별개입니다. 로컬 테스트에서는 30일 유효한 자체 서명 인증서를 만들고 공개 인증서를 내보냅니다. `keytool`이 키스토어 암호를 물으면 입력하고, 환경 변수에도 같은 암호를 넣으세요. 비밀 키스토어는 Git에 넣지 마세요.

`../tinyhttpd-guide/src/main/java/TlsServer.java`를 저장합니다.

```java
import com.snoworca.tinyhttpd.TinyHttpd;
import com.snoworca.tinyhttpd.TlsConfig;
import java.io.File;
import java.net.InetAddress;

public final class TlsServer {
    public static void main(String[] args) throws Exception {
        String secret = System.getenv("TLS_KEYSTORE_PASSWORD");
        if (secret == null) {
            throw new IllegalStateException("Set TLS_KEYSTORE_PASSWORD");
        }
        TlsConfig tls = TlsConfig.builder()
                .keyStore(new File("server.p12"), "PKCS12", secret.toCharArray())
                .build();
        TinyHttpd server = TinyHttpd.builder()
                .bindAddress(InetAddress.getByName("127.0.0.1"))
                .port(8443)
                .tls(tls)
                .daemon(false)
                .handler((req, res) -> {
                    res.setContentType("text/plain; charset=UTF-8");
                    res.getWriter().write("TLS ready\n");
                })
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Listening on https://localhost:" + server.getPort());
    }
}
```

`TinyHttpd` 체크아웃에서 실행합니다.

```bash
cd ../tinyhttpd-guide
keytool -genkeypair -alias tinyhttpd -keyalg RSA -keysize 3072 \
  -storetype PKCS12 -keystore server.p12 -validity 30 \
  -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1'
keytool -exportcert -rfc -alias tinyhttpd -keystore server.p12 -file server-cert.pem
cd ../TinyHttpd
read -r -s -p 'Keystore password: ' TLS_KEYSTORE_PASSWORD
export TLS_KEYSTORE_PASSWORD
./gradlew -p ../tinyhttpd-guide -PmainClass=TlsServer run
```

체크아웃을 작업 디렉터리로 하는 다른 터미널에서 실행합니다.

```bash
curl --fail --cacert ../tinyhttpd-guide/server-cert.pem https://localhost:8443/
# TLS ready
```

TLS 기본값은 TLS 1.2/1.3과 AEAD 암호군을 허용하며 플랫폼이 지원하는 항목만 사용합니다. 배포 환경에 맞는 인증서·키 로딩·갱신을 구성하세요. 이 curl 명령은 테스트용 자체 서명 인증서를 명시적으로 신뢰하며, 해당 인증서가 공개적으로 신뢰되는 것은 아닙니다. 키스토어와 암호는 커밋하지 마세요.

## 8. Android에 탑재

`minSdk` 21 이상, Java 8 언어 지원, 앞의 Maven 의존성을 사용합니다. manifest 최상위 요소의 자식으로 다음 권한을 추가합니다.

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

라이브러리 자체는 Android Service를 구현하지 않습니다. 생명주기 소유자가 서버를 한 번 생성하고 UI 스레드 밖에서 시작·종료하며 사용이 끝날 때 해제하세요. UI 밖에서도 계속할 사용자에게 보이는 작업이라면 [foreground service 공식 지침](https://developer.android.com/develop/background-work/services/fgs)을 따라 대상 SDK·서비스 유형·권한·알림·시작 조건을 확인하세요. 실제 기기에서 측정해야 하며 API 시그니처 검사만으로 서비스 수명이나 메모리 동작까지 검증되지는 않습니다.

앱 전용 저장소나 사용자가 허용한 [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) 접근을 사용하세요. content URI는 파일 경로가 아닙니다. 제공자가 길이와 탐색 가능한 FileChannel을 제공할 수 있을 때만 `FileResource`로 연결하고, 그렇지 않으면 해당 제공자에 맞는 앱 핸들러를 선택하세요. `StaticFileHandler`는 파일시스템 루트를 대상으로 하며 임의의 content URI를 처리하지 않습니다.

Android 앱이 HTTP 클라이언트도 포함한다면 그 앱의 평문·TLS 정책은 서버 리스너와 별개입니다. [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)을 참고하세요. 루프백 바인딩은 다른 휴대폰·컴퓨터에서 접속할 수 있다는 뜻이 아닙니다. 네트워크·인터페이스 변경, 서비스 재시작 정책, 저장소 허용, 절전 상태 대응은 호스트 앱이 담당합니다.

## 9. NanoHttpd에서 이식

| NanoHttpd 사용 방식 | TinyHttpd 3.1.1 |
|---|---|
| 서버를 상속하고 `serve(...)` 재정의 | `Handler`와 필요 시 `Router`를 지정해 서버 구성 |
| `newFixedLengthResponse(...)` | 상태·타입을 지정하고 `Response.getWriter()` 또는 `getOutputStream()`에 쓰기 |
| `parseBody(...)` | `getInputStream()`, `readForm(limit)`, `readMultipart(limits)` 중 하나 사용 |
| 쿼리·바디 파라미터 통합 | 쿼리는 `getQueryParameter*()`, 폼은 바디를 명시적으로 파싱 |
| 요청 가로채기 | `Filter`를 등록하고 계속 처리하려면 `chain.doFilter(...)` 호출 |
| 파일 전체 버퍼링 | `StaticFileHandler`, `FileResponses.serve(...)`, `Response.sendFile(...)` 사용 |

코어는 Servlet 컨테이너가 아니며 servlet JAR에 의존하지 않습니다. 파라미터·문자셋 기본값·제공하지 않는 컨테이너 기능은 [Servlet API와 다른 점](../api/servlet-differences.md)을 참고하세요. 3.1.1은 TinyHttpd의 버전이며 원본 NanoHttpd의 릴리스라고 주장하지 않습니다.

## 10. 문제 해결과 재배포

| 증상 | 확인할 내용 |
|---|---|
| Address already in use | 기존 서버 종료 또는 포트 변경 |
| 다른 장치에서 연결 거부 | 루프백 바인딩, 도달 가능한 인터페이스 주소, 호스트 네트워크 규칙 |
| 사용자 지정 이름에서 HTTP 421 | `allowedHosts(...)` 등록 |
| POST에서 403 | 이 예제의 Origin·Referer 또는 세션 쿠키와 X-CSRF-Token |
| HTTP 413 | 전역 바디 상한과 폼·multipart 제한 |
| 디렉터리에서 403 | 인덱스 파일이 없고 디렉터리 목록이 꺼져 있는지 확인 |
| 독립 프로그램이 종료됨 | 데몬 스레드 기본값. 호스트 생명주기 유지 또는 `daemon(false)` |
| HTTPS 인증서 오류 | 인증서 신뢰, SAN 호스트명, 유효기간, 플랫폼 지원 |

TinyHttpd는 BSD 3-Clause를 사용합니다. [LICENSE](../../LICENSE), [NOTICE](../../NOTICE), [수정하지 않은 NanoHttpd 라이선스](../licenses/NanoHttpd-LICENSE.md)를 보존하세요. 라이브러리 JAR의 META-INF에도 포함되어 있습니다. 소스·바이너리를 재배포할 때 저작권 고지·조건·면책 조항을 유지하고 앱 배포 자료에도 포함하세요. NanoHttpd 원저작자에는 Paul S. Hawke, Jarno Elonen, Konstantinos Togias가 포함됩니다. 원본 조직·기여자의 보증이나 추천을 주장하지 않습니다.
