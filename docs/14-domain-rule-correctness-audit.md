# 도메인 규칙 정합성 감사

> 원본 티켓: Notion `Audit domain rule correctness`
>
> 작성일: 2026-06-10
>
> 범위: PRD, Functional Spec, Domain Model, PROJECT_RULES 기준 MVP 도메인 불변조건, 상태 전이, application service orchestration, scope guardrail, 테스트 증거.

## 요약

현재 production 코드에서 문서화된 핵심 예약 도메인 규칙과 명확히 충돌하는 버그는 확인되지 않았다. 회원 활성 상태, 수업 OPEN/정원, 중복 CONFIRMED 예약, 이용권 차감/복구, 수업 취소 시 예약 자동 취소, OutboxEvent 기록은 domain method와 application service 경계에서 대체로 문서와 맞게 구현되어 있다.

다만 문서에는 PRD/Functional Spec의 실제 MVP use case보다 넓은 상태 전이가 일부 남아 있다. 특히 Member 재활성화, MemberPass `expire()`/`cancel()`, ClassSession `close()`는 Domain Model/Test Strategy에 구현 기대처럼 표현되어 있지만, PRD MVP scope와 현재 API/use case에는 없다. 구현 누락으로 바로 판단하기보다 문서 드리프트로 분리하고, 제품 결정 없이 production code를 넓히지 않는 것이 맞다.

## Findings

### F-01: 핵심 aggregate invariant는 domain method와 unit test로 보호됨

- Category: `No issue`
- Source document reference: `docs/01-prd.md:98`, `docs/02-functional-spec.md:329`, `docs/03-domain-model.md:238`, `docs/03-domain-model.md:311`
- Code or test reference: `src/main/kotlin/dev/climbdesk/member/domain/Member.kt:16`, `src/main/kotlin/dev/climbdesk/pass/domain/MemberPass.kt:25`, `src/main/kotlin/dev/climbdesk/classsession/domain/ClassSession.kt:20`, `src/test/kotlin/dev/climbdesk/pass/domain/MemberPassTest.kt`, `src/test/kotlin/dev/climbdesk/classsession/domain/ClassSessionTest.kt`
- Current behavior: `Member.ensureActive()` rejects inactive members. `MemberPass.consume()` rejects non-active, empty, and expired passes and records consume history. `MemberPass.restore()` prevents canceled/full restores and records restore history. `ClassSession.reserveSeat()` rejects non-OPEN and full sessions. `ClassSession.cancelSeat()` prevents negative reserved count.
- Expected behavior: Current behavior matches the MVP invariant set.
- Impact: The important single-aggregate rules are not leaking into controllers or persistence entities.
- Recommended action: Keep current boundaries. Do not add extra lifecycle methods unless a product document promotes them into MVP use cases.
- Risk and effort estimate: Low risk, no immediate implementation effort.

### F-02: Reservation and class-session cancellation orchestration matches documented business flow

- Category: `No issue`
- Source document reference: `docs/02-functional-spec.md:406`, `docs/02-functional-spec.md:430`, `docs/02-functional-spec.md:480`, `docs/03-domain-model.md:428`
- Code or test reference: `src/main/kotlin/dev/climbdesk/reservation/application/ReservationApplicationService.kt:30`, `src/main/kotlin/dev/climbdesk/reservation/application/ReservationApplicationService.kt:80`, `src/main/kotlin/dev/climbdesk/classsession/application/ClassSessionApplicationService.kt:59`, `docs/11-reservation-transaction-concurrency-audit.md`
- Current behavior: Reservation creation validates member active state, locks class session, reserves a seat, checks duplicate confirmed reservations, selects an available member pass, creates a reservation, consumes the pass, saves class session state, and records a confirmation outbox event in one transaction. Reservation cancellation locks reservation and class session, cancels the reservation, restores the seat and pass, records history, and records a cancellation outbox event. Class session cancellation locks the session, locks confirmed reservations, restores each member pass, cancels each reservation, cancels the session, and records a class-session-canceled outbox event.
- Expected behavior: Current behavior is aligned with the documented application service responsibility.
- Impact: No production domain correctness issue found in the main reservation/cancellation orchestration.
- Recommended action: Keep follow-ups from `docs/11-reservation-transaction-concurrency-audit.md` focused on DB-backed lock-conflict evidence rather than changing orchestration.
- Risk and effort estimate: Low risk, no immediate implementation effort.

### F-03: Duplicate reservation and available-pass selection guardrails are implemented at the right boundaries

- Category: `No issue`
- Source document reference: `docs/01-prd.md:126`, `docs/02-functional-spec.md:341`, `docs/02-functional-spec.md:460`, `docs/03-domain-model.md:258`
- Code or test reference: `src/main/kotlin/dev/climbdesk/reservation/application/ReservationApplicationService.kt:41`, `src/main/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassJpaRepository.kt:9`, `src/main/resources/db/migration/V1__create_mvp_schema.sql`
- Current behavior: Duplicate CONFIRMED reservations are checked in the application service and backed by PostgreSQL partial unique index `uk_reservations_confirmed_member_class`. Available pass selection filters ACTIVE, positive remaining count, not-expired passes and orders by `expires_at asc nulls last`, `issued_at asc`, and `id asc`.
- Expected behavior: Current behavior matches the documented cross-instance and selection policies.
- Impact: The implementation has both domain/application checks and DB-level backup where the documents require it.
- Recommended action: No change.
- Risk and effort estimate: Low risk, no effort.

