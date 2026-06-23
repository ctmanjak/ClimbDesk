# 예약 트랜잭션 및 동시성 감사

> 원본 티켓: Notion `Review reservation transaction and concurrency tests for real DB correctness`
>
> 작성일: 2026-06-10
>
> 범위: 예약 생성/취소 rollback 테스트, 정원 및 중복 예약 동시성 테스트, MemberPass optimistic lock 테스트, pessimistic lock 실패 처리 커버리지, Testcontainers 사용 여부, 트랜잭션 경계, 최종 DB 상태 검증.

## 요약

예약 테스트 스위트는 대부분 PostgreSQL Testcontainers와 Flyway 기반 schema validation을 사용하고, 실패 이후 최종 DB 상태를 직접 검증한다. 따라서 정원 초과 방지, 중복 예약 방지, outbox rollback 같은 핵심 주장에는 유의미한 DB-backed 증거가 있다.

가장 큰 공백은 예약 workflow 테스트가 실제 DB에서 발생한 `MemberPass` optimistic lock 충돌을 재현하지 않는다는 점이다. `MEMBER_PASS_VERSION_CONFLICT` rollback 테스트는 `MemberPassRepository`를 실패하는 테스트 빈으로 교체한다. 이 방식은 예외 발생 후 트랜잭션 rollback은 검증하지만, `ReservationApplicationService` 안에서 실제 JPA/PostgreSQL version conflict가 발생하고 매핑되는 경로까지 검증하지는 않는다.

pessimistic lock 실패 처리도 MVC exception mapping 경계에서 mocked service exception으로만 검증된다. PostgreSQL-backed 테스트가 lock timeout을 강제로 만들거나, 예약 생성/취소 중 실제 pessimistic locking failure가 `CONCURRENCY_CONFLICT`로 매핑되는지 검증하지 않는다.

## Findings

### F-01: 예약 workflow optimistic lock rollback이 DB로 재현되지 않음

- Category: `Missing test`
- Source document reference: `docs/07-test-strategy.md`의 CT-03 및 transaction rollback 전략은 MemberPass optimistic lock 충돌과 최종 DB 상태 검증을 요구한다.
- Code or test reference: `src/test/kotlin/dev/climbdesk/reservation/application/ReservationCreationMemberPassFailureIntegrationTest.kt:74`, `src/test/kotlin/dev/climbdesk/reservation/application/ReservationCancellationMemberPassFailureIntegrationTest.kt:76`
- Current behavior: 예약 생성/취소 rollback 테스트는 Testcontainers를 사용하고 최종 DB 상태를 검증한다. 하지만 충돌 자체는 `ObjectOptimisticLockingFailureException`을 던지는 `@Primary` 테스트용 `MemberPassRepository`로 주입한다.
- Expected behavior: 최소 하나의 예약 workflow 테스트는 실제 `MemberPassPersistenceAdapter`와 JPA `@Version` 컬럼을 통해 충돌을 발생시킨 뒤 `Reservation`, `ClassSession`, `MemberPass`, `PassUsageHistory`, `OutboxEvent` rollback을 검증해야 한다.
- Impact: 현재 suite는 optimistic-lock 형태의 예외가 던져졌을 때 rollback되는지는 증명한다. 그러나 전체 예약 use case가 실제 PostgreSQL-backed stale version conflict를 매핑하고 rollback하는지는 증명하지 못한다.
- Recommended action: DB-backed 예약 workflow optimistic locking 테스트를 추가하는 follow-up 티켓을 만든다. deterministic rollback 커버리지로서 가치가 있다면 기존 injected-failure 테스트는 유지한다.
- Risk and effort estimate: 중간 risk, 중간 effort. stale `MemberPass` snapshot 주변의 deterministic transaction coordination이 필요할 수 있다.

### F-02: Pessimistic lock 실패 응답이 mock으로만 검증됨

