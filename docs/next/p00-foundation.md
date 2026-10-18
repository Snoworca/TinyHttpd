# P0. 기반 작업

- 목표: 재작성을 시작하기 전에 저장소, CI, 측정 도구, 테스트 기반을 갖춘다.
- 선행: 없음
- 관련: [README](README.md), CLAUDE.md "테스트 규칙"

## 작업

### P0-01 첫 커밋과 원격 저장소 `[x]` (2026-10-18 push)
- 지금까지의 정리(패키지 이름 변경, Gradle 통일, 모듈 제거, 연구 문서)는 커밋되지 않았다. 리뷰하기 쉽게 세 커밋으로 나눈다.
  1. `chore: rename to TinyHttpd (com.snoworca.tinyhttpd), drop unused modules` — 패키지 이름 변경, 삭제, LICENSE/README
  2. `build: unify on Gradle 8.14.3, Java 8 release, animal-sniffer API 21` — 빌드 파일, wrapper
  3. `test/docs: keep-alive probes, research and plan` — KA/KB 테스트, `docs/`
- 자체 저장소 `origin`: https://github.com/Snoworca/TinyHttpd (2026-10-18 `git remote add` 완료). `upstream`(NanoHttpd 원본)은 fetch 전용으로 둔다(`git remote set-url --push upstream no-push`).
- 원격 `main`에는 GitHub가 만든 `Initial commit`(67096a2, LICENSE 하나)만 있고, 로컬 이력(원본 NanoHttpd 이력 + `efb2ebf`)과 이어지지 않는다. 통합 방법:
  1. 로컬 `master`를 `main`으로 이름을 바꾼다.
  2. 위 세 커밋을 만든다.
  3. `git merge origin/main --allow-unrelated-histories`로 합친다. LICENSE 충돌은 **로컬 판을 유지**하고 표기만 `Snoworca`로 맞춘다. 원격 판에는 원저작자 고지(Paul S. Hawke, Jarno Elonen, Konstantinos Togias)가 없어 BSD-3-Clause 조건을 지키지 못한다.
  4. `git push -u origin main`(force push 없음).
- 진행 상황(2026-10-18): 로컬에서 1~3단계를 끝냈다(`37c1ab7`, `34ce2c1`, `0710bef`, 병합 `4352bae`). push는 소유자 확인 뒤에 한다. CLAUDE.md와 SpecKiwi 파일(`AGENTS.md`, `docs/spec/`, `docs/rule/`, `docs/.kiwi/`)은 소유자 요청으로 커밋에서 뺐다(P0-08 결정 뒤 커밋).
- 완료 조건: `git status`가 깨끗하다. `git log --follow`로 원본 파일의 이력을 따라갈 수 있다(이름 변경이 rename으로 기록됨). `origin/main`에 push되었다.

### P0-02 CI `[~]` (2026-10-18 워크플로 작성. PR에서 실행 확인과 KA-14 Linux 결과 기록이 남음)
- GitHub Actions 워크플로 `.github/workflows/build.yml`:
  - `ubuntu-latest`, Temurin JDK 17, Gradle 캐시
  - `./gradlew build` (실패하면 CI 실패)
  - `./gradlew :tinyhttpd-core:knownDefectTest` (실패해도 통과. 결과 XML을 아티팩트로 올린다)
- **Linux 측정 겸용**: KA-14(lingering close)는 macOS에서 재현되지 않았다. Linux 러너 결과를 keep-alive.md §2.1에 기록한다.
- 완료 조건: PR에서 워크플로가 돌고, knownDefectTest 리포트를 내려받을 수 있다.

