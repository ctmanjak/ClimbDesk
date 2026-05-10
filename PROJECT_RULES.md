# PROJECT_RULES.md

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
- AI work rules and prompt operations: AI Rules

## MVP Scope Guardrails

Do not implement the following unless the project documents are explicitly updated:

- Period passes
- Day passes
- Class update
- Recurring classes
- Instructor assignment
- Attendance check
- Waitlist reservation
- Real payment integration
- Real notification delivery
- Advanced search/filtering
- Multi-branch operation
- Statistics dashboard
- Generic audit log system
- Real SQS/Kafka external messaging integration

## Architecture Rules

- Keep domain code independent from Spring, JPA, Web, and infrastructure concerns.
- Do not put business rules in controllers.
- Do not call repositories directly from controllers.
- Application services own use case orchestration and transaction boundaries.
- Domain models enforce invariants through explicit methods.
- Avoid public setters for domain state mutation.
- Reference other aggregates by ID, not by object reference.
- Keep DTOs, commands, persistence entities, and domain models separate.

## Transaction and Consistency Rules

- Declare transactions at the Application Service method boundary.
- Do not place transaction orchestration in controllers.
- Do not open transactions inside aggregate methods.
- Keep domain state changes and OutboxEvent persistence in the same transaction when events are part of the use case.
- Reservation creation, reservation cancellation, and class session cancellation must be atomic.

## Concurrency Rules

- Load ClassSession with pessimistic locking when creating reservations or canceling class sessions.
- Use optimistic locking for MemberPass consume and restore operations.
- Do not add automatic retry for MemberPass version conflicts in MVP.
- Prevent duplicate CONFIRMED reservations with both application pre-checks and database constraints.

## API and Persistence Rules

- Keep API behavior aligned with API Spec.
- Return documented HTTP status codes and error codes.
- Do not expose JPA entities through controllers.
- Keep database schema, constraints, indexes, and migrations aligned with Database Design.
- Do not weaken persistence constraints to make code or tests pass.

## Test Rules

- Add or update tests when business rules, transactions, concurrency behavior, API contracts, or persistence constraints change.
- Prefer focused domain unit tests for aggregate invariants.
- Use PostgreSQL Testcontainers for integration behavior that depends on PostgreSQL locks, indexes, constraints, or transactions.
- Do not use H2 as the default strategy for core integration tests.

## Prohibited Actions

- Do not create domain-specific AGENTS.md files during initial MVP setup.
- Do not implement business logic while editing these rules.
- Do not change package structure except directories required for requested files.
- Do not add rules that conflict with project documents.
- Do not broaden MVP scope through code, tests, or docs without an explicit product decision.
