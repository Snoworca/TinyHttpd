# P11. `tinyhttpd-servlet` 어댑터 (선택)

- 목표: 기존 `javax.servlet` 3.1 코드(직접 작성한 서블릿, 소형 라이브러리)를 TinyHttpd 위에서 돌린다.
- 결정: D-05에 따라 로드맵에 넣는다. 다만 core API가 안정된 뒤에 착수한다.
- 선행: P8. core API를 0.x 안에서 더 깨지 않기로 판단한 시점(P12-01의 "API 동결 후보")
- 관련: [servlet-api.md](../research/servlet-api.md) §3.1~3.2, §4.3, §6

## 착수 조건
1. P8이 끝나 core API가 router/webserver/websocket으로 검증되었다.
2. P11-01의 조사에서 "돌리고 싶은 서블릿 코드"가 한 가지 이상 확인되었다. 없으면 이 단계는 보류 상태로 두고 이유를 이 문서에 적는다(servlet-api.md §6 "javax 3.1 어댑터의 가치").

## 작업

### P11-01 대상 조사 `[ ]`
- API 21에서 디슈가링 없이 도는 `javax.servlet` 3.x 기반 라이브러리를 찾는다. 후보 범주: 파일 업로드(Commons FileUpload 1.x), WebDAV 서블릿, 템플릿 엔진의 서블릿 통합, 사용자가 직접 쓴 서블릿.
- 후보마다 animal-sniffer API 21 검사를 하고, 쓰는 Servlet 기능(아래 범주 표의 어느 행인지)을 기록한다.
- 산출물: 이 문서의 "조사 결과" 절. 착수 여부 결론.
- 완료 조건: 후보별 API 21 검사 결과와 결론이 있다.

### P11-02 모듈 골격 `[ ]`
- 모듈 `servlet/`(`tinyhttpd-servlet`), 의존성 `api 'javax.servlet:javax.servlet-api:3.1.0'`. `compileOnly`가 아니다. APK에 jar가 포함되어야 실제로 동작한다(servlet-api.md §4.3).
- CDDL 고지와 소스 제공 의무는 **servlet-api jar를 함께 배포하는 앱**이 진다. 어댑터 jar 자체(BSD)에 CDDL 고지를 넣을 대상은 아니다. 모듈 README 첫머리에 앱이 해야 할 일(고지 문구, 소스 위치: Maven Central의 sources jar)을 적는다(servlet-api.md §3.1 라이선스 표).
- animal-sniffer 검사 대상에 이 모듈도 포함한다.
- 완료 조건: 빈 모듈이 빌드되고 animal-sniffer를 통과한다.

### P11-03 위임 구현 `[ ]`
servlet-api.md §4.3의 범주 표를 따른다. 다만 "core에 위임"만으로는 Servlet 의미를 만들 수 없는 항목이 있다. 이것들은 어댑터가 직접 구현한다.

| 항목 | core와 다른 점 | 어댑터 구현 |
|---|---|---|
| `getWriter()` 인코딩 | core는 UTF-8, Servlet은 ISO-8859-1 | core의 `OutputStream` 위에 어댑터가 `Writer`를 만든다 |
| `getParameter*`와 바디 | core는 `getInputStream`/`readForm`이 배타적(P5-03) | 어댑터가 첫 `getParameter` 때 `readForm`으로 읽어 캐시하고, 이후 `getInputStream`/`getReader`는 Servlet 명세대로 빈 스트림 또는 `IllegalStateException` |
| `@MultipartConfig` | 리플렉션으로 어노테이션을 읽는다 | R8 keep 규칙(`-keepattributes RuntimeVisibleAnnotations`, 서블릿 클래스 보존)을 어댑터 jar의 `META-INF/proguard/`에 넣는다(P12-03) |
| `sendError` 뒤 쓰기 | Servlet은 무시 | core와 같음(P2-07). 위임 |

나머지 범주:

| 범주 | 처리 |
|---|---|
| 요청 라인, 헤더, 쿠키, 속성, 원격 주소, `getInputStream`, `getReader`(위 표의 바디 규칙을 따름) | core에 위임 |
| 응답 상태, 헤더, `getOutputStream`, `sendRedirect`, 버퍼 | core에 위임 |
| `getSession()` | session 모듈(P8-04)에 위임 |
| `getParts()` | `@MultipartConfig`가 있을 때만 임시 파일로 구현. 임시 디렉터리는 설정으로 받는다(SEC-12) |
| `isAsyncSupported()` | `false`. `startAsync`, `setReadListener`/`setWriteListener`는 명세대로 `IllegalStateException` |
| `ServletContext` | `getMimeType`, `getAttribute`, `getInitParameter`, `getContextPath`, `log`. `getRealPath`는 `null` |
| `RequestDispatcher`, `login`/`authenticate`, `upgrade` | `UnsupportedOperationException`. README에 **명세 비준수**라고 적는다 |

- `ServletHandler implements Handler`: `HttpServlet` 하나와 `javax.servlet.Filter` 목록을 감싼다. 서블릿 수명주기(`init`/`destroy`)를 서버 시작/정지에 맞춘다.
- `getParameter`는 Servlet 의미 그대로 바디를 읽는다. core의 D-04와 다르지만 어댑터는 Servlet 의미를 따른다(이름이 같으면 의미도 같다).

- 완료 조건(P11-03): SRV-01~06 통과.

### P11-04 테스트 `[ ]`

| ID | 시나리오 | 기대 |
|---|---|---|
| SRV-01 | `HttpServlet.doGet`이 헤더·쿼리·쿠키 읽기 | 값 일치 |
| SRV-02 | `doPost`에서 `getParameter`로 urlencoded 바디 | 값 일치 |
| SRV-03 | `getWriter()`로 "é", charset 미지정 | ISO-8859-1 바이트 |
| SRV-04 | `@MultipartConfig` 서블릿의 `getParts()` | 파트 일치, 임시 파일 정리 |
| SRV-05 | `startAsync()` | `IllegalStateException` |
| SRV-06 | `javax.servlet.Filter` 체인 | 순서대로 실행 |
| SRV-07 | P11-01에서 고른 실제 라이브러리 1개 | 기본 시나리오 동작 |

- 완료 조건(P11-04): SRV-01~07 통과.

## 조사 결과
(P11-01에서 채운다.)

## 완료 조건 (단계)
- 착수했다면 SRV 테스트가 통과하고 README에 지원 범위 표가 있다. 보류했다면 이유가 이 문서에 있다.
