# P12. 문서와 배포

- 목표: 다른 사람이(또는 NAS 앱 프로젝트가) 이 라이브러리를 가져다 쓸 수 있게 한다.
- 선행: P8, P9(최종 감사), P10(측정)
- 관련: CLAUDE.md "라이선스", [README](README.md) §6

## 작업

### P12-01 버전 정책 `[ ]`
- 0.x 동안은 마이너 버전이 올라갈 때 깨는 변경을 허용한다(D-07). 패치 버전은 호환을 유지한다.
- `CHANGELOG.md`를 둔다. 항목마다 작업 ID(`P4-05` 등)를 적는다.
- 첫 공개 버전은 `0.1.0`이다. 1.0으로 가기 전에 "API 동결 후보" 기간을 두고, 그 기간에 P11 착수 여부를 판단한다.
- 완료 조건: `CHANGELOG.md`가 있고 버전 정책이 README에 있다.

### P12-02 배포 경로 `[ ]` (소유자 확인 필요)
- 선택지: Maven Central(서명, `com.snoworca` 네임스페이스 검증 필요), JitPack(GitHub 태그만으로 배포), 로컬 `mavenLocal`만.
- 공통: `maven-publish` 플러그인, sources·javadoc jar, POM 메타데이터(BSD-3-Clause 라이선스, 원저작자 표기, SCM URL).
- `java-test-fixtures`(P0-04)의 test-fixtures 변형은 `maven-publish`가 기본으로 함께 배포한다. 테스트용 개인 키(P7-04)가 공개 아티팩트에 들어가지 않도록 `components.java.withVariantsFromConfiguration(configurations.testFixturesApiElements) { skip() }`(RuntimeElements도 같음)로 뺀다. 배포 산출물 목록을 확인하는 단계를 둔다.
- 완료 조건: 선택한 경로로 `0.1.0`이 배포되었고, 빈 Android 프로젝트(`minSdk 21`)에서 의존성 한 줄로 가져와 빌드된다.

### P12-03 R8/ProGuard 소비자 규칙 `[ ]`
- core는 리플렉션을 쓰지 않으므로 규칙이 필요 없게 유지한다.
- webserver 플러그인의 `ServiceLoader` 구현 클래스를 보존하는 규칙을 jar의 `META-INF/proguard/tinyhttpd-webserver.pro`에 넣는다. R8은 jar 안의 이 경로를 자동으로 읽는다.
- 완료 조건: AND-11(측정 앱 minify 빌드) 통과.

### P12-04 문서 `[ ]`
- `README.md`: 소개, 빠른 시작(10줄 예제), 모듈 표, 기본값 표, 문서 링크.
- `docs/guide/android.md`: foreground service, `WakeLock`/`WifiLock`, Doze, scoped storage 전략(SAF와 `MANAGE_EXTERNAL_STORAGE`), 포트 선택(1024 이상), 네트워크 변경 시 재바인딩, 앱 전용 임시 디렉터리(SEC-12), `HostAllowlistFilter` 권장(SEC-14), 인증서 준비(P7-01 결과).
- `docs/guide/migrating-from-nanohttpd.md`: 구 API(`serve`, `newFixedLengthResponse`, `parseBody`, 인터셉터)와 새 API의 대응표.
- `docs/api/servlet-differences.md`(P4-09)를 링크한다.
- 라이선스 고지 안내: 앱의 오픈소스 라이선스 화면에 넣을 BSD-3-Clause 문구(원저작자 포함).
- SAF용 `FileResource` 구현 예제(P5-06).
- 완료 조건: 위 문서가 있고, 빠른 시작 예제가 테스트 소스에서 컴파일된다.

### P12-05 라이선스 고지 `[ ]`
- 모든 jar에 루트 LICENSE를 `META-INF/LICENSE-tinyhttpd.txt`로 넣는다. `META-INF/LICENSE`라는 흔한 이름은 Android 앱 빌드에서 다른 라이브러리와 경로가 충돌하거나, AGP의 기본 packaging 설정에 따라 APK에서 빠질 수 있다.
- **실제 고지 의무는 앱이 진다.** BSD-3-Clause의 바이너리 배포 조건은 "문서 또는 배포물에 고지 포함"이다. 그래서 jar 안의 파일만으로는 부족할 수 있다. 앱의 오픈소스 라이선스 화면에 넣을 문구를 P12-04 가이드에 둔다.
- 원본 코드를 옮긴 파일(MIME 표 출처, WebSocket 프레임 클래스 등)에 원본 저작권 헤더가 남아 있는지 확인한다.
- 완료 조건: `unzip -l`로 각 jar에서 파일을 확인했고, `git grep -L "Copyright"`로 원본 유래 파일의 헤더를 점검했다.

### P12-06 릴리스 점검표 `[ ]`
1. `./gradlew build` 통과(macOS, Linux CI)
2. `./gradlew :tinyhttpd-core:knownDefectTest`가 비어 있다(KnownDefect 테스트 0개)
3. TLS 매개변수 회귀 스위트 통과(P7-08)
4. P9 최종 감사 완료. 원본 유래 파일의 저작권 헤더 점검(P12-05)
5. P10 최소 기기 구성에서 AND-01~13 확인. README §5.1 메모리 예산의 확정 목표를 지키고, CI 회귀 게이트가 녹색이다
6. CHANGELOG, README, 가이드 갱신
7. 태그 `v0.1.0`, 배포(P12-02)

- 완료 조건(P12-06): 7개 항목의 확인 결과(날짜, 커밋)를 이 문서에 적었다.

## 완료 조건 (단계)
- `0.1.0`이 배포되었고 점검표 7개 항목이 모두 확인되었다.
