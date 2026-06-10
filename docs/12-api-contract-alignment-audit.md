# API 계약 정합성 감사

> 원본 티켓: Notion `Audit API contract alignment`
>
> 작성일: 2026-06-10
>
> 범위: `docs/04-api-spec.md` 기준 endpoint path/method/status, request/response DTO, ErrorResponse, error code/status mapping, authentication/authorization boundary, API test evidence.

## 요약

현재 구현된 MVP API endpoint 표면은 API Spec의 endpoint summary와 대체로 일치한다. `POST /auth/login`을 제외한 API는 JWT 인증을 요구하고, 운영 API는 `MANAGER`/`STAFF`, AdminUser 관리 API는 `MANAGER` 전용으로 구현되어 있다.

Production bug로 볼 만한 명확한 API contract 불일치는 확인하지 못했다. 다만 response timestamp/date-time 형식은 API Spec 예시가 `+09:00` offset을 기준으로 쓰인 반면 실제 DTO와 테스트는 `Instant`의 UTC `Z` 출력을 계약처럼 고정하고 있다. 또한 AdminUser activate/deactivate에 대해 STAFF 금지 통합 테스트가 비어 있고, PassProduct의 optional 필드 및 reservation cancel request DTO는 API Spec이 상위 문서/구현보다 덜 명확하다.

## Findings

### F-01: 구현된 endpoint path, method, 기본 status는 API Spec과 정렬됨

- Category: `No issue`
- Source document reference: `docs/04-api-spec.md:145`, `docs/04-api-spec.md:154`, `docs/04-api-spec.md:163`, `docs/04-api-spec.md:174`, `docs/04-api-spec.md:183`
- Code or test reference: `src/main/kotlin/dev/climbdesk/auth/presentation/AdminUserController.kt:20`, `src/main/kotlin/dev/climbdesk/member/presentation/MemberController.kt:27`, `src/main/kotlin/dev/climbdesk/pass/presentation/PassProductController.kt:26`, `src/main/kotlin/dev/climbdesk/pass/presentation/MemberPassController.kt:26`, `src/main/kotlin/dev/climbdesk/classsession/presentation/ClassSessionController.kt:27`, `src/main/kotlin/dev/climbdesk/reservation/presentation/ReservationController.kt:28`
- Current behavior: API Spec의 Auth, AdminUser, Member, Pass, ClassSession, Reservation endpoint가 컨트롤러에 구현되어 있다. 생성 API는 `@ResponseStatus(HttpStatus.CREATED)`로 201을 반환하고, 조회/변경 API는 기본 200을 반환한다.
- Expected behavior: 현재와 동일.
- Impact: README/API 문서에서 endpoint 목록을 구현 완료로 설명할 수 있다.
- Recommended action: 그대로 유지한다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-02: ErrorResponse shape와 주요 error code/status mapping은 정렬됨

- Category: `No issue`
- Source document reference: `docs/04-api-spec.md:111`, `docs/04-api-spec.md:751`
- Code or test reference: `src/main/kotlin/dev/climbdesk/common/error/ErrorResponse.kt:7`, `src/main/kotlin/dev/climbdesk/common/error/GlobalExceptionHandler.kt:129`, `src/main/kotlin/dev/climbdesk/common/error/ErrorCodeStatusMapper.kt:5`, `src/test/kotlin/dev/climbdesk/common/error/GlobalExceptionHandlerTest.kt:33`
- Current behavior: `timestamp`, `status`, `code`, `message`, `path`, `traceId`, optional `details` shape가 구현되어 있고, validation/auth/not-found/conflict/internal error mapping 테스트가 존재한다. `PessimisticLockingFailureException`은 `409 CONCURRENCY_CONFLICT`로 매핑된다.
- Expected behavior: 현재와 동일.
- Impact: 공통 오류 응답 계약은 문서화된 형태와 일치한다.
- Recommended action: 그대로 유지한다. 필요하면 `ErrorCodeStatusMapperTest`에 모든 error code catalog parameterized coverage를 추가할 수 있으나, 현재 mapping에서 contract bug는 확인되지 않았다.
- Risk and effort estimate: 낮은 risk, 낮은 effort.

### F-03: 인증 및 역할 경계 구현은 API Spec과 정렬됨

