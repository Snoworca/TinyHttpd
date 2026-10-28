[한국어](#한국어) | [English](#english)

<p align="center">
  <img src="docs/logo-v2.png" alt="TinyHttpd — a compact server core with flowing data" width="720">
</p>

# TinyHttpd

<a id="english"></a>

## English

A lightweight embedded HTTP server for Java and Android, built to keep memory use under control and stream large files reliably. Use it for local services, file servers, embedded applications, and home NAS projects—including those running on older Android devices.

**Based on [NanoHttpd](https://github.com/NanoHttpd/nanohttpd), continued and improved independently.** TinyHttpd was forked from commit [`efb2ebf`](https://github.com/NanoHttpd/nanohttpd/commit/efb2ebf85a2b06f7c508aba9eaad5377e3a01e81), dated July 3, 2019. It carries forward NanoHttpd's lightweight approach with a rewritten HTTP engine and redesigned public API. It uses the `com.snoworca.tinyhttpd` package and is not a drop-in replacement for NanoHttpd.

### Features

- **No external runtime dependencies.** The core uses Java platform APIs; optional modules depend on the core. Add only the modules you need.
- **Controlled resource use.** Workers are created on demand, connection and worker counts are bounded, and reusable buffers reduce repeated allocations. Configurable timeouts and graceful shutdown control connection lifetimes.
- **Streaming file I/O.** Read multipart uploads incrementally and send files through bounded copy buffers. File responses support byte ranges, ETags, and conditional requests without loading entire files into memory.
- **A small, explicit API.** Compose `Handler` and `Filter` implementations around `Request` and `Response`. Query parameters, form bodies, and multipart bodies are read separately. The core does not require the Servlet API.
- **HTTP and live connections.** HTTP/1.1 keep-alive, chunked bodies, SSE, optional WebSocket support, and configurable TLS. SSE and upgraded connections have separate connection budgets.
- **Compatibility and input checks.** Java 8 bytecode and Android 5.0/API 21 API checks, strict request parsing, configurable request limits, and a Host allowlist enabled by default.

The current version is `3.1.1`. Android API compatibility is checked during the build; the physical-device validation plan remains open. Memory targets and benchmark results depend on the workload and platform. See the [measurement notes](docs/research/baseline.md) and [Android verification plan](docs/next/p10-android-verification.md).

Framework adapters can opt into original request-target handling with `.requestTargetPolicy(RequestTargetPolicy.RAW)` (3.1.1+). The default remains `STRICT`; decoded `getPath()`, static-file and WebSocket path validation remain strict. See the [request handling guide](docs/guide/en.md#3-handle-requests-routes-and-responses).

### Modules

| Module | Directory | Purpose |
|---|---|---|
| `tinyhttpd-core` | [core/](core/) | HTTP engine, handlers, filters, streaming bodies, file responses, SSE, TLS |
| `tinyhttpd-webserver` | [webserver/](webserver/) | Static files, directory listings, CORS, command-line file server |
| `tinyhttpd-websocket` | [websocket/](websocket/) | WebSocket handlers and connections |
| `tinyhttpd-router` | [router/](router/) | Method/path routing and mounted handlers |
| `tinyhttpd-session` | [session/](session/) | Optional in-memory sessions and CSRF filter |

### Add with Gradle

All five modules are available from Maven Central. Add the core dependency and keep only the optional modules your application needs. Gradle resolves each optional module's core dependency automatically.

**Kotlin DSL — `build.gradle.kts`:**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.snoworca:tinyhttpd-core:3.1.1")

    // Optional modules
    implementation("com.snoworca:tinyhttpd-webserver:3.1.1")
    implementation("com.snoworca:tinyhttpd-websocket:3.1.1")
    implementation("com.snoworca:tinyhttpd-router:3.1.1")
    implementation("com.snoworca:tinyhttpd-session:3.1.1")
}
```

**Groovy DSL — `build.gradle`:**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca:tinyhttpd-core:3.1.1'

    // Optional modules
    implementation 'com.snoworca:tinyhttpd-webserver:3.1.1'
    implementation 'com.snoworca:tinyhttpd-websocket:3.1.1'
    implementation 'com.snoworca:tinyhttpd-router:3.1.1'
    implementation 'com.snoworca:tinyhttpd-session:3.1.1'
}
```

These blocks belong in your application's module build file. If your project manages repositories centrally, put `mavenCentral()` in `settings.gradle(.kts)` under `dependencyResolutionManagement.repositories` instead of adding a module-level `repositories` block. For Android, set `minSdk` to 21 or later and enable Java 8 language support.

### Downloads

Download binary, sources, and Javadoc JARs from [GitHub Releases](https://github.com/Snoworca/TinyHttpd/releases/tag/v3.1.1). Each optional module requires the core JAR. See the [release notes](CHANGELOG.md) for changes and migration notes.

### Quick tutorial

Use **JDK 17** to build and run this tutorial, matching CI. The libraries compile with `--release 8`; animal-sniffer also checks their main sources against Android API 21. Use the included Gradle wrapper. These commands target macOS/Linux shells and require `curl` for the request check.

**1. Build from source.**

```bash
git clone https://github.com/Snoworca/TinyHttpd.git
cd TinyHttpd
./gradlew build
```

The commands below use locally built JARs. Applications can instead use the Maven Central dependencies shown above.

**2. Save this as `HelloServer.java` in the repository root.**

```java
import com.snoworca.tinyhttpd.TinyHttpd;
import java.net.InetAddress;

public final class HelloServer {
    public static void main(String[] args) throws Exception {
        TinyHttpd server = TinyHttpd.builder()
                .bindAddress(InetAddress.getByName("127.0.0.1"))
                .port(8080)
                .daemon(false)
                .handler((request, response) -> {
                    response.setContentType("text/plain; charset=UTF-8");
                    response.getWriter().write("Hello, TinyHttpd!\n");
                })
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Listening on http://127.0.0.1:" + server.getPort());
    }
}
```

**3. Compile and start it.**

```bash
javac --release 8 -cp core/build/libs/tinyhttpd-core-3.1.1.jar HelloServer.java
java -cp ".:core/build/libs/tinyhttpd-core-3.1.1.jar" HelloServer
```

In another terminal:

```bash
curl --fail http://127.0.0.1:8080/
# Hello, TinyHttpd!
```

Press Ctrl+C in the server terminal to stop it. `.daemon(false)` keeps this standalone process alive; the builder normally creates daemon threads. Inside an application, tie the server to the application's lifecycle and call `stop()` when finished. A shared handler can receive concurrent requests, so keep shared state thread safe.

**4. Try the static file server.** Stop the hello server first, then run:

```bash
java -cp "core/build/libs/tinyhttpd-core-3.1.1.jar:webserver/build/libs/tinyhttpd-webserver-3.1.1.jar" \
  com.snoworca.tinyhttpd.webserver.SimpleWebServer \
  -h 127.0.0.1 -p 8080 -d docs --listing
```

Open `http://127.0.0.1:8080/` to browse `docs/`. Directory listing is opt-in via `--listing`.

### Selected defaults

| Setting | Default |
|---|---|
| Input / output buffer | 8 KiB / 8 KiB |
| Maximum workers / ordinary connections | 64 / 128 |
| Maximum ordinary connections per address | 32 |
| Idle timeout / idle timeout under load | 15 s / 2 s |
| Header / body read / write timeout | 10 s / 30 s / 30 s |
| Maximum header bytes / header count | 16 KiB / 100 |
| Request body size limit | No size cap by default; set `maxRequestBodyBytes(...)` for your application |
| Maximum SSE / upgraded connections | 64 / 16 |
| Graceful shutdown wait | 5 s |

Configure these through [`TinyHttpd.Builder`](core/src/main/java/com/snoworca/tinyhttpd/TinyHttpd.java). TLS is enabled explicitly with a [`TlsConfig`](core/src/main/java/com/snoworca/tinyhttpd/TlsConfig.java); its default protocol allowlist is TLS 1.2/1.3, limited to what the platform supports. Custom DNS names must be added with `allowedHosts(...)`; IP literals and `localhost` are allowed by default.

### Android integration

Use API 21 or later and declare `android.permission.INTERNET` in the host app. Start and stop the server off the UI thread and tie it to the application's lifecycle. A long-running server needs an Android background-execution strategy; file access depends on the Android version and storage permissions. The host app manages services, storage access, and network changes.

### Troubleshooting

| Symptom | What to do |
|---|---|
| `Address already in use` | Stop the process using port 8080 or choose another port with `.port(...)` / `-p`. |
| Standalone app exits immediately | Use `.daemon(false)` or keep an application-owned thread alive. |
| Custom hostname receives HTTP 421 | Add the hostname with `allowedHosts("nas.example")`. |
| `javac` cannot resolve `TinyHttpd` | Run the build first and check the JAR path and version in the classpath. |

### Documentation

- [English user guide](docs/guide/en.md) · [한국어 사용 가이드](docs/guide/ko.md)
- [Guide language index](docs/guide/README.md)
- [Servlet API differences (English)](docs/api/servlet-differences.en.md)
- [API differences from Servlet](docs/api/servlet-differences.md)
- [Development roadmap](docs/next/README.md)
- [Research and measurements](docs/research/)
- [Public API source](core/src/main/java/com/snoworca/tinyhttpd/http/)

### License and attribution

TinyHttpd is distributed under the **BSD 3-Clause License**. See [LICENSE](LICENSE), [NOTICE](NOTICE), and the [unmodified NanoHttpd license](docs/licenses/NanoHttpd-LICENSE.md) preserved from the fork-point commit.

Copyright (c) 2026, Snoworca. NanoHttpd copyright: (c) 2012–2013 Paul S. Hawke; 2001, 2005–2013 Jarno Elonen; 2010 Konstantinos Togias. The exact upstream notice is retained in the linked license file.

For source redistribution, retain the copyright notices, license conditions, and disclaimers. For JAR/APK or other binary redistribution, reproduce them in the documentation and/or other materials supplied with the distribution—for example, an app's open-source licenses screen or bundled license files. Include both license texts and the attribution notice; a project link alone does not reproduce the required notices. Library JARs include these notices under `META-INF/`; preserve them when repackaging and include them in your application's distribution materials. TinyHttpd does not claim endorsement by the NanoHttpd organization or its contributors.

---

<a id="한국어"></a>

## 한국어

TinyHttpd는 메모리 사용량 관리와 안정적인 대용량 파일 스트리밍을 중시하는 Java·Android용 경량 임베디드 HTTP 서버입니다. 로컬 서비스, 파일 서버, 임베디드 애플리케이션, 홈 NAS에 사용할 수 있으며 구형 안드로이드 기기의 활용도 목표로 합니다.

**[NanoHttpd](https://github.com/NanoHttpd/nanohttpd)를 기반으로 독립적으로 개발을 이어 개선한 프로젝트입니다.** 2019년 7월 3일의 [`efb2ebf`](https://github.com/NanoHttpd/nanohttpd/commit/efb2ebf85a2b06f7c508aba9eaad5377e3a01e81) 커밋에서 포크했습니다. 가벼운 임베디드 서버라는 방향을 이어받으면서 HTTP 엔진과 공개 API를 재설계했습니다. 패키지는 `com.snoworca.tinyhttpd`이며, 기존 NanoHttpd API와 직접 호환되지는 않습니다.

### 주요 특징

- **외부 런타임 의존성 없음.** 코어는 Java 플랫폼 API를 사용하고 선택 모듈은 코어에 의존합니다. 필요한 모듈만 추가할 수 있습니다.
- **자원 사용량 관리.** 필요한 시점에 워커를 만들고 연결·워커 수에 상한을 둡니다. 버퍼 재사용, 타임아웃, 정상 종료 대기를 통해 자원을 관리합니다.
- **스트리밍 파일 입출력.** multipart 업로드를 순차적으로 읽고, 크기가 제한된 복사 버퍼로 파일을 전송합니다. 파일 전체를 메모리에 올리지 않으며 바이트 범위 요청, ETag, 조건부 요청을 지원합니다.
- **작고 명시적인 API.** `Request`·`Response`를 중심으로 `Handler`와 `Filter`를 조합합니다. 쿼리·폼·multipart를 구분해 읽으며 코어는 Servlet API에 의존하지 않습니다.
- **HTTP와 실시간 연결.** HTTP/1.1 keep-alive, 청크 전송, SSE, 선택 모듈의 WebSocket, 설정 가능한 TLS를 제공합니다. SSE와 업그레이드 연결은 별도의 연결 예산으로 관리합니다.
- **호환성과 입력 검사.** Java 8 바이트코드와 Android 5.0/API 21 API 검사를 적용합니다. 엄격한 요청 파싱, 설정 가능한 요청 제한, 기본 활성화된 Host 허용 목록을 제공합니다.

현재 버전은 `3.1.1`입니다. Android API 호환성은 빌드에서 검사하며, 실기기 검증 계획은 아직 진행 전입니다. 메모리 목표치와 벤치마크 결과는 환경·작업 부하에 따라 달라집니다. [측정 자료](docs/research/baseline.md)와 [Android 검증 계획](docs/next/p10-android-verification.md)을 참고하세요.

프레임워크 어댑터는 `.requestTargetPolicy(RequestTargetPolicy.RAW)`로 원본 요청 경로를 직접 처리할 수 있습니다(3.1.1 이상). 기본값 `STRICT`, 디코딩 접근자·정적 파일·WebSocket의 엄격한 경로 검증은 유지합니다. [요청 처리 가이드](docs/guide/ko.md#3-요청라우팅응답-처리)를 참고하세요.

### 모듈

| 모듈 | 디렉터리 | 용도 |
|---|---|---|
| `tinyhttpd-core` | [core/](core/) | HTTP 엔진, 핸들러, 필터, 스트리밍 바디, 파일 응답, SSE, TLS |
| `tinyhttpd-webserver` | [webserver/](webserver/) | 정적 파일, 디렉터리 목록, CORS, 명령행 파일 서버 |
| `tinyhttpd-websocket` | [websocket/](websocket/) | WebSocket 핸들러와 연결 |
| `tinyhttpd-router` | [router/](router/) | HTTP 메서드·경로 라우팅, 핸들러 마운트 |
| `tinyhttpd-session` | [session/](session/) | 선택형 메모리 세션과 CSRF 필터 |

### Gradle 의존성 추가

5개 모듈 모두 Maven Central에서 받을 수 있습니다. 코어 의존성을 추가하고 필요한 선택 모듈만 남기세요. 선택 모듈의 코어 의존성은 Gradle이 자동으로 가져옵니다.

**Kotlin DSL — `build.gradle.kts`:**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.snoworca:tinyhttpd-core:3.1.1")

    // Optional modules
    implementation("com.snoworca:tinyhttpd-webserver:3.1.1")
    implementation("com.snoworca:tinyhttpd-websocket:3.1.1")
    implementation("com.snoworca:tinyhttpd-router:3.1.1")
    implementation("com.snoworca:tinyhttpd-session:3.1.1")
}
```

**Groovy DSL — `build.gradle`:**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.snoworca:tinyhttpd-core:3.1.1'

    // Optional modules
    implementation 'com.snoworca:tinyhttpd-webserver:3.1.1'
    implementation 'com.snoworca:tinyhttpd-websocket:3.1.1'
    implementation 'com.snoworca:tinyhttpd-router:3.1.1'
    implementation 'com.snoworca:tinyhttpd-session:3.1.1'
}
```

위 설정은 사용하는 앱의 모듈 빌드 파일에 추가합니다. 저장소를 중앙에서 관리하는 프로젝트라면 모듈에 `repositories` 블록을 추가하는 대신 `settings.gradle(.kts)`의 `dependencyResolutionManagement.repositories`에 `mavenCentral()`을 넣으세요. Android에서는 `minSdk`를 21 이상으로 설정하고 Java 8 언어 지원을 활성화하세요.

### 다운로드

[GitHub Releases](https://github.com/Snoworca/TinyHttpd/releases/tag/v3.1.1)에서 바이너리·소스·Javadoc JAR를 받을 수 있습니다. 선택 모듈을 사용할 때는 코어 JAR도 필요합니다. 변경 사항과 이식 안내는 [릴리스 노트](CHANGELOG.md)를 참고하세요.

### 간단한 튜토리얼

CI와 같은 **JDK 17**로 빌드하고 예제를 실행합니다. 라이브러리는 `--release 8`로 컴파일하며 animal-sniffer가 main 소스의 Android API 21 호환성을 검사합니다. 포함된 Gradle wrapper를 사용하세요. 아래 명령은 macOS/Linux 셸 기준이며 요청 확인에 `curl`을 사용합니다.

**1. 소스를 받아 빌드합니다.**

```bash
git clone https://github.com/Snoworca/TinyHttpd.git
cd TinyHttpd
./gradlew build
```

아래 예제는 로컬에서 빌드한 JAR를 사용합니다. 앱에서는 위의 Maven Central 의존성으로 대신 사용할 수 있습니다.

**2. 저장소 루트에 `HelloServer.java`를 만듭니다.**

```java
import com.snoworca.tinyhttpd.TinyHttpd;
import java.net.InetAddress;

public final class HelloServer {
    public static void main(String[] args) throws Exception {
        TinyHttpd server = TinyHttpd.builder()
                .bindAddress(InetAddress.getByName("127.0.0.1"))
                .port(8080)
                .daemon(false)
                .handler((request, response) -> {
                    response.setContentType("text/plain; charset=UTF-8");
                    response.getWriter().write("Hello, TinyHttpd!\n");
                })
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Listening on http://127.0.0.1:" + server.getPort());
    }
}
```

**3. 컴파일하고 실행합니다.**

```bash
javac --release 8 -cp core/build/libs/tinyhttpd-core-3.1.1.jar HelloServer.java
java -cp ".:core/build/libs/tinyhttpd-core-3.1.1.jar" HelloServer
```

다른 터미널에서 요청합니다.

```bash
curl --fail http://127.0.0.1:8080/
# Hello, TinyHttpd!
```

서버 터미널에서 Ctrl+C로 종료합니다. `.daemon(false)`는 독립 실행 프로그램이 바로 끝나지 않도록 합니다. 빌더의 기본값은 데몬 스레드입니다. 앱에 탑재할 때는 앱 생명주기에 맞춰 서버를 관리하고 사용이 끝나면 `stop()`을 호출하세요. 하나의 핸들러가 요청을 동시에 처리하므로 공유 상태는 스레드 안전하게 관리해야 합니다.

**4. 정적 파일 서버를 실행합니다.** 앞의 서버를 종료한 뒤 실행하세요.

```bash
java -cp "core/build/libs/tinyhttpd-core-3.1.1.jar:webserver/build/libs/tinyhttpd-webserver-3.1.1.jar" \
  com.snoworca.tinyhttpd.webserver.SimpleWebServer \
  -h 127.0.0.1 -p 8080 -d docs --listing
```

`http://127.0.0.1:8080/`에 접속하면 `docs/`를 탐색할 수 있습니다. 디렉터리 목록은 `--listing`을 지정할 때만 활성화됩니다.

### 주요 기본값

| 설정 | 기본값 |
|---|---|
| 입력 / 출력 버퍼 | 8 KiB / 8 KiB |
| 최대 워커 / 일반 연결 | 64 / 128 |
| 주소당 최대 일반 연결 | 32 |
| 유휴 타임아웃 / 부하 시 유휴 타임아웃 | 15초 / 2초 |
| 헤더 / 바디 읽기 / 쓰기 타임아웃 | 10초 / 30초 / 30초 |
| 최대 헤더 크기 / 헤더 수 | 16 KiB / 100개 |
| 요청 바디 크기 제한 | 기본 크기 상한 없음. 용도에 맞게 `maxRequestBodyBytes(...)` 설정 |
| 최대 SSE / 업그레이드 연결 | 64 / 16 |
| 정상 종료 대기 | 5초 |

[`TinyHttpd.Builder`](core/src/main/java/com/snoworca/tinyhttpd/TinyHttpd.java)로 설정합니다. TLS는 [`TlsConfig`](core/src/main/java/com/snoworca/tinyhttpd/TlsConfig.java)를 명시해 활성화합니다. 기본 프로토콜 허용 목록은 TLS 1.2/1.3이며 플랫폼이 지원하는 항목만 사용합니다. 사용자 지정 DNS 이름은 `allowedHosts(...)`에 등록해야 하며, IP 주소와 `localhost`는 기본 허용됩니다.

### Android에 탑재하기

API 21 이상을 사용하고 호스트 앱에 `android.permission.INTERNET`을 선언하세요. 서버 시작·종료는 UI 스레드 밖에서 수행하고 앱 생명주기와 연결하세요. 장시간 운영하려면 Android의 백그라운드 실행 정책에 맞는 구성이 필요합니다. 파일 접근은 Android 버전과 저장소 권한에 따라 달라집니다. 서비스·저장소 접근·네트워크 변경 처리는 호스트 앱이 담당합니다.

### 문제 해결

| 증상 | 해결 방법 |
|---|---|
| `Address already in use` | 8080 포트를 사용하는 프로세스를 종료하거나 `.port(...)` / `-p`로 포트를 바꿉니다. |
| 독립 실행 앱이 바로 종료됨 | `.daemon(false)`를 사용하거나 앱이 관리하는 스레드를 유지합니다. |
| 사용자 지정 호스트 이름에서 HTTP 421 | `allowedHosts("nas.example")`로 이름을 추가합니다. |
| `javac`가 `TinyHttpd`를 찾지 못함 | 먼저 빌드하고 클래스패스의 JAR 경로와 버전을 확인합니다. |

### 문서

- [한국어 사용 가이드](docs/guide/ko.md) · [English user guide](docs/guide/en.md)
- [가이드 언어 선택](docs/guide/README.md)
- [Servlet API와 다른 점](docs/api/servlet-differences.md)
- [개발 로드맵](docs/next/README.md)
- [연구와 측정 자료](docs/research/)
- [공개 API 소스](core/src/main/java/com/snoworca/tinyhttpd/http/)

### 라이선스와 출처

TinyHttpd는 **BSD 3-Clause License**로 배포합니다. [LICENSE](LICENSE), [NOTICE](NOTICE), 포크 기준 커밋에서 수정 없이 보존한 [NanoHttpd 원본 라이선스](docs/licenses/NanoHttpd-LICENSE.md)를 참고하세요.

TinyHttpd 저작권: (c) 2026, Snoworca. NanoHttpd 원저작자: (c) 2012–2013 Paul S. Hawke, 2001·2005–2013 Jarno Elonen, 2010 Konstantinos Togias. 정확한 원문 저작권 고지는 연결된 라이선스 파일에 보존되어 있습니다.

소스를 재배포할 때는 저작권 고지·라이선스 조건·면책 조항을 유지해야 합니다. JAR·APK 등 바이너리를 재배포할 때는 배포물과 함께 제공하는 문서 또는 기타 자료에 해당 내용을 포함해야 합니다. 앱의 오픈소스 라이선스 화면이나 동봉한 라이선스 파일을 사용할 수 있습니다. 두 라이선스 전문과 출처 고지를 함께 제공하세요. 프로젝트 링크만으로 필수 고지 내용을 대신할 수는 없습니다. 라이브러리 JAR의 `META-INF/`에 이 고지 파일들이 포함됩니다. 재패키징할 때도 보존하고 앱 배포 자료에 포함하세요. TinyHttpd는 NanoHttpd 조직이나 기여자의 보증·추천을 주장하지 않습니다.
