# TinyHttpd

Android 5.0(API 21) 이상의 구형 기기에서도 돌아가는 초경량 임베디드 HTTP 서버입니다.
기본 메모리 사용량을 최소로 유지하는 것을 최우선 목표로 하며, 오래된 안드로이드 폰 기반 NAS의 HTTP 서버로 쓰기 위해 만들고 있습니다.

> Based on [NanoHttpd](https://github.com/NanoHttpd/nanohttpd) (BSD-3-Clause).
> 원본 마지막 커밋 `efb2ebf`(2019-07-03)에서 포크했으며, 내부 로직과 API를 대대적으로 재작성하는 중입니다.

## 모듈

| 모듈 | 디렉터리 | 설명 |
|---|---|---|
| `tinyhttpd-core` | `core/` | HTTP 서버 코어 (런타임 의존성 없음) |
| `tinyhttpd-webserver` | `webserver/` | 정적 파일 서버 |
| `tinyhttpd-websocket` | `websocket/` | WebSocket 지원 |
| `tinyhttpd-router` | `router/` | URL 라우터 |

## 빌드

```sh
./gradlew build
```

- 언어 수준은 Java 8(`--release 8`)입니다.
- animal-sniffer가 Android API 21 시그니처로 `main` 소스를 검사합니다. API 21에 없는 클래스나 메서드를 쓰면 빌드가 실패합니다.

## 문서

- 설계 결정과 제약: [`CLAUDE.md`](CLAUDE.md)
- 연구 자료: [`docs/research/`](docs/research/)

## 라이선스

BSD-3-Clause. [`LICENSE`](LICENSE)를 참고하세요. 원본 NanoHttpd 저작권 고지가 포함되어 있습니다.
