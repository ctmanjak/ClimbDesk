# ClimbDesk AI Rules v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `08 - AI Rules`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: 이 문서는 ClimbDesk를 Codex 기반 AI-assisted development 방식으로 구현할 때 사용하는 AI 작업 규칙의 원본 문서다. Notion 문서는 AI가 항상 자동으로 참조하는 실행 규칙이 아니므로, 실제 Codex 작업 규칙은 레포지토리의 `AGENTS.md` 파일들에 둔다.

---

# 1. Purpose

ClimbDesk는 백엔드 포트폴리오 목적의 클라이밍짐 수업 예약 시스템이다.

MVP의 핵심은 단순 CRUD가 아니라 다음 역량을 코드로 보여주는 것이다.

- 도메인 모델링
- 예약 정합성
- 트랜잭션 경계
- 동시성 제어
- 이용권 차감/복구 이력
- 테스트 가능성
- AI-assisted development에서도 유지 가능한 아키텍처

이 문서는 Codex가 코드를 생성하거나 수정할 때 설계 의도를 벗어나지 않도록 하기 위한 운영 기준을 정의한다.

---

# 2. Rule Distribution Strategy

Notion의 `08 - AI Rules`는 AI 규칙의 원본 문서다.
실제 Codex가 작업 중 참고해야 하는 실행 규칙은 레포지토리 안의 `AGENTS.md` 파일들에 둔다.

```plain text
Notion
└─ 08 - AI Rules
   └─ AI 규칙의 원본/운영 문서

Repository
├─ AGENTS.md
├─ src/main/kotlin/AGENTS.md
├─ src/test/AGENTS.md
└─ docs/AGENTS.md
```

| File | Role |
| --- | --- |
| `/AGENTS.md` | 프로젝트 전체 공통 규칙, MVP 범위, 아키텍처 원칙, 금지사항 |
| `/src/main/kotlin/AGENTS.md` | Kotlin production code 작성 규칙, 레이어/도메인 구현 규칙 |
| `/src/test/AGENTS.md` | 테스트 작성 규칙, 통합 테스트/동시성 테스트 기준 |
| `/docs/AGENTS.md` | 문서 작성/수정 규칙, 설계 문서 동기화 기준 |

---

# 3. AGENTS.md Application Policy

Codex는 작업 대상 파일과 가까운 `AGENTS.md`를 함께 참고한다고 가정한다.

```plain text
/AGENTS.md
→ 프로젝트 전체에 적용되는 최상위 규칙

/src/main/kotlin/AGENTS.md
→ production code에만 적용되는 구현 규칙

/src/test/AGENTS.md
→ test code에만 적용되는 테스트 규칙

/docs/AGENTS.md
→ 문서에만 적용되는 작성 규칙
```

하위 `AGENTS.md`는 루트 규칙을 반복하지 않고, 해당 폴더에 필요한 추가 규칙만 작성한다.

---

# 4. Source of Truth

Codex는 구현 판단 시 다음 문서를 우선 참조한다.

1. `01 - PRD`
2. `02 - Functional Spec`
3. `03 - Domain Model`
4. `04 - API Spec`
5. `05 - Architecture`
6. `06 - Database Design`
7. `07 - Test Strategy`
8. `08 - AI Rules`

| Conflict Type | Source of Truth |
| --- | --- |
| 기능 범위, MVP 포함/제외 여부 | PRD |
| 유스케이스, 비즈니스 규칙 | Functional Spec |
| Aggregate, Entity, Value Object, 도메인 경계 | Domain Model |
| HTTP endpoint, request/response DTO, status code | API Spec |
| 패키지 구조, 레이어 의존성, 기술 구조 | Architecture |
| 테이블, 컬럼, 제약조건, 인덱스, 마이그레이션 | Database Design |
| 테스트 범위, 테스트 종류, 검증 기준 | Test Strategy |
| AI 작업 방식, 금지사항, 프롬프트 운영 | AI Rules |

---

# 5. MVP Scope Guardrails

Codex는 문서에 명시된 MVP 범위만 구현한다.
다음 기능은 명시적인 설계 변경 요청이 없는 한 구현하지 않는다.

- 기간권
- 일일권
- 수업 수정
- 반복 수업
- 강사 배정
- 출석 체크
- 대기 예약
- 실제 결제 연동
- 실제 알림 발송
- 고급 검색/필터링
- 멀티 지점 운영
- 통계 대시보드
- 일반화된 AuditLog 시스템
- SQS/Kafka 외부 메시징 실제 연동

