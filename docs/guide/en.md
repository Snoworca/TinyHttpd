[English](en.md) | [한국어](ko.md) | [README](../../README.md)

# TinyHttpd 3.1.0 user guide

Build a local HTTP service, then add forms, file transfer, SSE, WebSocket, sessions, and TLS. This guide targets Java developers embedding TinyHttpd in JVM or Android applications. Commands use Bash on macOS/Linux; JDK 17 and an internet connection are required for the walkthrough. The library uses Java 8 bytecode and checks its platform APIs against Android API 21. Physical-device validation remains pending.

TinyHttpd independently continues NanoHttpd from development commit `efb2ebf` (`2.3.2-SNAPSHOT`, 2019-07-03). Its engine and API were redesigned; migrate existing application code rather than replacing a dependency without changes.

## 1. Install and choose modules

All coordinates use group `com.snoworca` and version `3.1.0` on Maven Central. Optional modules bring the core transitively. The walkthrough uses all five; a minimal server needs only `tinyhttpd-core`.

| Module | Provides |
|---|---|
| `tinyhttpd-core` | HTTP engine, handlers/filters, multipart, file responses, SSE, TLS |
| `tinyhttpd-router` | HTTP method/path routing and mounted handlers |
| `tinyhttpd-webserver` | Static files, directory listings, CORS |
| `tinyhttpd-websocket` | WebSocket handshake, messages, ping/close handling |
| `tinyhttpd-session` | In-memory sessions and CSRF filtering |

Create an application beside the library checkout. The existing Gradle wrapper runs it; you do not need to build TinyHttpd from source first.

```bash
git clone https://github.com/Snoworca/TinyHttpd.git
cd TinyHttpd
mkdir -p ../tinyhttpd-guide/src/main/java
```

Save the following as `../tinyhttpd-guide/build.gradle`:

```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca:tinyhttpd-core:3.1.0'
    implementation 'com.snoworca:tinyhttpd-router:3.1.0'
    implementation 'com.snoworca:tinyhttpd-webserver:3.1.0'
    implementation 'com.snoworca:tinyhttpd-websocket:3.1.0'
    implementation 'com.snoworca:tinyhttpd-session:3.1.0'
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass = providers.gradleProperty('mainClass').getOrElse('GuideServer')
}
```

Save `../tinyhttpd-guide/settings.gradle`:

```groovy
rootProject.name = 'tinyhttpd-guide'
```

