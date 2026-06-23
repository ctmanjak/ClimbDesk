# ClimbDesk Test Strategy v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `07 - Test Strategy`.
> This Markdown file is a repository-local snapshot synced on 2026-05-24 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: ClimbDesk MVP의 핵심 품질 목표인 예약 정합성, 트랜잭션 원자성, 동시성 제어, 권한 정책, API 계약을 테스트 가능한 기준으로 정의한다.

---

# 1. 문서 상태

## Status

```plain text
Draft
```

## Source Documents

- `01 - PRD`: MVP 범위, 핵심 비즈니스 규칙, 성공 기준
- `02 - Functional Spec`: 기능 행위, 상태 변화, 예외, 테스트 기준
- `03 - Domain Model`: Aggregate, 상태 전이, Repository 경계, 이벤트 정책
- `04 - API Spec`: REST API 계약, error code, DTO 기준
- `05 - Architecture`: 레이어 구조, Port/Adapter, 트랜잭션/이벤트 아키텍처
- `06 - Database Design`: PostgreSQL 스키마, 제약조건, 인덱스, 락 쿼리

## 작성 원칙

- 테스트는 구현 상세를 무작정 고정하지 않고, 문서화된 비즈니스 규칙과 불변조건을 검증한다.
- 포트폴리오 설득력을 위해 단순 CRUD 성공 테스트보다 예약 정합성, 동시성, 트랜잭션 롤백, DB 제약조건 검증을 우선한다.
- PostgreSQL 의존 기능을 사용하므로 H2 기반 통합 테스트는 기본 전략에서 제외한다.
- 핵심 통합 테스트는 PostgreSQL Testcontainers로 실행한다.
- 모든 실패 케이스는 API Spec의 error code와 매핑되어야 한다.

---

# 2. Quality Goals

## Primary Quality Goals

```plain text
1. ClassSession.reservedCount <= ClassSession.capacity 항상 보장
2. MemberPass.remainingCount >= 0 항상 보장
3. 같은 회원은 같은 수업에 CONFIRMED 예약을 2개 이상 가질 수 없음
4. 예약 생성/취소/수업 취소는 원자적으로 처리
5. 도메인 상태 변경과 OutboxEvent 저장은 같은 트랜잭션에 포함
6. 인증/인가 실패는 명확한 HTTP status와 error code로 반환
```

## Secondary Quality Goals

- Aggregate 상태 전이가 명확하게 테스트된다.
- Repository 특수 조회와 인덱스 전제가 테스트된다.
- Controller DTO validation과 exception mapping이 테스트된다.
- 테스트 데이터는 재사용 가능하고 가독성 있게 구성된다.
- CI에서 핵심 테스트가 반복 실행 가능해야 한다.

---

# 3. Test Scope

## In Scope

- Domain unit test
- Application Service test
- Repository integration test
- API slice/integration test
- Security authorization test
- Transaction rollback test
- Concurrency test
- OutboxEvent persistence test
- Database constraint test

## Out of Scope

- 프론트엔드 E2E 테스트
- 실제 결제 연동 테스트
- 실제 알림 발송 테스트
- 실제 SQS/Kafka broker 연동 테스트
- 대규모 부하 테스트
- 운영 모니터링/알림 테스트
- 멀티 지점/대기 예약/출석 체크 테스트

---

# 4. Test Pyramid

```plain text
            API / Scenario Tests
          -----------------------
        Application Service Tests
      -----------------------------
    Repository Integration Tests
  ---------------------------------
Domain Unit Tests / Value Object Tests
```

| Layer | 목적 | 주요 도구 | DB 사용 |
| --- | --- | --- | --- |
| Domain Unit | Aggregate 상태 전이와 불변조건 검증 | JUnit 5, AssertJ | No |
| Application Service | 유스케이스 orchestration, 트랜잭션 경계, 권한 정책 검증 | Spring Boot Test, MockK, Testcontainers | Case by case |
| Repository Integration | JPA mapping, lock query, unique index, check constraint 검증 | DataJpaTest, Testcontainers PostgreSQL | Yes |
| API Test | HTTP contract, validation, auth, error response 검증 | MockMvc 또는 WebTestClient | Optional |
| Concurrency Test | 동시 예약/취소 시 핵심 invariant 유지 검증 | Spring Boot Test, ExecutorService, CountDownLatch, Testcontainers | Yes |