- Category: `Missing test`
- Source document reference: `docs/04-api-spec.md`는 예약 생성/취소의 `CONCURRENCY_CONFLICT`를 명시한다. `docs/07-test-strategy.md`는 핵심 통합 테스트에서 PostgreSQL `for update` 동작 검증을 요구한다.
- Code or test reference: `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationFailureResponseControllerTest.kt:42`, `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationFailureResponseControllerTest.kt:70`
- Current behavior: Controller 테스트는 `ReservationApplicationService`가 `PessimisticLockingFailureException`을 던지도록 mock 처리하고 `CONCURRENCY_CONFLICT` 응답만 검증한다.
- Expected behavior: DB-backed 통합 테스트가 잠긴 `class_sessions` 또는 `reservations` row에서 실제 pessimistic lock timeout/failure를 유도하고 문서화된 API 응답을 검증해야 한다.
- Impact: HTTP exception mapping은 검증되어 있다. 하지만 PostgreSQL/JPA에서 발생한 실제 lock failure가 예약 생성/취소 경로에서 일관되게 노출되는지는 증명하지 못한다.
- Recommended action: PostgreSQL Testcontainers, 짧은 transaction-level lock timeout, held `FOR UPDATE` row lock을 사용하는 lock-timeout 통합 테스트 follow-up 티켓을 만든다.
- Risk and effort estimate: 중간 risk, 중간 effort. lock-timeout 테스트는 조율이 부정확하면 flaky해질 수 있다.

### F-03: 정원 및 중복 예약 동시성 커버리지는 DB-backed임

- Category: `No issue`
- Source document reference: `docs/07-test-strategy.md`, CT-01 및 CT-02.
- Code or test reference: `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationCreationIntegrationTest.kt:393`, `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationCreationIntegrationTest.kt:422`
- Current behavior: 테스트는 PostgreSQL Testcontainers 위에서 concurrent MockMvc 예약 요청을 실행한 뒤 성공/실패 개수와 예약, class session reserved count, member pass count, usage history, outbox event의 최종 DB 상태를 검증한다.
- Expected behavior: 현재와 동일.
- Impact: 동시 API 요청 상황에서 정원 초과 방지와 CONFIRMED 중복 예약 방지를 검증하는 의미 있는 증거다.
- Recommended action: 그대로 유지한다. 향후 flakiness가 생기면 assertion을 약화하지 말고 shared concurrency fixture를 추출한다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-04: 예약 outbox rollback 테스트는 최종 DB 상태를 검증함

- Category: `No issue`
- Source document reference: `docs/07-test-strategy.md`, RT-02 및 RT-03/RT-04 rollback 기대사항.
- Code or test reference: `src/test/kotlin/dev/climbdesk/reservation/application/ReservationCreationOutboxFailureIntegrationTest.kt:76`, `src/test/kotlin/dev/climbdesk/reservation/application/ReservationCancellationOutboxFailureIntegrationTest.kt:81`
- Current behavior: 테스트는 Testcontainers 위에서 실제 application service transaction을 통과하고, outbox recording failure 이후 최종 DB 상태를 검증한다.
- Expected behavior: 현재와 동일.
- Impact: 예약 생성/취소와 outbox persistence 실패의 원자성 주장을 뒷받침한다.
- Recommended action: 그대로 유지한다. 주된 검증 대상은 PostgreSQL outbox constraint 동작이 아니라 recorder 실패 이후 transaction rollback이므로 injected outbox failure 방식은 허용 가능한 편차다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-05: MemberPass persistence optimistic locking은 실제 adapter 커버리지가 있음

- Category: `No issue`
- Source document reference: `docs/07-test-strategy.md`의 MemberPass repository version 및 optimistic lock 기대사항.
- Code or test reference: `src/test/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassPersistenceAdapterIntegrationTest.kt:123`, `src/test/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassPersistenceAdapterIntegrationTest.kt:147`
- Current behavior: Repository integration 테스트는 실제 stale snapshot과 `MemberPassPersistenceAdapter`를 통한 concurrent save를 사용하고, 하나의 version conflict와 최종 DB 상태를 검증한다.
- Expected behavior: 현재와 동일.
- Impact: persistence adapter 레벨에는 실제 optimistic locking 증거가 있다. 다만 예약 workflow에는 별도의 DB-backed conflict 테스트가 여전히 필요하다.
- Recommended action: 그대로 유지하고, 예약 workflow follow-up을 만들 때 이 테스트들을 참고한다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-06: 예약 생성과 수업 취소 race는 DB-backed invariant 커버리지가 있음