### P0-03 기준선 측정 도구 `[x]` (2026-10-18, `tools/bench`, `docs/research/baseline.md`. JFR 할당 측정은 미구현)
- 목적: "메모리 최소화"를 숫자로 관리한다. 재작성 전후를 같은 도구로 비교한다.
- `tools/bench/`에 JVM용 측정 프로그램을 둔다. 라이브러리 빌드와 분리된 Gradle 서브프로젝트로 만들고, 배포 대상에서 뺀다.
  - 루트 `build.gradle`의 `subprojects {}`는 지금 모든 서브프로젝트에 java-library와 animal-sniffer API 21 검사를 건다. bench는 `java.lang.management`와 JFR을 쓰므로, 설정 대상을 라이브러리 모듈 목록으로 한정한다.
  - 시나리오: 서버 시작 직후, 유휴 연결 50/100/128개, 50개 연결로 작은 GET 반복, 1 GB 업로드 1개, 1 GB 다운로드 4개 동시.
  - 기록: 힙 사용량(GC 후 `Runtime.totalMemory - freeMemory`), 라이브 스레드 수, 처리량, p50/p99 지연, 연결별 할당 바이트(가능하면 JFR).
- 구 엔진으로 먼저 측정해 `docs/research/baseline.md`에 저장한다. 측정 환경(JDK, OS, 힙 설정)을 함께 적는다.
- 메모리 회귀 게이트(README §5.1): `idle-0`, `idle-100`을 짧게 돌리는 CI 작업을 이 단계에서 만들되, 처음에는 보고만 한다. 기준값 기록과 게이트 활성화는 P3 단계 완료 조건이다.
- Android 실기기 측정은 P10에서 같은 시나리오로 한다.
- 완료 조건: `./gradlew :bench:run --args='idle-100'` 같은 명령으로 재현할 수 있고, 기준선 문서가 있다.

### P0-04 테스트 도구 정리 `[ ]`
- `RawConnection`을 `core/src/testFixtures`로 옮긴다(`java-test-fixtures` 플러그인). router, webserver, websocket 테스트에서도 같은 도구를 쓴다.
- 추가할 기능:
  - 바이트를 나눠 천천히 보내기(`sendSlowly(bytes, chunk, delayMs)`) — slowloris, 헤더 분할 시나리오
  - 소켓 수신 버퍼를 작게 하고 읽지 않기 — 쓰기 타임아웃 시나리오(P3)
  - half-close(`shutdownOutput`) 후 응답 읽기
- OkHttp 클라이언트 생성 헬퍼: 연결 풀 공유, `retryOnConnectionFailure` 켜기/끄기, 이벤트 리스너로 연결 재사용 횟수 기록.
- 워커 스레드 CPU 시간 측정(`ThreadMXBean`) 헬퍼: 무한 루프 검출(KA-06/07)에 쓴다. 이미 `KeepAliveDefectTest`에 있는 코드를 꺼낸다.
- CLAUDE.md "테스트 규칙"의 `RawConnection` 경로를 새 위치로 고친다(README §4.1 R-13).
- 완료 조건: 기존 KA/KB 테스트가 새 헬퍼로 바뀌고 결과가 같다(KB 6개 통과. KA는 macOS 15개 실패, Linux는 KA-14를 포함해 최대 16개 실패).

### P0-05 코드 품질 도구 `[ ]`
- 대상은 **구 코드를 뺀 전부**다. 구 코드는 `protocols/**` 패키지와 `util`의 구 클래스 4개(`ServerRunner`, `IHandler`, `IFactory`, `IFactoryThrowing`)이며 삭제 예정이므로 제외한다. 새 유틸(`Html`, `Base64`)은 구 `util`과 섞이지 않게 `com.snoworca.tinyhttpd.http.util`에 둔다(P4).
- javac `-Werror`는 패키지 단위로 걸 수 없다. 그래서 P8-05(구 패키지 삭제) 전까지는 `-Xlint:all` 경고를 보고만 하고, 새 패키지의 경고 0개는 CI에서 경고 줄을 패키지 경로로 걸러 확인한다. P8-05 뒤에 `-Werror`를 켠다.
- javadoc `-Xdoclint:all`도 구 코드에서 경고가 난다. P8-05 전까지 구 코드만 파일 단위로 제외한다: `javadoc { exclude 'com/snoworca/tinyhttpd/protocols/**', 'com/snoworca/tinyhttpd/util/ServerRunner.java', 'com/snoworca/tinyhttpd/util/IHandler.java', 'com/snoworca/tinyhttpd/util/IFactory*.java' }`.
- 포맷: 원본의 Eclipse formatter 설정은 삭제했다. 새로 정하지 않고, 기존 코드 스타일(4칸 들여쓰기, 120자)을 `.editorconfig`로만 고정한다.
- 완료 조건: `./gradlew build`에서 구 코드를 뺀 경고가 0개다.

