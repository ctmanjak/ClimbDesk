# src/main/kotlin/AGENTS.md

Follow the root `AGENTS.md` first for AI execution discipline, then `PROJECT_RULES.md` for ClimbDesk-wide project rules.

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