### F-04: Member activation appears in lower-priority documents but is outside PRD MVP scope

- Category: `Doc drift`
- Source document reference: `docs/01-prd.md:67`, `docs/02-functional-spec.md:277`, `docs/02-functional-spec.md:511`, `docs/03-domain-model.md:182`, `docs/03-domain-model.md:642`, `docs/07-test-strategy.md:199`
- Code or test reference: `src/main/kotlin/dev/climbdesk/member/domain/Member.kt:22`, `src/main/kotlin/dev/climbdesk/member/application/MemberApplicationService.kt:47`, `src/test/kotlin/dev/climbdesk/member/domain/MemberTest.kt:52`
- Current behavior: Member supports create, active eligibility validation, and idempotent deactivate. There is no Member `activate()` method, no application service activation use case, and no API endpoint for member reactivation.
- Expected behavior: PRD and Functional Spec feature sections list member registration/query/deactivation, not member reactivation. Domain Model state transition and Test Strategy currently imply `INACTIVE -> ACTIVE` coverage.
- Impact: A future implementer could incorrectly add member reactivation as MVP scope because lower-priority documents ask for it.
- Recommended action: Create a documentation follow-up to either remove Member activation expectations from Domain Model/Test Strategy or explicitly record a product decision to add reactivation to MVP before implementation.
- Risk and effort estimate: Low production risk, low documentation effort.

### F-05: MemberPass expire/cancel and ClassSession close are documented as transitions without MVP use cases

- Category: `Doc drift`
- Source document reference: `docs/02-functional-spec.md:518`, `docs/03-domain-model.md:266`, `docs/03-domain-model.md:651`, `docs/03-domain-model.md:666`
- Code or test reference: `src/main/kotlin/dev/climbdesk/pass/domain/MemberPass.kt:25`, `src/main/kotlin/dev/climbdesk/pass/domain/MemberPass.kt:54`, `src/main/kotlin/dev/climbdesk/classsession/domain/ClassSession.kt:20`, `src/main/kotlin/dev/climbdesk/classsession/domain/ClassSession.kt:39`
- Current behavior: MemberPass has `EXPIRED` and `CANCELED` statuses and correctly treats them as unavailable or non-restorable where relevant, but there is no `expire()` or `cancel()` use case. ClassSession has a `CLOSED` status and reservation rejects non-OPEN sessions, but there is no `close()` use case or API.
- Expected behavior: PRD MVP scope includes pass issuance/consume/restore/history and class create/query/cancel/capacity management. It does not include pass cancellation, batch expiration, class closing, or class update workflows.
- Impact: The code is scope-conservative, but the documents can be read as requiring unimplemented lifecycle operations.
- Recommended action: Create a documentation follow-up to mark these transitions as future/internal states, or make an explicit product decision before adding them as MVP work.
- Risk and effort estimate: Low production risk, low documentation effort.

### F-06: Authorization business rules are enforced at the API/security boundary, not inside application service commands

- Category: `Acceptable deviation`
- Source document reference: `docs/02-functional-spec.md:83`, `docs/02-functional-spec.md:143`, `docs/07-test-strategy.md:257`
- Code or test reference: `src/main/kotlin/dev/climbdesk/auth/presentation/AdminUserController.kt:20`, `src/main/kotlin/dev/climbdesk/auth/infrastructure/adapter/SecurityConfig.kt:20`, `src/test/kotlin/dev/climbdesk/auth/presentation/AdminUserCreateIntegrationTest.kt:68`
- Current behavior: AdminUser management application service methods do not receive an actor/requester command field. MANAGER-only enforcement is handled by Spring method security on the controller and verified by integration tests for create and role change. API contract audit already captured missing STAFF-forbidden tests for activate/deactivate.
- Expected behavior: Functional Spec flow text says requester role is checked, while Architecture/PROJECT_RULES primarily require business logic to stay out of controllers. For HTTP entry points, method security is a reasonable authorization boundary as long as tests prove it.
- Impact: Not a production bug for current API use. Direct application service invocation in tests or future non-HTTP adapters would bypass role checks unless those adapters add equivalent authorization.
- Recommended action: No implementation change now. Track the activate/deactivate STAFF-forbidden test follow-up from `docs/12-api-contract-alignment-audit.md`; if a non-HTTP adapter is introduced, revisit requester-aware application commands.
- Risk and effort estimate: Low current risk, low test effort already tracked elsewhere.

## Follow-up 티켓 권고

1. Member activation scope 문서 정리.
   - Goal: PRD/Functional Spec/Domain Model/Test Strategy가 Member reactivation을 MVP에 포함하는지 하나로 결정한다.
   - Acceptance: Member activation is either removed from lower-priority MVP docs/test expectations, or a product decision adds a concrete activation API/use case before implementation.

2. Non-exposed lifecycle transitions 문서 정리.
   - Goal: MemberPass `expire()`/`cancel()` and ClassSession `close()` are clearly marked as future/internal states, or promoted into explicit MVP use cases by product decision.
   - Acceptance: Domain Model and Test Strategy no longer imply implementation obligations that are absent from PRD/Functional Spec MVP feature flows.

## 감사 결론

이번 도메인 규칙 감사에서 production bug는 확인되지 않았다. 현재 구현은 문서화된 핵심 MVP 예약 정합성 규칙을 지키고, scope guardrail을 넓히지 않은 상태다. 후속 조치는 구현보다 문서 정리 성격이 강하며, 기존 API/트랜잭션 감사에서 이미 분리된 테스트 보강 티켓과 함께 처리하면 된다.