---

# 5. Test Environment

## Runtime

```plain text
JDK 21
Kotlin
Spring Boot
JUnit 5
AssertJ
Gradle
PostgreSQL 16+ Testcontainers
```

## Database Strategy

- 통합 테스트 DB는 PostgreSQL Testcontainers를 사용한다.
- PostgreSQL partial unique index, `for update`, `timestamptz`, check constraint를 실제 DB에서 검증한다.
- H2는 PostgreSQL과 lock/constraint/index 동작이 달라 핵심 테스트 환경으로 사용하지 않는다.
- 핵심 통합 테스트는 Hibernate schema 생성이 아니라 Flyway baseline migration을 적용한 schema에서 실행한다.
- `ddl-auto=create-drop`는 핵심 repository/application/API integration test의 기본 전략으로 사용하지 않는다.
- JPA mapping은 migration 적용 후 `validate` 또는 동등한 검증 방식으로 확인한다.
- 전환기 예외: MVP baseline migration 티켓이 구현되기 전까지 `MemberQueryIntegrationTest` 같은 기존 통합 테스트는 `ddl-auto=create-drop`를 계속 사용할 수 있다. 새로 작성하거나 migration하는 핵심 통합 테스트는 Flyway가 생성한 schema와 JPA validation을 사용한다.

## Time Strategy

- 시간 의존 로직은 `Clock`을 주입해 고정한다.
- 만료 여부, 발급일 정렬, 이벤트 발생 시각은 fixed clock으로 검증한다.

## Transaction Strategy

- 트랜잭션 테스트는 테스트 메서드의 자동 롤백에만 의존하지 않는다.
- 동시성 테스트는 각 thread가 독립 트랜잭션을 열도록 Application Service를 호출한다.
- rollback 검증은 실패 이후 DB 상태를 별도 트랜잭션에서 재조회한다.

---

# 6. Test Data Strategy

## Fixture Principles

- 테스트 데이터 생성은 fixture builder를 사용한다.
- 테스트의 의도를 드러내기 위해 기본값은 유효한 상태로 둔다.
- 실패 조건에 필요한 값만 override한다.
- ID에 의존하는 테스트는 저장 후 반환된 ID를 사용한다.

## Fixture Candidates

```plain text
AdminUserFixture
MemberFixture
PassProductFixture
MemberPassFixture
ClassSessionFixture
ReservationFixture
OutboxEventFixture
```

## Data Cleanup

동시성 테스트는 thread commit 결과를 확인해야 하므로 truncate cleanup을 권장한다.

```plain text
pass_usage_histories
outbox_events
reservations
member_passes
pass_products
class_sessions
members
admin_users
```

---

# 7. Domain Unit Test Strategy

## 목표

DB와 Spring 없이 Aggregate의 상태 전이, 불변조건, 도메인 예외를 빠르게 검증한다.

## AdminUser

- ACTIVE AdminUser는 deactivate 후 INACTIVE가 된다.
- INACTIVE AdminUser는 activate 후 ACTIVE가 된다.
- changeRole은 MANAGER, STAFF로 변경 가능하다.
- passwordHash만 보관하고 raw password를 도메인에 저장하지 않는다.

## Member

- 신규 Member는 ACTIVE로 생성된다.
- ACTIVE Member는 deactivate 후 INACTIVE가 된다.
- INACTIVE Member에 대한 deactivate는 멱등 처리된다.
- Member reactivation(`INACTIVE -> ACTIVE`)은 MVP public use case/API가 아니므로 MVP 테스트 의무에 포함하지 않는다. 향후 제품 결정으로 재활성화 API가 추가될 때 domain/application/API 테스트 범위를 정의한다.

## PassProduct

- COUNT_PASS 상품은 totalCount 1 이상일 때 생성된다.
- totalCount 0 이하는 생성 실패한다.

## MemberPass