- Category: `No issue`
- Source document reference: `docs/07-test-strategy.md`, CT-04.
- Code or test reference: `src/test/kotlin/dev/climbdesk/classsession/presentation/ClassSessionCancellationIntegrationTest.kt:211`
- Current behavior: class session cancellation과 reservation creation을 PostgreSQL Testcontainers 위에서 MockMvc로 동시에 실행한다. 최종 assertion은 canceled class session에 CONFIRMED reservation이 남지 않는지, pass count가 복구되는지, committed outbox event가 committed state와 맞는지 검증한다.
- Expected behavior: 현재와 동일.
- Impact: 문서화된 race invariant에 대해 유용한 커버리지를 제공한다.
- Recommended action: 그대로 유지한다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-07: 예약 취소와 예약 생성 동시 실행 커버리지가 없음

- Category: `Missing test`
- Source document reference: `docs/07-test-strategy.md`, CT-05는 예약 취소와 예약 생성 동시 실행 후 `reservedCount <= capacity`, `CONFIRMED Reservation count`와 `reservedCount` 일치, 사용 이력 일관성 검증을 요구한다.
- Code or test reference: `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationCancellationIntegrationTest.kt:264`, `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationCreationIntegrationTest.kt:394`, `src/test/kotlin/dev/climbdesk/classsession/presentation/ClassSessionCancellationIntegrationTest.kt:212`
- Current behavior: 현재 동시성 테스트는 정원 초과 예약, 중복 예약, 같은 예약 취소 2회, 수업 취소와 예약 생성 race를 검증한다. 하지만 한 수업에서 기존 예약 취소와 새 예약 생성이 동시에 실행되는 CT-05 시나리오는 별도 테스트로 검증하지 않는다.
- Expected behavior: PostgreSQL Testcontainers 기반 테스트가 예약 취소 요청과 새 예약 생성 요청을 동시에 실행하고, 최종 `reservedCount`, `CONFIRMED` 예약 수, `MemberPass` 차감/복구, `PassUsageHistory`, `OutboxEvent`가 committed 결과와 일치하는지 검증해야 한다.
- Impact: 문서화된 동시성 invariant 중 하나가 자동화 증거 없이 남아 있다. 현재 확인된 production bug는 아니지만, reservation cancel/create race 회귀를 CI에서 직접 차단하지 못한다.
- Recommended action: CT-05 DB-backed 동시성 테스트를 추가하는 follow-up 티켓을 만든다.
- Risk and effort estimate: 중간 risk, 중간 effort. 동시 성공/실패 결과가 타이밍에 따라 달라질 수 있으므로 최종 invariant 중심 assertion이 필요하다.

## Follow-up 티켓 권고

1. DB-backed 예약 workflow optimistic lock conflict 커버리지 추가.
   - Goal: `ReservationApplicationService.reserveClass` 및/또는 `cancelReservation` 안에서 실제 `MemberPassJpaEntity.version` 충돌을 통해 `MEMBER_PASS_VERSION_CONFLICT`를 재현한다.
   - Acceptance: workflow attempt 중 성공/실패 결과가 기대와 일치하고, 실패한 트랜잭션이 reservation/class session/member pass/history/outbox 상태를 변경하지 않는다.

2. 예약 생성/취소에 대한 PostgreSQL-backed pessimistic lock timeout 커버리지 추가.
   - Goal: `class_sessions` 또는 `reservations`에 `FOR UPDATE` lock을 잡은 상태에서 짧은 lock timeout으로 예약 API/service 경로를 실행하고 `CONCURRENCY_CONFLICT`를 검증한다.
   - Acceptance: Testcontainers, 독립 트랜잭션, deterministic latch, 최종 DB 상태 assertion을 사용한다.

3. 예약 취소와 예약 생성 동시 실행 CT-05 커버리지 추가.
   - Goal: 같은 수업에서 기존 CONFIRMED 예약 취소와 새 예약 생성을 동시에 실행해 최종 예약 수, `reservedCount`, 이용권 차감/복구 이력, outbox event가 일관적인지 검증한다.
   - Acceptance: Testcontainers, 독립 트랜잭션, final-state assertion을 사용하고 타이밍 의존적인 특정 승패 조합에 과도하게 의존하지 않는다.

## 감사 결론

이번 감사에서 production bug는 확인되지 않았다. 현재 suite는 정원 초과, 중복 예약, outbox rollback, MemberPass repository optimistic locking, class-session-cancel race invariant에 대해 강한 증거를 갖고 있다. 다만 workflow 레벨의 실제 DB optimistic lock conflict, 실제 DB pessimistic lock failure handling, 예약 취소와 예약 생성 동시 실행 CT-05는 증거가 부족하므로 follow-up 테스트가 필요하다.
