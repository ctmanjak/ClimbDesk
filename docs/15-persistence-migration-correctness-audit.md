# Persistence and Migration Correctness Audit

> Original ticket: Notion `Compare Flyway schema and repository tests with Database Design`
>
> Date: 2026-06-11
>
> Scope: Flyway MVP baseline migration, JPA entity mapping, repository behavior, PostgreSQL constraints and indexes, and DB-backed test evidence against `docs/06-database-design.md` and `docs/07-test-strategy.md`.

## Summary

The MVP Flyway migration and JPA mappings are broadly aligned with the Database Design. Core integration tests use PostgreSQL Testcontainers with `spring.jpa.hibernate.ddl-auto=validate`, so schema creation is migration-owned and JPA mismatches are checked at startup.

The main gap is not a confirmed production bug. It is evidence coverage: `MvpSchemaMigrationTest` verifies table creation, a representative subset of constraints/indexes, and a few strengthened consistency constraints, while `docs/07-test-strategy.md` asks repository integration tests to prove PostgreSQL check constraints, unique indexes, lock queries, and ordering behavior. Several repository behaviors are covered elsewhere, but direct DB-backed enforcement coverage is still partial.

Existing audits already cover reservation workflow transaction/concurrency gaps, API contract issues, README readiness, and domain-scope drift. This document does not duplicate those findings; it references them where relevant.

## Findings

### F-01: Flyway baseline and JPA mappings align with the MVP schema contract

- Category: `No issue`
- Source document reference: `docs/06-database-design.md:38`, `docs/06-database-design.md:67`, `docs/07-test-strategy.md:127`
- Code or test reference: `src/main/resources/db/migration/V1__create_mvp_schema.sql`, JPA entities under `src/main/kotlin/dev/climbdesk/**/infrastructure/persistence`, DB-backed tests with `spring.jpa.hibernate.ddl-auto=validate`
- Current behavior: The baseline migration creates all MVP tables, documented FKs/check constraints/indexes, and PostgreSQL-specific structures such as partial unique indexes and `jsonb`. JPA entities use `GenerationType.IDENTITY`, string enums, scalar aggregate references by ID, `@Version` for `member_passes.version`, and `jsonb` mapping for outbox payloads.
- Expected behavior: Flyway owns schema creation and Hibernate validates mappings after migration.
- Impact: The repository has meaningful startup-level evidence that the migration and JPA mappings agree.
- Recommended action: Keep Flyway as the schema source of truth. Do not switch core DB tests to Hibernate schema generation.
- Risk and effort estimate: Low risk, no immediate implementation effort.

### F-02: Migration schema test verifies only a representative subset of constraints and indexes

- Category: `Missing test`
- Source document reference: `docs/06-database-design.md:220`, `docs/06-database-design.md:252`, `docs/06-database-design.md:309`, `docs/06-database-design.md:402`, `docs/06-database-design.md:441`, `docs/07-test-strategy.md:127`
- Code or test reference: `src/test/kotlin/dev/climbdesk/infrastructure/persistence/MvpSchemaMigrationTest.kt:48`
- Current behavior: `MvpSchemaMigrationTest` asserts all MVP table names and a small set of constraint/index names: selected member pass, reservation, pass usage history, class session, and outbox structures. It does not compare the full constraint/index inventory against Database Design.
- Expected behavior: The audit should have a DB-backed check that all documented baseline constraints and supporting indexes exist, or a clear explanation for intentionally untested structures.
- Impact: A future migration edit could accidentally drop an unlisted constraint/index while the migration test still passes.
- Recommended action: Create a follow-up test ticket to expand migration inventory coverage for all documented constraints and indexes. Keep it metadata-based and focused; avoid broad production changes.
- Risk and effort estimate: Medium risk, low to medium effort.

### F-03: DB-backed check-constraint enforcement coverage is partial

- Category: `Missing test`
- Source document reference: `docs/07-test-strategy.md:326`, `docs/07-test-strategy.md:332`, `docs/07-test-strategy.md:340`, `docs/07-test-strategy.md:354`
- Code or test reference: `src/test/kotlin/dev/climbdesk/infrastructure/persistence/MvpSchemaMigrationTest.kt:82`, `src/test/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassPersistenceAdapterIntegrationTest.kt:66`, `src/test/kotlin/dev/climbdesk/event/infrastructure/persistence/OutboxEventPersistenceAdapterIntegrationTest.kt:51`
- Current behavior: Some constraints are enforced in API/domain tests and a few are directly tested against PostgreSQL, including member deactivation field consistency, class-session cancellation field consistency, outbox published field consistency, member pass optimistic locking, available-pass selection, and outbox `jsonb` payload storage. However, repository strategy calls out DB-backed check-constraint behavior for member status, member pass count range, class-session time/capacity/reserved count, and outbox status. Those are not all directly exercised as PostgreSQL constraint violations.
- Expected behavior: For persistence rules that Database Design treats as the DB's last defense, at least one focused DB-backed test should fail invalid rows at the database boundary.
- Impact: Current tests prove much of the application behavior, but they do not fully prove every documented database guardrail independently of application validation/domain methods.
- Recommended action: Create a follow-up test ticket for focused DB-backed constraint enforcement cases. Prefer adding to migration/repository integration tests rather than changing production code.
- Risk and effort estimate: Medium risk, medium effort.

### F-04: Migration intentionally enforces stricter field consistency than the table-design snippets