확장 가능성을 코드 구조에 남길 수는 있지만, MVP에서 실제 기능으로 구현하지 않는다.

---

# 6. Root AGENTS.md Content

레포지토리 루트의 `/AGENTS.md`에는 다음 내용을 둔다.

```markdown
# AGENTS.md

## Project

ClimbDesk is a backend portfolio project for a climbing gym class reservation system.

The MVP focuses on domain modeling, transaction consistency, concurrency control, reservation integrity, pass usage history, and testability.

## Primary Goal

Implement only the documented MVP scope.

Do not add features that are not explicitly included in the project documents.

## Source of Truth

When implementing or modifying code, follow the project documents in this order:

1. PRD
2. Functional Spec
3. Domain Model
4. API Spec
5. Architecture
6. Database Design
7. Test Strategy
8. AI Rules

Conflict resolution:

- Product scope: PRD
- Business rules and use cases: Functional Spec
- Aggregates and domain boundaries: Domain Model
- HTTP endpoints and DTOs: API Spec
- Package structure and dependency direction: Architecture
- Tables, constraints, indexes, migrations: Database Design
- Test scope and test types: Test Strategy

## MVP Scope Guardrails

Do not implement the following unless the project documents are explicitly updated:

- Period passes
- Day passes
- Recurring classes
- Class update
- Instructor assignment
- Attendance check
- Waitlist reservation
- Real payment integration
- Real notification delivery
- Advanced search/filtering
- Multi-branch operation
- Statistics dashboard
- Generic audit log system

## Architecture Rules

- Keep domain code independent from Spring, JPA, Web, and infrastructure concerns.
- Do not put business rules in controllers.
- Do not call repositories directly from controllers.
- Application services own use case orchestration and transaction boundaries.
- Domain models enforce invariants through explicit methods.
- Avoid public setters for domain state mutation.
- Reference other aggregates by ID, not by object reference.
- Keep DTOs, commands, entities, and domain models separate.
```

The Notion source continues with domain, transaction, concurrency, API, database, test, prohibited action, and required work summary rules for the same root file.

---

# 7. Production Code AGENTS.md Content

`/src/main/kotlin/AGENTS.md`에는 production code 전용 규칙을 둔다.

```markdown
# src/main/kotlin/AGENTS.md

## Production Code Rules

- Keep domain code independent from Spring, JPA, Web, and infrastructure frameworks.
- Do not put business rules in controllers, DTOs, JPA entities, or mappers.
- Application services own transaction boundaries and use case orchestration.
- Domain models must protect invariants through explicit methods.
- Do not expose public setters for mutable domain state.
- Reference other aggregates by ID.
- Keep commands, DTOs, domain models, and persistence entities separate.

## Layering Rules

- presentation may depend on application.
- application may depend on domain ports and domain models.
- domain must not depend on application, presentation, infrastructure, Spring, or JPA.
- infrastructure may implement application/domain ports.

## Implementation Checklist

Before completing a production code task, verify:

- The change stays within MVP scope.
- The code follows the documented package/layer structure.
- Business rules are enforced in the domain or application layer.
- Transaction boundaries are correct.
- Persistence constraints are not bypassed.
- API contracts remain aligned with API Spec.
```

---

# 8. Test AGENTS.md Content

`/src/test/AGENTS.md`에는 테스트 전용 규칙을 둔다.

```markdown
# src/test/AGENTS.md

## Test Rules

- Add or update tests for every business rule change.
- Domain invariants should be tested with unit tests.
- Reservation workflows should be tested with integration tests.
- Concurrency behavior should be tested where transaction consistency is part of the requirement.

## Required Coverage Areas

The following areas require tests:

- Member activation/deactivation rules
- AdminUser login eligibility and role rules
- PassProduct creation rules
- MemberPass consume/restore rules
- PassUsageHistory recording
- ClassSession capacity rules
- ClassSession cancellation rules
- Reservation creation
- Reservation cancellation
- Duplicate CONFIRMED reservation prevention
- Capacity overflow prevention
- MemberPass optimistic locking conflict

## Prohibited Test Changes

Do not:

- Weaken assertions to make tests pass.
- Delete failing tests without explaining the replacement.
- Mock away transaction or concurrency behavior that should be tested with the database.
- Remove database constraints from test schema.
```

---

# 9. Docs AGENTS.md Content

`/docs/AGENTS.md`에는 문서 작성/수정 규칙을 둔다.