### P0-06 CLAUDE.md와 계획 문서 연결 `[x]`
- CLAUDE.md에 D-01~D-07과 "작업 계획은 `docs/next/`" 안내를 추가한다(이 계획을 작성할 때 함께 반영했다).
- 진행 상황: CLAUDE.md에 반영했다. CLAUDE.md 커밋은 P0-08 결정 뒤로 미뤘다.
- 완료 조건: CLAUDE.md만 읽어도 이 디렉터리를 찾을 수 있고, CLAUDE.md가 커밋되었다.

### P0-07 소켓 종류 스파이크 (JVM) `[ ]`
- 엔진의 acceptor(P1-03)를 쓰기 전에 정해야 할 것을 JVM에서 확인한다. Android 쪽 확인은 P5-04와 P10(AND-05)에서 한다.
  - 채널 기반 소켓(`ServerSocketChannel`)의 `socket().getInputStream()` 읽기가 `setSoTimeout`을 따르는가
  - 평문 `ServerSocket`으로 받은 소켓을 `SSLSocketFactory.createSocket(socket, host, port, autoClose)`로 감쌀 때, 다른 스레드에서 **원본 평문 소켓**을 닫으면 TLS 쓰기·읽기가 막힘 없이 풀리는가(P3-04, P7-02)
  - 다른 스레드의 `close()`가 막힌 `write`와 `transferTo`를 깨우는가
  - **전이중**: 채널 기반 소켓에서 한 스레드가 `read`에 막혀 있을 때 다른 스레드의 `write`가 진행되는가. JDK 8 계열 `SocketAdaptor`는 입출력 스트림이 락을 공유한다고 알려져 있다(착수 시 확인). Android 7 이후 libcore가 OpenJDK 8 기반이므로 JDK 17에서 문제가 없어도 Android에서 다를 수 있다. WebSocket(읽기 스레드 + 앱 송신 스레드)에 직접 영향을 준다. JDK 8과 JDK 17에서 각각 확인한다
  - 평문 소켓을 `createSocket(socket, …)`으로 감싼 `SSLSocket`에 `setUseClientMode(false)`를 걸었을 때 서버 핸드셰이크가 정상인가(감싼 소켓의 기본은 클라이언트 모드다)
- 산출물: `docs/research/socket-io.md`(JVM 절). P5-04 결정의 JVM 근거가 된다. P1-03은 이 결과와 무관하게 `ServerSocket`으로 시작한다.
- 완료 조건: 문서에 항목별 결과와 재현 코드 위치가 있다.

### P0-08 SpecKiwi 요구사항 체계와의 관계 정리 `[x]` (2026-10-18: (b) 채택. 작업·테스트 ID가 추적을 맡고 SpecKiwi는 적용하지 않는다)
- CLAUDE.md 뒤쪽의 SpecKiwi 블록은 요구사항의 원천을 `docs/spec/`으로 정한다. 그리고 "SRS 요구사항이 없는 동작 구현"과 "`docs/spec/` 밖의 대체 요구사항 원천"을 금지한다. 현재 `docs/spec/`은 빈 템플릿이다.
- 이 디렉터리(`docs/next`)는 **작업 계획**이다. 요구사항 원천으로 쓰지 않는다. 착수 전에 둘 중 하나를 정한다.
  - (a) 단계마다 SRS 범위 문서(`docs/spec/NN.<scope>.srs.md`)를 만들고, 이 계획의 테스트 ID(RQ, RS, TH …)를 요구사항 ID에 연결한다. 작업 문서에는 요구사항 ID를 함께 적는다.
  - (b) SpecKiwi 적용 범위를 줄이거나 블록을 제거한다.
- 이 결정 전에는 **코드 구현을 시작하지 않는다**(P0-01~07의 문서·도구 작업은 제외).
- 완료 조건: 결정이 CLAUDE.md와 README §4에 반영되었다.

## 완료 조건 (단계)
- P0-01~08이 끝났다. CI가 녹색이다. 기준선 측정 문서가 있다.
