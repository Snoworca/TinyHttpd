# 스레드 모델 판단 (P3-10, P6-07)

- 현재 모델: 블로킹 워커 풀(keep-alive.md §4.8 1단계). 코드: `core/.../server/WorkerPool.java`, `HttpServer.java`(acceptor, watchdog).
- JVM 측정(baseline.md "새 엔진"): idle-128에서 스레드 72개(워커 64 + acceptor + watchdog + 클라이언트 측), 힙 증가 약 2 MB.

## 2단계(Selector 주차) — 보류 (2026-10-18)

판단 기준(p03 P3-10)은 실기기 PSS와 브라우저 50탭 시나리오가 필요하다. JVM 수치로는 판단하지 않는다. P10-04(AND-06)에서 측정한 뒤 이 문서에 판단을 적는다.

## SSE 50개 스트림 대안(P6-07) — 보류

AND-08 측정 뒤 판단한다. 그 전까지는 스트림마다 소유 스레드를 쓰는 현재 구조를 유지한다.