- Category: `Acceptable deviation`
- Source document reference: `docs/06-database-design.md:256`, `docs/06-database-design.md:413`, `docs/06-database-design.md:492`
- Code or test reference: `src/main/resources/db/migration/V1__create_mvp_schema.sql:25`, `src/main/resources/db/migration/V1__create_mvp_schema.sql:64`, `src/main/resources/db/migration/V1__create_mvp_schema.sql:158`, `src/test/kotlin/dev/climbdesk/infrastructure/persistence/MvpSchemaMigrationTest.kt:82`
- Current behavior: The migration requires ACTIVE members to have `deactivated_at is null`, non-canceled class sessions to have both cancel fields null and canceled sessions to have both set, and non-PUBLISHED outbox events to have `published_at is null`. The Database Design snippets are looser in those sections.
- Expected behavior: The stricter migration behavior is reasonable for MVP state consistency and is already tested directly.
- Impact: This is not a production bug, but Database Design can be read as allowing states the migration rejects.
- Recommended action: Create a documentation follow-up to align Database Design snippets with the accepted stricter migration constraints.
- Risk and effort estimate: Low production risk, low documentation effort.

### F-05: Repository ordering and core selection behavior has useful DB-backed evidence

- Category: `No issue`
- Source document reference: `docs/06-database-design.md:331`, `docs/07-test-strategy.md:326`, `docs/07-test-strategy.md:332`
- Code or test reference: `src/main/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassJpaRepository.kt:13`, `src/test/kotlin/dev/climbdesk/pass/infrastructure/persistence/MemberPassPersistenceAdapterIntegrationTest.kt:66`, `src/test/kotlin/dev/climbdesk/member/presentation/MemberQueryIntegrationTest.kt:77`, `src/test/kotlin/dev/climbdesk/pass/presentation/PassProductIntegrationTest.kt:178`, `src/test/kotlin/dev/climbdesk/classsession/presentation/ClassSessionQueryIntegrationTest.kt:63`
- Current behavior: Available member pass selection uses the documented PostgreSQL ordering of `expires_at asc nulls last`, `issued_at asc`, `id asc`. List query ordering for members, pass products, and class sessions is also covered with deterministic tie-breaker tests under migration-backed PostgreSQL integration tests.
- Expected behavior: Current behavior matches the documented ordering contracts.
- Impact: These tests provide good evidence for repository behavior that depends on query ordering.
- Recommended action: Keep these tests. Do not duplicate them in the migration test.
- Risk and effort estimate: Low risk, no immediate implementation effort.

### F-06: Outbox pending retrieval contract was inconsistent and is not implemented

- Category: `Doc drift`
- Source document reference: `docs/06-database-design.md:663`, `docs/07-test-strategy.md:354`, `docs/07-test-strategy.md:512`
- Code or test reference: `src/main/resources/db/migration/V1__create_mvp_schema.sql:214`, `src/main/kotlin/dev/climbdesk/event/infrastructure/persistence/OutboxEventJpaRepository.kt:1`, `src/test/kotlin/dev/climbdesk/event/infrastructure/persistence/OutboxEventPersistenceAdapterIntegrationTest.kt:51`
- Current behavior: The migration creates `idx_outbox_events_pending` on `(status, next_retry_at asc nulls first, id asc)` for `PENDING` and `FAILED` rows. No production repository method currently reads pending events.
- Expected behavior: Pending retrieval ordering should be documented as `next_retry_at/id`, and publisher/retry implementation and tests should remain deferred until a future use case exists.
- Impact: No current MVP write-path bug is confirmed, because the application only records outbox events.
- Recommended action: Completed by aligning Database Design and Test Strategy to `next_retry_at/id` and keeping publisher/retry implementation out of MVP.
- Risk and effort estimate: Low current production risk, low documentation effort.

## Follow-up Ticket Recommendations

1. Expand Flyway schema inventory coverage.
   - Goal: Verify all documented MVP constraints and indexes exist after `V1__create_mvp_schema.sql` runs.
   - Acceptance: Testcontainers PostgreSQL metadata assertions cover the full Database Design inventory, including FK/check/unique constraints, partial unique indexes, and supporting indexes.

2. Add focused DB-backed constraint enforcement coverage.
   - Goal: Exercise representative invalid rows directly against PostgreSQL for documented last-defense constraints that are not currently proven at the DB boundary.
   - Acceptance: Tests cover member status, member pass count range, class-session time/capacity/reserved count, reservation cancel fields, pass usage history reason/count, and outbox status/retry constraints without weakening existing API/domain tests.

3. Align Database Design with accepted stricter consistency constraints.
   - Goal: Update Database Design snippets for member deactivation fields, class-session cancel fields, and outbox published fields to match the migration and migration tests.
   - Acceptance: The document no longer implies that the migration should allow active members with `deactivated_at`, non-canceled class sessions with cancel fields, or non-published outbox events with `published_at`.

4. Resolve outbox pending retrieval ordering contract. Completed on 2026-06-11.
   - Goal: Future pending outbox reads are ordered by `next_retry_at/id`, matching `idx_outbox_events_pending`.
   - Acceptance: Database Design and Test Strategy agree, and implementation/test work is deferred until a publisher/retry use case exists.

## Audit Conclusion

No production-code bug was confirmed in the persistence/migration audit. The Flyway schema, JPA mappings, core repository queries, and current migration-backed integration tests are directionally aligned with Database Design and Test Strategy.

The remaining work is follow-up test/documentation coverage: strengthen migration inventory assertions, add focused DB-boundary constraint tests, and resolve small documentation drifts. `./gradlew test` succeeded on the current branch on 2026-06-11.