- ACTIVE MemberPass는 consume 시 remainingCount가 1 감소한다.
- remainingCount가 0이 되면 EXHAUSTED가 된다.
- remainingCount가 0인 MemberPass는 추가 consume에 실패한다.
- EXHAUSTED MemberPass는 restore 후 remainingCount가 1 이상이고 만료되지 않았다면 ACTIVE가 된다.
- EXPIRED MemberPass는 restore 후 remainingCount는 증가하지만 상태는 EXPIRED로 유지한다.
- CANCELED MemberPass는 consume/restore 모두 실패한다.
- consume은 `CONSUME / RESERVATION_CONFIRMED` 이력을 남긴다.
- restore는 사유에 따라 `RESTORE / RESERVATION_CANCELED` 또는 `RESTORE / CLASS_SESSION_CANCELED` 이력을 남긴다.
- MemberPass `expire()`/`cancel()` use case/API, batch expiration, pass cancellation workflow는 MVP 테스트 의무가 아니다. `EXPIRED`/`CANCELED`는 현재 MVP에서 guard behavior를 검증하는 내부/future lifecycle state로만 다룬다.

## ClassSession

- OPEN ClassSession은 reserveSeat 시 reservedCount가 1 증가한다.
- reservedCount가 capacity에 도달하면 추가 reserveSeat는 실패한다.
- cancelSeat는 reservedCount를 1 감소시킨다.
- reservedCount가 0일 때 cancelSeat는 실패한다.
- cancel 후 status는 CANCELED가 된다.
- CANCELED ClassSession은 reserveSeat에 실패한다.
- ClassSession `close()` use case/API와 class update/closing workflow는 MVP 테스트 의무가 아니다. `CLOSED`는 현재 MVP에서 reservation guard behavior를 검증하는 내부/future lifecycle state로만 다룬다.

## Reservation

- Reservation은 CONFIRMED로 생성된다.
- CONFIRMED Reservation은 cancel 후 CANCELED가 된다.
- CANCELED Reservation은 다시 cancel할 수 없다.
- cancelReason은 USER_REQUESTED 또는 CLASS_SESSION_CANCELED만 허용한다.

---

# 8. Application Service Test Strategy

## 목표

여러 Aggregate를 조율하는 유스케이스가 문서화된 순서와 트랜잭션 경계로 동작하는지 검증한다.

## 권장 방식

- 순수 orchestration 테스트는 Repository Port를 mock 처리할 수 있다.
- 트랜잭션/락/DB constraint가 핵심인 유스케이스는 PostgreSQL Testcontainers 기반 통합 테스트로 검증한다.
- 예약 생성, 예약 취소, 수업 취소는 반드시 실제 DB 통합 테스트를 포함한다.

## AuthApplicationService

- ACTIVE AdminUser는 올바른 인증 정보로 로그인 성공한다.
- 잘못된 password는 INVALID_CREDENTIALS로 실패한다.
- INACTIVE AdminUser는 ADMIN_USER_INACTIVE로 실패한다.

## AdminUserApplicationService

- MANAGER는 AdminUser를 생성할 수 있다.
- STAFF는 AdminUser 생성에 실패한다.
- 중복 email은 DUPLICATE_ADMIN_USER_EMAIL로 실패한다.
- MANAGER는 Role을 변경할 수 있다.
- STAFF는 Role 변경에 실패한다.
- 마지막 ACTIVE MANAGER를 STAFF로 변경할 수 없다.
- 마지막 ACTIVE MANAGER를 INACTIVE로 변경할 수 없다.

## ReservationApplicationService.reserveClass

### Success Criteria

```plain text
Reservation CONFIRMED 생성
ClassSession.reservedCount + 1
MemberPass.remainingCount - 1
PassUsageHistory CONSUME 기록
OutboxEvent ReservationConfirmedEvent 기록
```

### Failure Cases

- Member not found.
- Member INACTIVE.
- ClassSession not found.
- ClassSession CLOSED 또는 CANCELED.
- ClassSession full.
- duplicate CONFIRMED reservation.
- available MemberPass 없음.
- MemberPass optimistic lock conflict.
- OutboxEvent 저장 실패 시 전체 rollback.

## ReservationApplicationService.cancelReservation

### Success Criteria

```plain text
Reservation CANCELED 변경
ClassSession.reservedCount - 1
MemberPass.remainingCount + 1
PassUsageHistory RESTORE / RESERVATION_CANCELED 기록
OutboxEvent ReservationCanceledEvent 기록
```

## ClassSessionApplicationService.cancelClassSession

### Success Criteria