```markdown
# docs/AGENTS.md

## Documentation Rules

- Keep documentation aligned with Notion ClimbDesk Documents.
- Do not change MVP scope in docs without explicitly marking it as a product decision.
- When API behavior changes, update API Spec before or with code changes.
- When database schema changes, update Database Design before or with migrations.
- When domain rules change, update Functional Spec and Domain Model before or with implementation.
- Keep AI rules concise enough to be useful during Codex work.

## Required Documentation Update Cases

Update docs when changing:

- MVP scope
- Business rules
- Domain model boundaries
- API contracts
- Database schema, constraints, or indexes
- Test strategy
- AI development workflow
```

---

# 10. Codex Prompt Template

```plain text
Task:
- 구현/수정할 기능을 한 문장으로 설명한다.

Context:
- 관련 문서:
- 관련 도메인:
- 관련 API:
- 관련 DB 테이블:

Scope:
- 수정 허용 파일/패키지:
- 수정 금지 파일/패키지:
- MVP 범위 외 추가 금지 사항:

Rules:
- 따라야 할 AGENTS.md:
- 반드시 지켜야 할 도메인 규칙:
- 트랜잭션/동시성 요구사항:

Tests:
- 추가할 테스트:
- 수정할 테스트:
- 실행할 검증 명령:

Output:
- 변경 파일 목록
- 구현 요약
- 테스트 결과
- 남은 리스크
```

---

# 11. Codex Work Unit Policy

좋은 작업 단위:

- 하나의 Aggregate 구현
- 하나의 Application Service 유스케이스 구현
- 하나의 API endpoint 구현
- 하나의 DB migration 작성
- 하나의 테스트 시나리오 추가

나쁜 작업 단위:

- 전체 예약 시스템 구현
- 모든 도메인 한 번에 구현
- API, DB, 테스트를 설계 없이 한 번에 생성
- 문서와 다른 구조로 자동 리팩터링

---

# 12. Sync Policy

Notion 문서가 변경되면 관련 레포지토리 규칙 파일도 함께 갱신한다.

동기화 대상:

```plain text
/AGENTS.md
/src/main/kotlin/AGENTS.md
/src/test/AGENTS.md
/docs/AGENTS.md
```

동기화가 필요한 경우:

- MVP 범위 변경
- 도메인 규칙 변경
- API Spec 변경
- Database Design 변경
- Architecture 변경
- Test Strategy 변경
- Codex 운영 방식 변경

---

# 13. Review Checklist

- MVP 범위 밖 기능이 추가되지 않았는가?
- Domain Model의 Aggregate 경계를 지켰는가?
- Controller에 비즈니스 로직이 들어가지 않았는가?
- Application Service가 트랜잭션 경계를 가진가?
- 예약 생성/취소/수업 취소가 원자적으로 처리되는가?
- ClassSession 비관적 락 정책을 지켰는가?
- MemberPass 낙관적 락 정책을 지켰는가?
- 중복 CONFIRMED 예약 방지가 DB 제약으로도 보장되는가?
- PassUsageHistory가 차감/복구와 함께 기록되는가?
- API Spec과 응답 형식이 일치하는가?
- 테스트가 추가 또는 수정되었는가?
- 실패한 테스트를 억지로 완화하지 않았는가?
- 문서 변경이 필요한 코드 변경인데 문서가 누락되지 않았는가?

---

# 14. Final Decision

```plain text
Notion 08 - AI Rules
= Codex용 AI 개발 규칙의 원본/운영 문서

Repository AGENTS.md files
= Codex가 실제 작업 중 참고하는 실행 규칙
```

초기 레포지토리에는 다음 4개의 `AGENTS.md`를 둔다.

```plain text
/AGENTS.md
/src/main/kotlin/AGENTS.md
/src/test/AGENTS.md
/docs/AGENTS.md
```

도메인 규모가 커지면 다음 위치에 도메인별 `AGENTS.md`를 추가할 수 있다.

```plain text
/src/main/kotlin/com/climbdesk/auth/AGENTS.md
/src/main/kotlin/com/climbdesk/member/AGENTS.md
/src/main/kotlin/com/climbdesk/pass/AGENTS.md
/src/main/kotlin/com/climbdesk/classsession/AGENTS.md
/src/main/kotlin/com/climbdesk/reservation/AGENTS.md
```

단, 초기 MVP에서는 규칙 파일을 과도하게 분리하지 않는다.
