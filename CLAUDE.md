# TinyHttpd

NanoHttpd(https://github.com/NanoHttpd/nanohttpd)를 포크해 대대적으로 개조하는 경량 임베디드 HTTP 서버.
원본은 2019-07-03(`efb2ebf`) 이후 사실상 개발이 중단되었고 내부 로직 품질이 낮아 전면 개선을 전제로 한다.

## 응답 언어

- 작업 진행과 사고 과정은 영어로 해도 된다.
- 사용자에게 주는 최종 답변은 한국어로 쓴다.

## 주 용도

- **오래된 안드로이드 폰 기반 NAS**의 HTTP 서버로 탑재한다.
- 대상 기기: **Android 5.0 (API 21) 이상**의 구형 폰.
- 동시 접속자: **50명 이하 1차 목표**. 사양에 따라서 10k 이상 가능. 
- 최우선 목표: **기본 메모리 사용량 최소화**. 그다음이 대용량 파일 전송 안정성.

## 결정 사항

### 기반 서버 선택: 개조한 NanoHttpd
- Tomcat: Android에 없는 API(JMX, JNDI, `java.beans` 등)에 의존하고 최신 버전은 Java 11 이상 필요 → 탈락.
- Vert.x: Netty 기반이라 메모리·APK 크기 부담이 크고, 동시 접속 50명 규모에서는 논블로킹 이점이 없음 → 탈락.
- NanoHttpd: 의존성 없음, Java 7 기준, 구조가 단순해 재작성하기 쉬움 → 채택.

### 이름과 좌표
- 프로젝트 이름: **TinyHttpd**
- Java 패키지: **`com.snoworca.tinyhttpd`** (기존 `org.nanohttpd` 사용 금지). Maven groupId: **`com.snoworca`**.
- 원격 저장소: 원본은 `upstream`(https://github.com/NanoHttpd/nanohttpd, fetch 전용)으로 둔다. 자체 저장소는 `origin`(https://github.com/Snoworca/TinyHttpd), 기본 브랜치는 `main`이다.

### 라이선스: BSD-3-Clause 유지
- 원본 저작권 고지(Paul S. Hawke, Jarno Elonen, Konstantinos Togias)는 LICENSE와 원본 코드가 남은 파일에서 **삭제하지 않는다**. 새 저작권 줄은 그 위에 추가한다.
- 바이너리(jar/APK) 배포 시 문서나 오픈소스 라이선스 화면에 같은 고지를 포함한다.
- "NanoHttpd 공식/추천"처럼 원작자 보증으로 보이는 표현은 쓰지 않는다. "Based on NanoHttpd"만 허용한다.
- 세 번째 조항의 "NanoHttpd organization"은 표준 문구 "the copyright holder"로 바꾼다.

### Java 버전과 Android 호환
- 언어 수준: **Java 8** (람다 등 문법은 D8/R8이 API 21용으로 변환).
- API 사용: **Java 7 수준 + API 21에 있는 것만** 쓴다. 코어 라이브러리가 `coreLibraryDesugaring`을 요구하지 않게 한다. 요구하면 이 라이브러리를 쓰는 앱도 디슈가링을 켜야 한다.
- 사용 금지(API 21에 없음): `java.nio.file.*`(`Files`, `Path`), `java.util.Base64`, `java.util.stream`, `java.util.function`, `Optional`, `java.time`, `CompletableFuture`.
- 대신 쓸 것: `java.io.File`, `FileChannel`, `ExecutorService`, 자체 Base64 구현.
- 빌드할 때 검사: 순수 Java 모듈은 animal-sniffer + `net.sf.androidscents.signature:android-api-level-21`로, Android 모듈은 Lint `NewApi`를 에러로 설정해 위반을 막는다.

### 빌드 환경과 의존성 (2026-10-18)
- 빌드 환경은 **Gradle**로만 구성한다(wrapper 사용). Maven 등 다른 빌드 도구를 다시 들이지 않는다.
- **추가 의존성을 절대 넣지 않는다.** 런타임·테스트·Gradle 플러그인 모두 해당한다. 지금 `build.gradle`에 있는 것(JUnit, OkHttp 등 테스트 도구, animal-sniffer)만 유지한다.
- 필요한 기능이 있으면 라이브러리를 들이지 말고 **직접 구현한다**(예: Base64, MIME 판별, multipart 파서, URL 디코딩).

### 요구사항 체계 (P0-08, 2026-10-18)
- 소유자 지시: "코드가 곧 문서다." 요구사항 추적은 `docs/next/`의 작업 ID(`P1-04`)와 테스트 ID(`RQ-nn` 등)가 맡는다.
- 아래 SpecKiwi 블록은 이 저장소의 작업에 적용하지 않는다. `docs/spec/`은 쓰지 않으며 SRS 등록 없이 구현한다.

### 재작성 방식과 API (2026-10-18)
- 공개 API는 Servlet 모양의 자체 API다(`com.snoworca.tinyhttpd.http`의 `Request`/`Response`/`Handler`/`Filter`). core는 servlet jar에 의존하지 않는다.
- 세션은 core 밖의 선택 모듈(`tinyhttpd-session`)에 둔다. 쿼리와 form을 합친 `getParameter`는 두지 않는다.
- `javax.servlet-api` 3.1.0 어댑터(`tinyhttpd-servlet`)는 로드맵의 마지막 단계에 둔다.
- 구 코드를 고치지 않는다. 새 엔진(`com.snoworca.tinyhttpd.server`)과 새 API를 옆에 새로 만들고, 모든 모듈을 옮긴 뒤 구 패키지를 삭제한다.
- 첫 공개 버전은 3.0.0이다(소유자 결정, 2026-10-22). 이후 마이너·패치 버전은 공개 API 호환성을 유지하고, 깨는 변경은 메이저 버전으로 배포한다.
- 단계별 작업, 기본값, 테스트 ID는 `docs/next/`에 있다(README가 목차). 작업을 시작하거나 끝낼 때 그 문서의 상태 표시를 갱신한다.

## 저장소 구성

- 모듈: `core/`(`tinyhttpd-core`), `webserver/`, `websocket/`, `router/`, `session/`. 모두 새 API(`com.snoworca.tinyhttpd.http`) 위에 다시 썼고 원본 NanoHttpd 코드는 남아 있지 않다(P8-05). `tools/bench`는 측정 도구다.
- 빌드: Gradle 8.14.3 wrapper 하나로 통일(`./gradlew build`). 루트 `build.gradle`이 모든 모듈을 설정하고, 모듈별 build 파일은 없다.
- `--release 8`로 컴파일한다. animal-sniffer(`android-api-level-21:5.0.1_r2`)가 `main` 소스를 검사한다.

## 테스트 규칙

- 테스트용 HTTP 클라이언트는 OkHttp(`testImplementation`)를 쓴다. 바이트 단위 제어가 필요한 경우에는 raw socket(`core/src/testFixtures`의 `com.snoworca.tinyhttpd.testing.RawConnection`)을 쓴다. 다른 모듈은 `testFixtures(project(":tinyhttpd-core"))`로 가져온다.
- 올바른 동작을 단언하지만 현재 코드에서 실패하는 테스트에는 `@Category(KnownDefect.class)`를 붙인다. 이 테스트는 기본 `test`에서 제외되고 `./gradlew :tinyhttpd-core:knownDefectTest`로 실행된다. 결함을 고치면 카테고리를 제거해 기본 스위트로 옮긴다.
- 결함 테스트 ID(KA-nn 등)는 `docs/research/`의 문서와 연결된다. ID를 바꾸면 문서도 함께 고친다.

## 연구 자료

- 모든 연구 자료는 `docs/research/`에 저장한다.
- 회사에서 진행한 NanoHttpd 개조 작업의 코드나 자료는 사용하지 않는다. 개인 프로젝트이므로 원본 코드와 공개 자료만으로 독립적으로 연구한다.

## 개선 방향 (NAS 기준)

- 요청마다 스레드를 무한히 만드는 `DefaultAsyncRunner`를 크기가 제한된 스레드 풀(32~64개)과 대기 큐로 교체.
- Keep-alive 지원과 유휴 연결 타임아웃 정리.
- multipart 업로드를 메모리나 임시 파일 전체에 담지 않고 디스크로 바로 스트리밍.
- 다운로드는 `FileChannel.transferTo`로 보내고 Range 요청(206 응답)을 정확히 지원.
- 경로 탐색(`../`) 차단, URL 디코딩·헤더 파싱을 엄격하게 재작성.

Android 앱 쪽 고려사항(서버 라이브러리 밖):
- Android 10 이상 scoped storage → SAF 또는 `MANAGE_EXTERNAL_STORAGE` 전략 필요.
- foreground service와 WakeLock/WifiLock으로 Doze 모드에서 서버가 종료되지 않게 한다.

# SpecKiwi SRS workflow v1.9

This repository uses `docs/spec/` as the required source of truth for requirements.

Before making any code, test, CLI, MCP, or documentation change, agents MUST:
1. Read `docs/spec/00.index.md`.
2. Find the relevant Requirement ID in the scope SRS files.
3. Mention the Requirement ID in the work summary.
4. If no matching requirement exists, stop and ask whether to create/update an SRS requirement first.

Requirement metadata has two separate lifecycle fields:
- `Status` tracks implementation and verification progress.
- `Stability` tracks requirement maturity and change-control maturity.

Agents MUST stop before implementing a non-discarded requirement with `Stability=draft` or `Stability=deprecated` unless the user explicitly overrides that workflow.

TDD principle:
- Agents MUST follow TDD for behavior changes: write or update a failing automated test for the relevant Requirement ID before implementation, make the smallest change to pass, then refactor while keeping tests green.
- If no meaningful automated test can be written, agents MUST stop before implementation and explain the exception and alternative verification evidence.

Work-mode and the TDD First (tdd) workflow:
1. Before starting work, read the persisted work-mode with the MCP `get_work_mode` tool, or CLI `speckiwi mode` when MCP is unavailable (stored in `docs/spec/steps/state.md`). When no mode is set the mode is wait and the sdd (SRS-first) rules in this document apply.
2. Switch modes with the MCP `set_work_mode` tool (mode plus an optional activeTask for vibe/tdd) or CLI `speckiwi mode <value>`. Any mode may switch to any other of sdd, vibe, wait, and tdd; switching to sdd or wait drops a stale Active Task line, and an out-of-enum value is rejected with INVALID_MODE.
3. When the mode is `tdd`, step-scoped work follows the TDD First cycle: author the step SDS at `docs/spec/steps/<task>/design.md` per the installed SDS-MD Authoring Rules (`docs/rule/SDS-MD-Rules-v2.6.0.md`) with EARS acceptance contracts (SDS-AC), translate the SDS-ACs into failing tests and confirm they fail, implement the smallest change to green, run regression, then synthesize the step SRS and promote the step requirement with verification evidence.
4. tdd gates (all mandatory): do not write tests before the step's SDS exists, unless the skip is recorded in `intent.md` under `## SDS Skip` with a `Decision` of `skipped`, a non-empty `Reason` and an EARS `SDS-AC-n: WHEN … SHALL …` line — `SDS-E054` refuses an unrecorded skip; commit tests first and never weaken a test to reach green; never promote a step requirement without verification evidence.
5. In tdd mode the rule "do not implement behavior not covered by an SRS requirement" is satisfied for step-scoped work by the agreed SDS plus the mandatory post-hoc promotion; body-scope work keeps the sdd rules in this document.
6. Edits to existing body requirements and large architecture changes stay in sdd mode — never route them through a tdd step.

Scope SRS document naming:
1. A scope SRS document is named `docs/spec/{NN}.{scope-slug}.srs.md`, where `{NN}` is a two-digit ordering number. The full rules are in `docs/rule/SRS-MD-Rules-v2.5.0.md` §5.2.
2. Allocate `{NN}` as one above the highest number already present among the project's scope documents. The first scope document of a project is `01`, the next `02`. Do not number by tens.
3. Never reuse a number another scope document holds, and never renumber an existing document.
4. Prefer `speckiwi scaffold-scope <Name>:<PREFIX> --apply`, which allocates the number and registers the document in both index sections in one operation, over writing the file and the index rows by hand.

Agents MUST NOT:
- Implement behavior that is not covered by an SRS requirement.
- Create an alternate requirements source outside `docs/spec/`.
- Change requirement IDs manually.
- Mark requirements as verified without evidence.
- Introduce or invoke bulk-archive / bulk-finalize tooling that flips multiple requirements to `verified` or empties Active Target without per-requirement evidence and stability gate checks.

When SpecKiwi MCP tools are available, agents MUST use them for requirement lookup and safe SRS updates. Normal target-scoped SRS mutations go through MCP only: the `speckiwi` CLI diagnoses installation, version and configuration and guides MCP recovery, and is not a normal replacement for an MCP mutation. Registering an unregistered target is the single exception, and the target workflow below names it. CLI reads remain available for lookup while MCP is being repaired.

Per-call workspace root:
1. The MCP server resolves its root from its own process working directory, and SRS is written only there.
2. The `workflow_*` family accepts an optional absolute `workspaceRoot` on every tool, and the `orchestrate_*` family accepts it on every tool except `orchestrate_replay_apply` and `orchestrate_preflight`.
3. The SRS query tools also accept it: `list_requirements`, `search_requirements`, `get_requirement`, `validate_spec`, `summarize_target`, `get_active_target`, `list_completed_work`, `validate_step`, `get_work_mode`, `check_vibe_gate`, `list_dirty_edges`, `list_compat_edges`, `list_steps`, `check_sds` and `check_test_sufficiency` — each reading the named checkout and writing nothing. They are refused when that checkout holds no `docs/spec/00.index.md`, and `check_test_sufficiency` refuses there an `sds` path under `docs/spec` (a step `design.md`), so that check runs through `speckiwi coverage --tests` inside the worktree.
4. Every tool that writes under `docs/spec` or allocates a Requirement ID refuses `workspaceRoot` fail-closed, and so do `mcp_workspace_info` and `get_next_work_order`. Refusal is the default, so a tool not named here refuses it.
5. An accepted `workspaceRoot` MUST be an absolute path to an existing git top level that is a worktree of the startup root's repository; a path argument landing under `docs/spec` is refused even on a tool that accepts the root, unless the tool declares that it takes no caller-supplied path — which is what lets an SRS query filter on a `docs/spec` reference.
6. Agents MUST confirm workspace identity from the `mcpWorkspace` envelope — `workspaceRoot` plus `rootSource` — before any target-scoped read or mutation. `rootSource` reads `per-call-workspace-root` exactly when a supplied `workspaceRoot` passed every gate, and `server-cwd-discovery` or `auto-init` otherwise.

Current work status workflow:
1. Read the active target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
2. If `activeTarget` is empty, report that no active target is set and ask which target to use before making target-scoped changes.
3. Read `summary.countsByStatus`, `summary.countsByStability`, `summary.stabilityBlockers`, `summary.stabilityWarnings`, and `summary.newWorkCandidates` before selecting work.
4. Read open work with MCP `list_requirements` for `status=in_progress`, `status=blocked`, and `status=implemented`; CLI fallback is `speckiwi list --status <status> --json`.
5. Check missing verification evidence through `summary` or MCP `summarize_target` before saying work is complete.
6. Read recent completed work with MCP `list_completed_work`; CLI fallback is `speckiwi completed-work --json`.

Next target authoring workflow:
1. If the user asks to set the next target, first read the current Active Target and Target Map.
2. If the target is not registered, use a supported target-registration mutation such as MCP `set_active_target` with creation support, or CLI `speckiwi set-active-target <target> --create` when that option is available.
3. If the configured MCP/CLI cannot register the target, stop before target-scoped SRS changes and report the tool gap, unless the user explicitly authorizes a minimal SRS-MD patch.
4. After target assignment, confirm the resolved Active Target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
5. When the user provides a target goal, record it with MCP `set_target_goal`. Recording a goal is a mutation, so it waits for MCP rather than moving to the command line; if MCP cannot be repaired, report the gap instead.
6. For later SRS creation, omit the target only when the tool supports Active Target defaulting; otherwise pass the confirmed Active Target explicitly.
7. If the user provides an explicit different target for a requirement, the explicit target wins over Active Target.

Merge-time duplicate Requirement ID repair workflow:
1. Run `speckiwi validate --json` or MCP `validate_spec` first. Use repair only when `SRS-E002` duplicate Requirement ID diagnostics exist, or when a named duplicate ID is confirmed in parsed diagnostics.
2. Resolve normal Git conflict markers before repair. Then run MCP `diagnose_requirement_id_collisions` or CLI `speckiwi repair requirement-id-collisions diagnose --json`.
3. Select explicit keep and rename occurrences by `filePath`, `headingLine`, and `blockHash`. A duplicate ID alone is never enough to write.
4. Create a dry-run plan with MCP `plan_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions plan --duplicate-id <id> --keep <file:line:blockHash> --rename <file:line:blockHash> [--replacement-id <id>|--allocate-next] --write-plan <path> --json`.
5. Apply only from the explicit plan or equivalent explicit mapping with MCP `apply_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions apply --plan <path> --json`. `--ignore-lock` is allowed only on apply and bypasses only the SRS mutation lock.
6. Do not use collision repair for general renumbering, gap filling, ID beautification, bulk archive, bulk finalize, or Status/Stability changes. When two duplicate logical requirements should be merged or discarded, first repair IDs to uniqueness, then use separate guarded SRS mutations for discard, supersedes, Status, Stability, AC, or evidence changes.
7. When implemented runtime CLI or MCP repair tooling is available, do not hand-edit Requirement IDs. If tooling is unavailable and the user explicitly authorizes a degraded SRS-MD patch, limit it to the selected occurrence and explicitly mapped references.
8. Finish with `speckiwi validate --fail-on-warning --json`, `speckiwi summary --target <target> --json`, and `speckiwi links check --json` or MCP equivalents. Evidence must show duplicate IDs are zero and ambiguous references were reported or explicitly mapped.

The Completed Work Log — inline in `docs/spec/00.index.md` §7 and its split history file `docs/spec/91.completed-work-log.md` — is a read-only summary for agents. Requirement Block status, Acceptance Criteria, Verification Evidence, and Change Notes remain the source of truth for completion.

<!-- /SpecKiwi SRS workflow -->