```plain text
ClassSession CANCELED 변경
해당 수업의 CONFIRMED Reservation 모두 CANCELED 변경
각 MemberPass remainingCount + 1
각 PassUsageHistory RESTORE / CLASS_SESSION_CANCELED 기록
ClassSession.reservedCount = 0
OutboxEvent ClassSessionCanceledEvent 기록
```

---

# 9. Repository Integration Test Strategy

## AdminUserRepository

- email unique constraint가 동작한다.
- findByEmail이 AdminUser를 조회한다.
- countActiveManagers가 ACTIVE + MANAGER만 집계한다.

## MemberRepository

- phone unique constraint가 동작한다.
- status check constraint가 동작한다.
- createdAt desc, id desc 정렬 조회가 기대대로 동작한다.

## MemberPassRepository

- remainingCount는 0 미만이 될 수 없다.
- remainingCount는 totalCount를 초과할 수 없다.
- version은 optimistic lock으로 동작한다.
- findAvailablePassForUse는 만료일 빠른 순, 발급일 빠른 순, id 오름차순으로 선택한다.
- EXPIRED, EXHAUSTED, CANCELED, remainingCount 0인 pass는 선택되지 않는다.

## ClassSessionRepository

- startsAt < endsAt constraint가 동작한다.
- capacity >= 1 constraint가 동작한다.
- reservedCount <= capacity constraint가 동작한다.
- findByIdForUpdate는 동일 row에 대해 비관적 락을 획득한다.

## ReservationRepository

- CONFIRMED 예약에 대해 `(member_id, class_session_id)` partial unique index가 동작한다.
- CANCELED 예약이 있는 경우 같은 회원/수업에 CONFIRMED 재예약이 가능하다.
- existsConfirmedByMemberIdAndClassSessionId가 CONFIRMED만 조회한다.
- findConfirmedByClassSessionId가 CONFIRMED 예약만 반환한다.

## OutboxEventRepository

- OutboxEvent payload가 jsonb로 저장된다.
- pending event 조회는 `nextRetryAt asc nulls first, id asc` 기준으로 안정적으로 정렬된다.
- status check constraint가 동작한다.

---

# 10. API Test Strategy

## 공통 API Tests

- 보호 API는 Authorization header가 없으면 401을 반환한다.
- 유효하지 않은 JWT는 401을 반환한다.
- Role 권한이 부족하면 403을 반환한다.
- validation 실패는 400 VALIDATION_FAILED와 details를 반환한다.
- 비즈니스 규칙 위반은 409 계열 error code를 반환한다.
- not found는 404 `*_NOT_FOUND`를 반환한다.
- ErrorResponse는 timestamp, status, code, message, path, traceId를 포함한다.
- API date-time 응답은 persisted response timestamp와 ErrorResponse timestamp 모두 ISO-8601 UTC `Z` 형식을 사용한다.

## 주요 API Tests

- `POST /api/v1/auth/login` 성공 시 accessToken을 반환한다.
- INACTIVE AdminUser는 403 ADMIN_USER_INACTIVE를 반환한다.
- MANAGER는 `POST /api/v1/admin-users` 성공, STAFF는 403.
- 마지막 ACTIVE MANAGER 보호 실패는 409 LAST_ACTIVE_MANAGER_REQUIRED.
- `POST /api/v1/members` 성공 시 201, 중복 phone은 409 DUPLICATE_MEMBER_PHONE.
- `POST /api/v1/pass-products` 성공 시 201, totalCount 0은 400 VALIDATION_FAILED.
- `POST /api/v1/member-passes` 성공 시 snapshot과 remainingCount를 반환한다.
- INACTIVE 회원에게 이용권 발급 시 409 MEMBER_INACTIVE.
- `POST /api/v1/class-sessions` 성공 시 OPEN, reservedCount 0 반환.
- capacity 0 또는 startsAt >= endsAt은 400 VALIDATION_FAILED.
- `PATCH /api/v1/class-sessions/{id}/cancel` 성공 시 CANCELED, reservedCount 0 반환.
- `POST /api/v1/reservations` 성공 시 201, CONFIRMED 반환.
- 정원 초과는 409 CLASS_SESSION_FULL.
- 중복 예약은 409 DUPLICATE_RESERVATION.
- 이용권 부족은 409 MEMBER_PASS_NOT_AVAILABLE.
- `PATCH /api/v1/reservations/{id}/cancel` 성공 시 CANCELED 반환.
- 이미 CANCELED 예약 취소는 409 RESERVATION_ALREADY_CANCELED.

