# ClimbDesk AI Rules v0.2

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `08 - AI Rules`.
> This Markdown file is a repository-local snapshot synced on 2026-06-15 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: 이 문서는 ClimbDesk를 Codex 기반 AI-assisted development 방식으로 구현할 때 사용하는 AI 작업 규칙의 원본 문서다. Notion 문서는 원본/운영 문서이고, 실제 Codex 작업 규칙은 레포지토리의 `AGENTS.md` 및 보조 규칙 파일에 둔다.

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

# 2. Highest-Priority AI Execution Discipline

AI 작업 방식 규칙은 레포지토리에서 AI가 작업하는 방식에 대한 최우선 규칙이다.

이 규칙은 제품 범위, 아키텍처, API 계약, DB 설계, 테스트 전략 자체를 다시 정의하지 않는다. 대신 해당 문서들을 안전하게 해석하고 적용하는 방식을 지배한다.

## 2.1 Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2.2 Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 2.3 Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 2.4 Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" -> "Write tests for invalid inputs, then make them pass"
- "Fix the bug" -> "Write a test that reproduces it, then make it pass"
- "Refactor X" -> "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```plain text
1. [Step] -> verify: [check]
2. [Step] -> verify: [check]
3. [Step] -> verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

These guidelines are working if there are fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# 3. Rule Distribution Strategy

Notion의 `08 - AI Rules`는 AI 규칙의 원본/운영 문서다.

레포지토리의 실행 규칙은 다음 파일에 둔다.

```plain text
Repository
├─ AGENTS.md
├─ PROJECT_RULES.md
├─ src/main/kotlin/AGENTS.md
├─ src/test/AGENTS.md
└─ docs/AGENTS.md
```

역할은 다음과 같다.

- `/AGENTS.md`: AI 작업 방식 규칙. 최우선 실행 discipline이며, ambiguity handling, scope control, simplicity, surgical changes, verification을 지배한다.
- `/PROJECT_RULES.md`: ClimbDesk 프로젝트 공통 규칙. MVP 범위, source of truth, 아키텍처, 트랜잭션, 동시성, API, DB, 테스트, 금지사항을 둔다.
- `/src/main/kotlin/AGENTS.md`: production code 전용 규칙. 루트 `AGENTS.md`와 `PROJECT_RULES.md`를 반복하지 않고 production code에 필요한 추가 규칙만 둔다.
- `/src/test/AGENTS.md`: test code 전용 규칙. 루트 `AGENTS.md`와 `PROJECT_RULES.md`를 반복하지 않고 테스트에 필요한 추가 규칙만 둔다.
- `/docs/AGENTS.md`: 문서 전용 규칙. 루트 `AGENTS.md`와 `PROJECT_RULES.md`를 반복하지 않고 문서 작성/수정에 필요한 추가 규칙만 둔다.

초기 MVP에서는 `AGENTS.md` 파일 분포를 위 네 개로 제한한다. 도메인별 `AGENTS.md`는 아직 만들지 않는다.

---

# 4. Application Policy

Codex는 작업 대상 파일과 가까운 `AGENTS.md`를 함께 참고한다고 가정한다.

적용 순서는 다음과 같다.

```plain text
1. /AGENTS.md
   -> AI 작업 방식 규칙. 항상 최우선으로 적용한다.

2. /PROJECT_RULES.md
   -> ClimbDesk 전체 프로젝트 규칙. 무엇을 구현할지와 어떤 프로젝트 제약을 지킬지 정의한다.

3. Closest scoped AGENTS.md
   -> 작업 디렉터리에만 적용되는 추가 규칙.
```

하위 `AGENTS.md`는 루트 규칙과 프로젝트 공통 규칙을 반복하지 않고, 해당 폴더에 필요한 추가 규칙만 작성한다.

---

# 5. Project Source of Truth

Codex는 구현 판단 시 다음 문서를 우선 참조한다.

1. `01 - PRD`
2. `02 - Functional Spec`
3. `03 - Domain Model`
4. `04 - API Spec`
5. `05 - Architecture`
6. `06 - Database Design`
7. `07 - Test Strategy`
8. `08 - AI Rules`

설계 문서 간 충돌이 있을 경우 다음 기준을 따른다.

- 기능 범위, MVP 포함/제외 여부: PRD
- 유스케이스, 비즈니스 규칙: Functional Spec
- Aggregate, Entity, Value Object, 도메인 경계: Domain Model
- HTTP endpoint, request/response DTO, status code: API Spec
- 패키지 구조, 레이어 의존성, 기술 구조: Architecture
- 테이블, 컬럼, 제약조건, 인덱스, 마이그레이션: Database Design
- 테스트 범위, 테스트 종류, 검증 기준: Test Strategy
- AI 작업 방식, 금지사항, 프롬프트 운영: AI Rules

---

# 6. Project Rules Content Summary

`/PROJECT_RULES.md`에는 ClimbDesk 프로젝트 공통 규칙을 둔다.

필수 포함 항목:

- Project: ClimbDesk의 목적과 MVP 핵심 역량
- Primary Goal: 문서화된 MVP 범위만 구현
- Source of Truth: 프로젝트 문서 우선순위와 충돌 해결 기준
- MVP Scope Guardrails: MVP 제외 기능 목록
- Architecture Rules: 도메인 독립성, 레이어 의존성, DTO/command/entity/domain 분리
- Transaction and Consistency Rules: Application Service transaction boundary, atomic use cases, OutboxEvent transaction coupling
- Concurrency Rules: ClassSession pessimistic locking, MemberPass optimistic locking, duplicate CONFIRMED reservation prevention
- API and Persistence Rules: API Spec 및 Database Design 준수
- Test Rules: business rule, transaction, concurrency, API, persistence constraint 변경 시 테스트 추가/수정
- Prohibited Actions: domain-specific `AGENTS.md` 생성 금지, business logic 구현 금지, package structure 변경 금지, 문서와 충돌하는 규칙 추가 금지