- Category: `No issue`
- Source document reference: `docs/04-api-spec.md:55`, `docs/04-api-spec.md:73`, `docs/04-api-spec.md:930`
- Code or test reference: `src/main/kotlin/dev/climbdesk/auth/infrastructure/adapter/SecurityConfig.kt:33`, `src/main/kotlin/dev/climbdesk/auth/presentation/AdminUserController.kt:22`, `src/test/kotlin/dev/climbdesk/auth/infrastructure/adapter/SecurityFoundationTest.kt:53`, `src/test/kotlin/dev/climbdesk/auth/presentation/AdminUserCreateIntegrationTest.kt:68`
- Current behavior: `POST /api/v1/auth/login`만 public이고 나머지는 인증을 요구한다. AdminUser 관리 API는 `hasRole('MANAGER')`, 운영 API는 `hasAnyRole('MANAGER', 'STAFF')`를 사용한다. Security foundation 테스트는 401/403 shape를 검증하고, AdminUser 생성/role 변경은 STAFF 금지 통합 테스트가 있다.
- Expected behavior: 현재와 동일.
- Impact: 보안 경계 구현 자체는 API Spec과 맞다.
- Recommended action: 그대로 유지한다. F-05의 테스트 보강은 contract confidence를 높이기 위한 별도 follow-up이다.
- Risk and effort estimate: 낮은 risk, 즉시 필요한 구현 effort 없음.

### F-04: response date-time 형식이 API Spec 예시와 실제 UTC `Instant` 출력 사이에서 불명확함

- Category: `Doc drift`
- Source document reference: `docs/04-api-spec.md:85`, `docs/04-api-spec.md:115`, `docs/04-api-spec.md:488`
- Code or test reference: `src/main/kotlin/dev/climbdesk/classsession/presentation/ClassSessionResponse.kt:11`, `src/test/kotlin/dev/climbdesk/classsession/presentation/ClassSessionCreationIntegrationTest.kt:62`, `src/test/kotlin/dev/climbdesk/classsession/presentation/ClassSessionQueryIntegrationTest.kt:127`
- Current behavior: API DTO는 `Instant`를 노출하고 테스트는 `"2026-05-10T10:00:00Z"` 같은 UTC `Z` 문자열을 기대한다. ErrorResponse의 `timestamp`는 `OffsetDateTime.now()`라 실행 환경 offset을 포함할 수 있다.
- Expected behavior: API Spec은 전역 Date Time Format 예시로 `"2026-05-01T10:00:00+09:00"`을 제시한다. 이 예시가 KST offset 계약인지, ISO-8601 instant/offset 허용 예시인지 명확하지 않다.
- Impact: 클라이언트와 README/API 문서가 `+09:00` offset을 보장한다고 이해하면 실제 response의 `Z` 출력과 어긋난다.
- Recommended action: follow-up 문서 티켓을 만든다. 제품 결정은 둘 중 하나다. API Spec을 "ISO-8601 instant or offset datetime, currently UTC `Z` for persisted instants"로 명확히 하거나, 구현을 `OffsetDateTime`/Jackson 설정으로 `+09:00` 출력에 맞춘다.
- Risk and effort estimate: 낮은 risk, 낮은 to 중간 effort. 문서 명확화는 작고, 전 API 응답 형식 변경은 테스트 수정 범위가 넓다.

### F-05: AdminUser activate/deactivate의 STAFF 금지 통합 테스트가 없음

- Category: `Missing test`
- Source document reference: `docs/04-api-spec.md:73`, `docs/04-api-spec.md:150`
- Code or test reference: `src/main/kotlin/dev/climbdesk/auth/presentation/AdminUserController.kt:36`, `src/main/kotlin/dev/climbdesk/auth/presentation/AdminUserController.kt:43`, `src/test/kotlin/dev/climbdesk/auth/presentation/AdminUserCreateIntegrationTest.kt:68`, `src/test/kotlin/dev/climbdesk/auth/presentation/AdminUserCreateIntegrationTest.kt:110`, `src/test/kotlin/dev/climbdesk/auth/presentation/AdminUserCreateIntegrationTest.kt:135`
- Current behavior: activate/deactivate endpoints are protected with `hasRole('MANAGER')`, and manager success is covered. STAFF forbidden coverage exists for create and role change, but not for activate/deactivate.
- Expected behavior: Since API Spec defines AdminUser activation/deactivation as MANAGER-only MVP APIs, integration tests should also assert STAFF receives `403 FORBIDDEN` and target state remains unchanged.
- Impact: Implementation is likely correct, but API test evidence is incomplete for two MANAGER-only endpoints.
- Recommended action: create a follow-up test ticket to add STAFF-forbidden integration coverage for `PATCH /api/v1/admin-users/{id}/activate` and `/deactivate`.
- Risk and effort estimate: 낮은 risk, 낮은 effort.