---

# 11. Concurrency Test Strategy

## 공통 원칙

- `@SpringBootTest` + PostgreSQL Testcontainers를 사용한다.
- 같은 Application Service bean을 여러 thread에서 호출한다.
- thread 시작 시점을 `CountDownLatch`로 맞춘다.
- 각 호출은 독립 트랜잭션에서 실행되어야 한다.
- 테스트 종료 후 DB를 재조회해 최종 invariant를 검증한다.
- 성공/실패 개수와 최종 DB 상태를 함께 검증한다.

## CT-01. 동일 수업 정원 초과 방지

### Setup

```plain text
capacity = 10
ACTIVE members = 30
각 member는 remainingCount 1 이상인 MemberPass 보유
동일 ClassSession OPEN
```

### Expected Result

```plain text
성공 예약 수 = 10
실패 예약 수 = 20
ClassSession.reservedCount = 10
CONFIRMED Reservation count = 10
모든 MemberPass.remainingCount >= 0
OutboxEvent ReservationConfirmedEvent count = 10
```

## CT-02. 같은 회원의 중복 예약 방지

```plain text
성공 예약 수 = 1
실패 예약 수 = 4
CONFIRMED Reservation count = 1
ClassSession.reservedCount = 1
MemberPass 차감 = 1회
DUPLICATE_RESERVATION 또는 unique constraint 기반 conflict 반환
```

## CT-03. MemberPass optimistic lock 충돌

```plain text
성공 예약 수 = 1
실패 예약 수 = 1
MemberPass.remainingCount = 0
MemberPass.status = EXHAUSTED
CONFIRMED Reservation count = 1
실패한 예약의 ClassSession.reservedCount는 rollback되어야 함
```

## CT-04. 예약 생성과 수업 취소 동시 실행

공통 invariant:

```plain text
CANCELED ClassSession에 CONFIRMED Reservation이 남아 있으면 안 됨
MemberPass.remainingCount >= 0
OutboxEvent는 commit된 상태 변화에 대해서만 존재
```

## CT-05. 예약 취소와 예약 생성 동시 실행

```plain text
최종 reservedCount는 0 또는 1
reservedCount <= capacity 항상 유지
CONFIRMED Reservation count와 reservedCount 일치
MemberPass 차감/복구 이력 일관성 유지
```

---

# 12. Transaction and Rollback Test Strategy

## RT-01. 예약 생성 중 MemberPass consume 실패

```plain text
Reservation 저장 안 됨
ClassSession.reservedCount 변경 없음
MemberPass.remainingCount 변경 없음
PassUsageHistory 저장 안 됨
OutboxEvent 저장 안 됨
```

## RT-02. 예약 생성 중 OutboxEvent 저장 실패

```plain text
Reservation 저장 안 됨
ClassSession.reservedCount 변경 없음
MemberPass.remainingCount 변경 없음
PassUsageHistory 저장 안 됨
OutboxEvent 저장 안 됨
```

## RT-03. 예약 취소 중 MemberPass restore 실패

```plain text
Reservation은 CONFIRMED 유지
ClassSession.reservedCount 변경 없음
MemberPass.remainingCount 변경 없음
PassUsageHistory 저장 안 됨
OutboxEvent 저장 안 됨
```

## RT-04. 수업 취소 중 일부 MemberPass restore 실패

```plain text
ClassSession은 OPEN 유지
기존 Reservation은 CONFIRMED 유지
모든 MemberPass.remainingCount 변경 없음
PassUsageHistory 저장 안 됨
OutboxEvent 저장 안 됨
```

---

# 13. OutboxEvent Test Strategy

- 예약 생성 성공 시 ReservationConfirmedEvent OutboxEvent가 저장된다.
- 예약 취소 성공 시 ReservationCanceledEvent OutboxEvent가 저장된다.
- 수업 취소 성공 시 ClassSessionCanceledEvent OutboxEvent가 저장된다.
- 실패한 유스케이스는 OutboxEvent를 남기지 않는다.
- OutboxEvent payload는 aggregateId, memberId, classSessionId, memberPassId, occurredAt 등 이벤트별 필수 값을 포함한다.
- OutboxEvent status 초기값은 PENDING이다.
- pending event 조회는 `nextRetryAt asc nulls first, id asc` 기준으로 안정적으로 반환한다.
- 실제 SQS/Kafka 발행 성공 여부, 외부 메시지 idempotency, publisher retry/backoff는 MVP에서 테스트하지 않는다.