For Kotlin DSL or an existing Android project, see the [README dependency examples](../../README.md#add-with-gradle). If repositories are managed in settings, put `mavenCentral()` there instead of in the module. The `application` plugin here is for the standalone JVM tutorial, not an Android module.

## 2. Build a service

Save this complete program as `../tinyhttpd-guide/src/main/java/GuideServer.java`:

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

From the `TinyHttpd` checkout, start it:

```bash
./gradlew -p ../tinyhttpd-guide run
```

Expect `Listening on http://127.0.0.1:8080`. The application creates `public/` and `uploads/` in the sibling application's directory. Keep this terminal open; use a second terminal in the `TinyHttpd` checkout for the commands below. Ctrl+C runs the shutdown hook and stops the server.

The example binds only to loopback. To serve other machines, configure an appropriate interface address and connect to that address; `127.0.0.1` is local to each device. Keep the Host allowlist enabled and use `allowedHosts("nas.example")` when clients use a custom DNS name.

## 3. Handle requests, routes, and responses

```bash
curl --fail 'http://127.0.0.1:8080/hello?name=TinyHttpd'
# Hello, TinyHttpd!
curl --fail http://127.0.0.1:8080/users/alice
# User: alice
curl --fail -H 'Origin: http://127.0.0.1:8080' \
  --data-urlencode 'name=Alice' http://127.0.0.1:8080/form
# Form: Alice
```

`/hello` reads only the query. `/users/{name}` reads the router's decoded path parameter. `/form` reads an URL-encoded body into memory with a 16 KiB limit. The Origin header is required here because this example installs `CsrfFilter`; the session/token alternative is shown below.

| API | Contract |
|---|---|
| `getRequestURI()` | Raw path without query |
| `getPath()` | Decoded path; invalid escapes, encoded slashes, and dot segments are rejected before the handler |
| `getQueryParameter(name)` | First query value; never reads the body |
| `readForm(maxBytes)` | URL-encoded form; returns a map of lists |
| `getInputStream()` / `readMultipart(limits)` | Alternative ways to consume the body |
| `Response.getWriter()` | UTF-8 if the content type does not specify a charset |

Choose one body reader per request. Choose either response writer or output stream; do not mix them. The engine owns Content-Length/chunked framing and connection reuse, so do not set Transfer-Encoding yourself. Use `throw new HttpException(status, message)` or `response.sendError(...)` for errors before the response commits.

One handler instance serves requests concurrently. Keep shared state thread safe and use each Request/Response on its owner thread. A filter stops the chain if it does not call `chain.doFilter(...)`. Register routes and filters before serving. The router handles HEAD via GET and answers 405 with Allow when the path exists for another method.

## 4. Download and upload files

Run these commands after the server has created its directories:

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

`/files/` exposes only the configured `public/` tree. Directory listing is explicitly enabled in this demonstration; its default is disabled. Files support Range, ETag, Last-Modified, and conditional responses. The transfer uses bounded copy buffers, not a promise of zero-copy I/O. Use core `FileResponses.serve(request, response, file, contentType)` when you select the file yourself; low-level `sendFile` sends a region but leaves Range/precondition decisions to the caller.

`/upload` stores the first file part named `file`, ignores later parts, and checks the multipart closing boundary before finalizing. It accepts at most eight parts, 8 MiB per part, and 10 MiB total. The server chooses a UUID filename, keeps partial bytes in `.part`, and removes a partial file on failure (with deletion-at-exit as a fallback). Uploaded files remain outside `public/`; the example does not expose them as downloads. It does not provide authentication, quotas, or crash-recovery cleanup; add these to your application's storage policy before exposing an upload service.

Never use a client-supplied filename directly as a path. `getSubmittedFileName()` provides a sanitized name for inspection, while your application still chooses the destination.

## 5. SSE, WebSocket, and sessions

The SSE endpoint sends one event and closes intentionally:

```bash
curl --fail -N http://127.0.0.1:8080/events
# id: 1, event: ready, data: connected
```

For a persistent stream, keep its owner handler alive with `EventStream.awaitClosed(heartbeatMillis)`, handle interruption, and close it when finished. Other threads' sends queue events; the owner must drain them. Queue overflow closes a slow client; missed-event replay belongs to the application. SSE and upgraded connections have separate limits from ordinary workers/connections.

Open `http://127.0.0.1:8080/files/` and run this in that page's browser console to test the echo WebSocket from the same origin:

```javascript
const scheme = location.protocol === "https:" ? "wss:" : "ws:";
const socket = new WebSocket(scheme + "//" + location.host + "/ws");
socket.onopen = () => socket.send("hello");
socket.onmessage = event => console.log(event.data); // hello
```

WebSocket callbacks run on the connection's reading thread. Slow callbacks delay reads and pong replies. Do not accept every Origin just to bypass browser checks; configure the intended allowed origins.

`SessionFilter` runs before `CsrfFilter`. Sessions are in memory and created only when requested. This endpoint returns a token for the current session; the cookie must accompany later token-authenticated requests:

```bash
token=$(curl --fail -s -c /tmp/tinyhttpd-guide.cookies http://127.0.0.1:8080/session)
curl --fail -b /tmp/tinyhttpd-guide.cookies -H "X-CSRF-Token: $token" \
  --data-urlencode 'name=Alice' http://127.0.0.1:8080/form
```

The CSRF filter checks state-changing methods using a same/allowed Origin or Referer; when neither is present it requires the session's X-CSRF-Token. It is not authentication. After a successful login, use `Session.rotate(request, response)` and mark the session authenticated as appropriate; invalidate it on logout. Attribute storage is concurrent, but compound application operations still need synchronization. Restarting the process clears the default session store.

## 6. Configure limits and lifetime

| Setting | Library default | Tutorial override |
|---|---|---|
| Ordinary workers / connections | 64 / 128 | 16 / 32 |
| Request body size cap | No cap (`-1`) | 10 MiB |
| Header bytes / count | 16 KiB / 100 | Unchanged |
| Header / body-read / write timeout | 10 / 30 / 30 seconds | Unchanged |
| Idle timeout / under load | 15 / 2 seconds | Unchanged |
| Maximum SSE / upgraded connections | 64 / 16 | Unchanged |
| Graceful shutdown | 5 seconds | Unchanged |

Builder timeout setters use milliseconds. Use `TinyHttpd.UNLIMITED` or `UNLIMITED_LONG` only where the API declares them; zero and negative values are not a general way to disable limits. Buffers allocate memory, so do not use an unlimited constant as a buffer size. The upload/form limits in this guide intentionally remain finite.

The builder normally creates daemon threads. This JVM program uses `daemon(false)` to keep the process alive. `start()` binds on the calling thread; `stop()` waits for the configured shutdown grace and may block. Start/stop on a lifecycle executor in UI applications and call stop when the host is finished. Memory consumption and throughput depend on configuration and platform; see [measurement notes](../research/baseline.md).

`Response.abort()` cancels the active transport immediately, including GET and HEAD, without fabricating an error response or completing a chunked body. It is thread safe and can wake a blocked writer. Writes and flushes through either the body stream or Writer fail after cancellation; a completed response cannot cancel a subsequent request on a reused connection. `ResponseWrapper` forwards cancellation. The default on a legacy custom Response throws UnsupportedOperationException.

## 7. Enable HTTPS

Use a server TLS key/certificate, separate from the PGP key used to sign Maven releases. For a local-only test, create a 30-day self-signed certificate and export its public certificate. `keytool` prompts for a keystore password; reuse it for the environment variable. Keep the private keystore out of Git.

Save `../tinyhttpd-guide/src/main/java/TlsServer.java`:

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

From the `TinyHttpd` checkout, run:

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

In another terminal in the checkout:

```bash
curl --fail --cacert ../tinyhttpd-guide/server-cert.pem https://localhost:8443/
# TLS ready
```

TLS defaults allow TLS 1.2/1.3 and configured AEAD suites intersected with the platform's support. Configure real certificates, key loading, and renewal for your deployment. The self-signed test certificate is trusted explicitly by this curl command; it is not publicly trusted. Do not commit keystores or passwords.

## 8. Embed in Android

Use `minSdk` 21 or later, Java 8 language support, and the Maven dependencies above. Add this as a child of the manifest's root element:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

The server library does not implement an Android Service. Let a lifecycle owner create it once, perform start/stop off the UI thread, and release it when finished. For user-visible work that must continue outside the UI, follow Android's [foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs), including the rules for your target SDK, service type, permissions, notification, and start conditions. Measure on your actual devices; API-signature checks do not verify device lifetime or memory behavior.

Use app-owned storage or user-granted [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) access. A content URI is not a filesystem path. Adapt `FileResource` with a known length and a seekable FileChannel only when the provider can supply them; otherwise choose an application handler suitable for that provider. `StaticFileHandler` works with filesystem roots, not arbitrary content URIs.

If an Android app is also an HTTP client, its cleartext/TLS policy is separate from the server listener; see [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config). Binding to loopback does not make the service reachable from another phone or computer. Network/interface changes, service restart policy, storage grants, and wake behavior belong to the host app.

## 9. Migrate from NanoHttpd

| NanoHttpd pattern | TinyHttpd 3.1.0 |
|---|---|
| Subclass the server and override `serve(...)` | Build `TinyHttpd` with a `Handler`, optionally a `Router` |
| `newFixedLengthResponse(...)` | Set status/type, then write to `Response.getWriter()` or `getOutputStream()` |
| `parseBody(...)` | Read one of `getInputStream()`, `readForm(limit)`, or `readMultipart(limits)` |
| Combined query/body parameters | `getQueryParameter*()` for the query; explicit body parsing for forms |
| Intercept requests | Register `Filter` implementations and call `chain.doFilter(...)` to continue |
| Whole-file buffering | `StaticFileHandler`, `FileResponses.serve(...)`, or `Response.sendFile(...)` |

The core is not a Servlet container and does not require a servlet JAR. See [Servlet API differences](../api/servlet-differences.en.md) for request parameters, charset defaults, and unavailable container features. Version 3.1.0 belongs to TinyHttpd and does not claim to be an upstream NanoHttpd release.

## 10. Troubleshoot and redistribute

| Symptom | Check |
|---|---|
| Address already in use | Stop the old server or choose another port |
| Connection refused from another device | Loopback binding, reachable interface address, and host network rules |
| HTTP 421 with a custom name | Add it to `allowedHosts(...)` |
| POST returns 403 | Origin/Referer or session cookie plus X-CSRF-Token in this example |
| HTTP 413 | Global body cap and form/multipart limits |
| Directory returns 403 | No index file and directory listing is disabled |
| Standalone program exits | Daemon threads; keep a host lifecycle alive or use `daemon(false)` |
| HTTPS certificate error | Certificate trust, SAN hostname, validity, and provider support |

TinyHttpd uses BSD 3-Clause. Preserve [LICENSE](../../LICENSE), [NOTICE](../../NOTICE), and the [unmodified NanoHttpd license](../licenses/NanoHttpd-LICENSE.md). Library JARs contain these notices under META-INF. Preserve copyright notices, conditions, and disclaimers when redistributing sources or binaries; include them in application distribution materials. NanoHttpd authors include Paul S. Hawke, Jarno Elonen, and Konstantinos Togias. No upstream endorsement is claimed.