---

# 7. Scoped Agent File Content

## 7.1 Production Code Rules

`/src/main/kotlin/AGENTS.md`에는 production code 전용 규칙을 둔다.

필수 원칙:

- 루트 `AGENTS.md`를 먼저 따르고, 그 다음 `PROJECT_RULES.md`를 따른다.
- Domain code는 Spring, JPA, Web, infrastructure framework에 의존하지 않는다.
- Business rule은 controller, DTO, JPA entity, mapper에 두지 않는다.
- Application service가 transaction boundary와 use case orchestration을 가진다.
- Domain model은 explicit method로 invariant를 보호한다.
- Mutable domain state에 public setter를 노출하지 않는다.
- 다른 aggregate는 object reference가 아니라 ID로 참조한다.
- Command, DTO, domain model, persistence entity를 분리한다.

## 7.2 Test Rules

`/src/test/AGENTS.md`에는 test code 전용 규칙을 둔다.

필수 원칙:

- 루트 `AGENTS.md`를 먼저 따르고, 그 다음 `PROJECT_RULES.md`를 따른다.
- Business rule 변경마다 test를 추가 또는 수정한다.
- Domain invariant는 unit test로 검증한다.
- Reservation workflow는 integration test로 검증한다.
- Transaction consistency가 요구되는 concurrency behavior는 database를 사용하는 test로 검증한다.
- Assertion을 약화하거나 DB constraint를 제거해서 test를 통과시키지 않는다.

## 7.3 Docs Rules

`/docs/AGENTS.md`에는 documentation 전용 규칙을 둔다.

필수 원칙:

- 루트 `AGENTS.md`를 먼저 따르고, 그 다음 `PROJECT_RULES.md`를 따른다.
- 문서는 Notion ClimbDesk Documents와 정합성을 유지한다.
- MVP scope 변경은 product decision으로 명시하지 않고 문서에 반영하지 않는다.
- API behavior 변경 시 API Spec을 함께 갱신한다.
- Database schema 변경 시 Database Design을 함께 갱신한다.
- Domain rule 변경 시 Functional Spec과 Domain Model을 함께 갱신한다.

---

# 8. Codex Prompt Template

Codex 작업 요청은 다음 템플릿을 사용한다.

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

# 9. Codex Work Unit Policy

Codex에게 큰 기능을 한 번에 맡기지 않는다.

좋은 작업 단위:

- 하나의 Aggregate 구현
- 하나의 Application Service 유스케이스 구현
- 하나의 API endpoint 구현
- 하나의 DB migration 작성
- 하나의 테스트 시나리오 추가
- 하나의 rule distribution 변경

나쁜 작업 단위:

- 전체 예약 시스템 구현
- 모든 도메인 한 번에 구현
- API, DB, 테스트를 설계 없이 한 번에 생성
- 문서와 다른 구조로 자동 리팩터링

---

# 10. Sync Policy

Notion 문서가 변경되면 관련 레포지토리 규칙 파일도 함께 갱신한다.

동기화 대상:

```plain text
/AGENTS.md
/PROJECT_RULES.md
/src/main/kotlin/AGENTS.md
/src/test/AGENTS.md
/docs/AGENTS.md
```

동기화가 필요한 경우:

- AI execution discipline 변경
- MVP 범위 변경
- 도메인 규칙 변경
- API Spec 변경
- Database Design 변경
- Architecture 변경
- Test Strategy 변경
- Codex 운영 방식 변경

Codex 작업 전에는 현재 작업과 관련된 `AGENTS.md` 및 `PROJECT_RULES.md`가 최신 설계 문서를 반영하는지 확인한다.

---

# 11. Review Checklist

Codex 작업 결과를 검토할 때 다음 항목을 확인한다.

- AI execution discipline을 지켰는가?
- 요청 범위를 벗어난 변경이 없는가?
- 애매한 내용을 조용히 추측하지 않았는가?
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

# 12. Final Decision

ClimbDesk의 AI-assisted development 운영 방식은 다음으로 확정한다.

```plain text
Notion 08 - AI Rules
= AI 개발 규칙의 원본/운영 문서

Repository /AGENTS.md
= AI 작업 방식 규칙. 최우선 execution discipline.

Repository /PROJECT_RULES.md
= ClimbDesk 프로젝트 공통 실행 규칙.

Repository scoped AGENTS.md files
= Codex가 실제 작업 중 참고하는 디렉터리별 추가 실행 규칙.
```

초기 레포지토리에는 다음 4개의 `AGENTS.md`만 둔다.

```plain text
/AGENTS.md
/src/main/kotlin/AGENTS.md
/src/test/AGENTS.md
/docs/AGENTS.md
```

프로젝트 공통 규칙은 `/PROJECT_RULES.md`에 둔다. 이는 `AGENTS.md` 파일 분포에 포함하지 않는 보조 규칙 문서다.

도메인 규모가 커지면 도메인별 `AGENTS.md`를 추가할 수 있다. 단, 초기 MVP에서는 규칙 파일을 과도하게 분리하지 않는다.