---

# 14. Security Test Strategy

## Authentication

- 로그인 API를 제외한 모든 보호 API는 JWT를 요구한다.
- 유효하지 않은 token은 401을 반환한다.
- 만료 token은 401을 반환한다.

## Authorization

| 기능 | MANAGER | STAFF |
| --- | --- | --- |
| AdminUser 생성 | 허용 | 거부 |
| AdminUser Role 변경 | 허용 | 거부 |
| AdminUser 활성화/비활성화 | 허용 | 거부 |
| 회원/이용권/수업/예약 운영 | 허용 | 허용 |

## Last Active Manager Protection

- 마지막 ACTIVE MANAGER는 STAFF로 변경할 수 없다.
- 마지막 ACTIVE MANAGER는 INACTIVE로 변경할 수 없다.
- ACTIVE MANAGER가 2명 이상이면 한 명을 STAFF 또는 INACTIVE로 변경할 수 있다.

---

# 15. Error Mapping Test Strategy

## Required Error Codes

```plain text
VALIDATION_FAILED
UNAUTHORIZED
INVALID_CREDENTIALS
FORBIDDEN
ADMIN_USER_INACTIVE
DUPLICATE_ADMIN_USER_EMAIL
LAST_ACTIVE_MANAGER_REQUIRED
DUPLICATE_MEMBER_PHONE
MEMBER_NOT_FOUND
MEMBER_INACTIVE
PASS_PRODUCT_NOT_FOUND
MEMBER_PASS_NOT_FOUND
MEMBER_PASS_NOT_AVAILABLE
MEMBER_PASS_VERSION_CONFLICT
CLASS_SESSION_NOT_FOUND
CLASS_SESSION_NOT_OPEN
CLASS_SESSION_FULL
CLASS_SESSION_ALREADY_CANCELED
RESERVATION_NOT_FOUND
RESERVATION_ALREADY_CANCELED
DUPLICATE_RESERVATION
CONCURRENCY_CONFLICT
INTERNAL_SERVER_ERROR
```

## Verification Points

- HTTP status가 error code 의미와 일치한다.
- message는 디버깅 가능한 수준으로 구체적이다.
- path는 요청 path를 포함한다.
- traceId가 존재한다.
- validation error는 details를 포함한다.

---

# 16. CI Test Strategy

## Recommended Pipeline

```plain text
1. compileKotlin
2. unitTest
3. repositoryIntegrationTest
4. applicationIntegrationTest
5. apiTest
6. concurrencyTest
7. build image optional
```

## Test Tagging

```plain text
@Tag("unit")
@Tag("integration")
@Tag("api")
@Tag("concurrency")
@Tag("slow")
```

## CI Gate

- unit test 실패 시 merge 금지.
- integration test 실패 시 merge 금지.
- concurrency 핵심 테스트 CT-01, CT-02, CT-03 실패 시 merge 금지.
- test coverage는 참고 지표로 사용하고, 핵심 invariant 테스트 통과를 더 중요하게 본다.

---

# 17. Coverage Policy

## Line Coverage Target

```plain text
Domain: 90%+
Application: 80%+
Presentation: 70%+
Infrastructure: 핵심 adapter 중심
```

## Invariant Coverage Target

```plain text
ClassSession.reservedCount <= ClassSession.capacity
MemberPass.remainingCount >= 0
CONFIRMED reservation uniqueness
Reservation create atomicity
Reservation cancel atomicity
ClassSession cancel atomicity
OutboxEvent atomic persistence
MANAGER-only admin management
Last Active Manager Protection
```

---

# 18. Traceability Matrix