### F-06: PassProduct optional fields are implemented correctly, but API Spec request section is less explicit than upstream docs

- Category: `Doc drift`
- Source document reference: `docs/04-api-spec.md:430`, `docs/02-functional-spec.md:296`
- Code or test reference: `src/main/kotlin/dev/climbdesk/pass/presentation/CreatePassProductRequest.kt:21`, `src/test/kotlin/dev/climbdesk/pass/presentation/PassProductIntegrationTest.kt:78`
- Current behavior: `price` and `validDays` are nullable in the request DTO, database schema allows null, and integration tests assert a pass product can be created without those fields.
- Expected behavior: Functional Spec marks both as optional, so implementation behavior is acceptable. API Spec examples show both fields but do not explicitly label them optional in the Pass API section.
- Impact: External API readers may incorrectly infer that `price` and `validDays` are required.
- Recommended action: create a follow-up documentation ticket to mark `price` and `validDays` optional in API Spec request/response field notes.
- Risk and effort estimate: 낮은 risk, 낮은 effort.

### F-07: Reservation cancel body handling is an acceptable implementation deviation

- Category: `Acceptable deviation`
- Source document reference: `docs/04-api-spec.md:696`, `docs/04-api-spec.md:860`
- Code or test reference: `src/main/kotlin/dev/climbdesk/reservation/presentation/ReservationController.kt:60`, `src/test/kotlin/dev/climbdesk/reservation/presentation/ReservationCancellationIntegrationTest.kt:214`
- Current behavior: Controller exposes `PATCH /api/v1/reservations/{reservationId}/cancel` with no request DTO and always delegates to the domain `USER_REQUESTED` cancellation path. Tests verify both no body and a body containing `{"reason":"USER_REQUESTED"}` work.
- Expected behavior: API Spec says request body may be omitted and MVP domain cancel reason is always `USER_REQUESTED`. DTO naming guide lists `CancelReservationRequest`, but no behavior requires reading the body.
- Impact: No runtime API issue. The DTO naming guide is slightly more prescriptive than the endpoint behavior section.
- Recommended action: no implementation change. If API docs are being polished, clarify that `CancelReservationRequest` is optional and only exists if implementation chooses to bind the body.
- Risk and effort estimate: 낮은 risk, no immediate effort.

## Follow-up 티켓 권고

1. API date-time format decision 정리.
   - Notion: https://app.notion.com/p/37b4c60a730381fea815f368d9cdf380
   - Goal: persisted response timestamps and ErrorResponse timestamps should have one documented format.
   - Acceptance: API Spec either documents current UTC `Z` output or implementation/tests move consistently to documented `+09:00` offset output.

2. AdminUser activate/deactivate STAFF forbidden integration tests 추가.
   - Notion: https://app.notion.com/p/37b4c60a730381a99c70c0c81633d8f0
   - Goal: two MANAGER-only endpoints have the same role-boundary coverage as AdminUser create and role change.
   - Acceptance: STAFF token gets `403 FORBIDDEN` for activate/deactivate and target row state remains unchanged.

3. API Spec PassProduct optional fields 명확화.
   - Notion: https://app.notion.com/p/37b4c60a730381abb108c1e72ef28b8b
   - Goal: `price` and `validDays` optionality is explicit in `POST /pass-products` request/response docs.
   - Acceptance: API Spec matches Functional Spec and current nullable request/response behavior.

## 감사 결론

이번 감사에서 production API contract bug는 확인되지 않았다. Endpoint surface, ErrorResponse shape, common error mapping, and role annotations align with API Spec. Follow-up은 API 문서 명확화와 role-boundary test confidence 보강으로 제한하는 것이 적절하다.
