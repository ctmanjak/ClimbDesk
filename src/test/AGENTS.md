# src/test/AGENTS.md

Follow the root `AGENTS.md` first for AI execution discipline, then `PROJECT_RULES.md` for ClimbDesk-wide project rules.

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