| Business Rule | Test Type | Representative Tests |
| --- | --- | --- |
| ACTIVE 회원만 예약 가능 | Application/API | reserveClass_memberInactive_fails |
| OPEN 수업만 예약 가능 | Domain/Application/API | reserveClass_classSessionNotOpen_fails |
| 정원 초과 방지 | Domain/Integration/Concurrency | concurrentReserve_capacityNotExceeded |
| 중복 예약 방지 | Application/Repository/Concurrency | concurrentReserve_duplicateConfirmedReservationOnlyOneSuccess |
| 이용권 음수 차감 방지 | Domain/Repository/Concurrency | memberPass_consume_whenZero_fails |
| 예약 생성 원자성 | Application Integration | reserveClass_whenOutboxSaveFails_rollsBackAll |
| 예약 취소 복구 | Application Integration/API | cancelReservation_restoresSeatAndPass |
| 수업 취소 일괄 취소 | Application Integration/Concurrency | cancelClassSession_cancelsAllReservationsAndRestoresPasses |
| OutboxEvent 원자 저장 | Integration | reserveClass_savesOutboxEventInSameTransaction |
| MANAGER 전용 관리자 관리 | Security/API/Application | staff_createAdminUser_forbidden |
| 마지막 ACTIVE MANAGER 보호 | Application/API | deactivate_lastActiveManager_fails |

---

# 19. Test Naming Convention

## Pattern

```plain text
method_or_usecase_condition_expectedResult
```

## Examples

```plain text
reserveClass_whenClassSessionIsFull_failsWithClassSessionFull
reserveClass_whenConcurrentRequestsExceedCapacity_keepsReservedCountWithinCapacity
cancelReservation_whenConfirmed_restoresSeatAndMemberPass
cancelClassSession_whenConfirmedReservationsExist_cancelsAllAndRestoresPasses
createAdminUser_whenRequesterIsStaff_failsWithForbidden
changeRole_whenTargetIsLastActiveManager_failsWithLastActiveManagerRequired
```

---

# 20. Risk-based Test Priorities

## P0 - 반드시 자동화

- 동시 예약 정원 초과 방지
- 중복 예약 방지
- 예약 생성 원자성
- 예약 취소 복구 원자성
- 수업 취소 일괄 취소 원자성
- MemberPass optimistic lock 충돌
- OutboxEvent 원자 저장
- Last Active Manager Protection

## P1 - 자동화 권장

- API validation/error response
- JWT 인증/인가
- Repository query ordering
- DB check constraint
- PassUsageHistory 기록

## P2 - 선택적 자동화

- 단순 목록 조회 pagination
- 응답 DTO의 모든 표시 필드
- 멱등 처리 세부 응답
- 내부 adapter의 단순 mapping

---

# 21. Definition of Done

기능은 다음 조건을 만족해야 완료로 본다.

```plain text
1. Domain unit test 작성 및 통과
2. Application Service success/failure test 작성 및 통과
3. 필요한 Repository integration test 작성 및 통과
4. API contract test 작성 및 통과
5. Error code mapping test 작성 및 통과
6. 트랜잭션/동시성이 관련된 경우 PostgreSQL Testcontainers 테스트 작성 및 통과
7. OutboxEvent가 관련된 경우 원자 저장/rollback test 작성 및 통과
```

## MVP 완료 기준

```plain text
P0 테스트 100% 통과
주요 API happy path 테스트 통과
주요 error code 테스트 통과
PostgreSQL Testcontainers 기반 integration test 통과
CI에서 반복 실행 가능
```

---

# 22. Resolved Test Decisions

## RTD-01. Test Framework Style

```plain text
JUnit 5 + AssertJ를 기본 테스트 스택으로 사용한다.
```

## RTD-02. Test Task 분리

```plain text
test
integrationTest
concurrencyTest
```

## RTD-03. Outbox Publisher Test 범위

```plain text
현재 계획된 MVP 범위까지 진행한다.
```

MVP 테스트 범위는 OutboxEvent 저장, payload 검증, PENDING 상태, rollback 시 미저장 검증까지로 둔다.

## RTD-04. API Test DB 범위

```plain text
핵심 API는 full integration test로 검증한다.
```

예약 생성, 예약 취소, 수업 취소, 이용권 발급, 관리자 권한 변경처럼 트랜잭션/권한/DB 제약조건이 중요한 API는 실제 DB와 Application Service까지 포함해 검증한다.

---

# Product Test Statement

> ClimbDesk의 테스트 전략은 단순 CRUD 검증이 아니라, 예약 정원, 이용권 차감/복구, 중복 예약, 트랜잭션 원자성, 동시성 제어, OutboxEvent 저장을 자동 테스트로 증명하는 데 초점을 둔다.
