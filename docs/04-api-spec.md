# ClimbDesk API Spec v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `04 - API Spec`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: Functional Spec의 MVP 기능을 REST API 계약으로 구체화한다. endpoint, request/response DTO, status code, error code, 인증/권한 규칙을 정의한다.

---

# 1. 문서 상태

## Status

```plain text
Draft
```

## Source Documents

- `01 - PRD`: MVP 범위, 핵심 비즈니스 규칙, 성공 기준
- `02 - Functional Spec`: 기능 행위, 상태 변화, 예외, 테스트 기준
- `03 - Domain Model`: Aggregate, 트랜잭션 경계, 이벤트/Outbox 정책

## 작성 원칙

- API는 MVP 구현 가능성과 포트폴리오 설득력을 우선한다.
- API는 도메인 모델을 그대로 노출하지 않고 유스케이스 중심 DTO를 사용한다.
- 외부 클라이언트에는 내부 락 전략을 직접 노출하지 않는다.
- 동시성 충돌, 중복 예약, 정원 초과, 이용권 부족은 명시적인 error code로 반환한다.
- OutboxEvent는 내부 구현 요소이며 public API로 노출하지 않는다.

---

# 2. Global Convention

## Base URL

```plain text
/api/v1
```

## Content Type

```plain text
Content-Type: application/json
Accept: application/json
```

## Authentication

`POST /auth/login`을 제외한 모든 endpoint는 JWT 인증을 요구한다.

```plain text
Authorization: Bearer {accessToken}
```

## Common Auth Errors

```plain text
401 UNAUTHORIZED
403 FORBIDDEN
```

- 인증 토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`
- 인증은 되었지만 필요한 Role이 없으면 `403 FORBIDDEN`

## Roles

```plain text
MANAGER
STAFF
```

- `MANAGER`: 모든 운영 API 접근 가능
- `STAFF`: 회원, 이용권, 수업, 예약 운영 API 접근 가능
- AdminUser 생성/Role 변경/활성화/비활성화 API는 MANAGER 전용 MVP API로 제공한다.

## ID Type

MVP API 예시는 `Long` ID 기준으로 작성한다. 구현에서 UUID를 선택할 경우 path variable과 DTO 타입을 UUID로 통일한다.

## Date Time Format

```plain text
2026-05-01T10:00:00+09:00
```

## List Response Format

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

기본값:

```plain text
page = 0
size = 20
sort = id,desc
```

## Error Response Format

```json
{
  "timestamp": "2026-05-01T10:00:00+09:00",
  "status": 409,
  "code": "DUPLICATE_RESERVATION",
  "message": "Member already has a confirmed reservation for this class session.",
  "path": "/api/v1/reservations",
  "traceId": "c0a8012a-0001"
}
```

Validation error는 `details`를 포함할 수 있다.

```json
{
  "timestamp": "2026-05-01T10:00:00+09:00",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed.",
  "path": "/api/v1/members",
  "traceId": "c0a8012a-0002",
  "details": [
    { "field": "phone", "reason": "must not be blank" }
  ]
}
```

---

# 3. Endpoint Summary

## Auth

```plain text
POST  /auth/login
POST  /admin-users
PATCH /admin-users/{adminUserId}/role
PATCH /admin-users/{adminUserId}/activate
PATCH /admin-users/{adminUserId}/deactivate
```

## Member

```plain text
POST  /members
GET   /members
GET   /members/{memberId}
PATCH /members/{memberId}/deactivate
```

## Pass

```plain text
POST /pass-products
GET  /pass-products
GET  /pass-products/{passProductId}
POST /member-passes
GET  /members/{memberId}/passes
GET  /member-passes/{memberPassId}/usage-histories
```

## Class Session

```plain text
POST  /class-sessions
GET   /class-sessions
GET   /class-sessions/{classSessionId}
PATCH /class-sessions/{classSessionId}/cancel
```

## Reservation

```plain text
POST  /reservations
GET   /reservations
GET   /reservations/{reservationId}
PATCH /reservations/{reservationId}/cancel
```

---

# 4. Auth API

## 4.1 Login

```plain text
POST /api/v1/auth/login
```

### Auth

```plain text
Public
```

### Request

```json
{
  "email": "manager@climbdesk.local",
  "password": "password1234"
}
```

### Response: 200 OK

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "adminUser": {
    "id": 1,
    "email": "manager@climbdesk.local",
    "role": "MANAGER",
    "status": "ACTIVE"
  }
}
```

### Errors

```plain text
400 VALIDATION_FAILED
401 INVALID_CREDENTIALS
403 ADMIN_USER_INACTIVE
```

## 4.2 Create AdminUser

```plain text
POST /api/v1/admin-users
```

### Auth

```plain text
MANAGER
```

### Request Fields

```json
{
  "email": "manager@climbdesk.local",
  "password": "password1234",
  "role": "MANAGER"
}
```

### Response Fields

```json
{
  "id": 1,
  "email": "manager@climbdesk.local",
  "role": "MANAGER",
  "status": "ACTIVE",
  "createdAt": "2026-05-01T10:00:00+09:00"
}
```

### Errors

```plain text
400 VALIDATION_FAILED
409 DUPLICATE_ADMIN_USER_EMAIL
```

## 4.3 Change AdminUser Role

```plain text
PATCH /api/v1/admin-users/{adminUserId}/role
```

### Auth

```plain text
MANAGER
```

### Request Fields

```json
{
  "role": "STAFF"
}
```

### Errors

```plain text
400 VALIDATION_FAILED
404 ADMIN_USER_NOT_FOUND
409 ADMIN_USER_ROLE_CHANGE_NOT_ALLOWED
409 LAST_ACTIVE_MANAGER_REQUIRED
```

## 4.4 Activate AdminUser

```plain text
PATCH /api/v1/admin-users/{adminUserId}/activate
```

## 4.5 Deactivate AdminUser

```plain text
PATCH /api/v1/admin-users/{adminUserId}/deactivate
```

Deactivate errors include:

```plain text
404 ADMIN_USER_NOT_FOUND
409 ADMIN_USER_STATUS_CHANGE_NOT_ALLOWED
409 LAST_ACTIVE_MANAGER_REQUIRED
```

---

# 5. Member API

## 5.1 Create Member

```plain text
POST /api/v1/members
```

### Auth

```plain text
MANAGER, STAFF
```

### Request

```json
{
  "name": "홍길동",
  "phone": "010-1234-5678",
  "email": "hong@example.com"
}
```

### Response: 201 Created

```json
{
  "id": 1,
  "name": "홍길동",
  "phone": "010-1234-5678",
  "email": "hong@example.com",
  "status": "ACTIVE",
  "createdAt": "2026-05-01T10:00:00+09:00",
  "deactivatedAt": null
}
```

### Errors

```plain text
400 VALIDATION_FAILED
409 DUPLICATE_MEMBER_PHONE
```

## 5.2 List Members

```plain text
GET /api/v1/members?page=0&size=20
```

## 5.3 Get Member

```plain text
GET /api/v1/members/{memberId}
```

### Errors

```plain text
404 MEMBER_NOT_FOUND
```

## 5.4 Deactivate Member

```plain text
PATCH /api/v1/members/{memberId}/deactivate
```

---

# 6. Pass API

## 6.1 Create Pass Product

```plain text
POST /api/v1/pass-products
```

### Request

```json
{
  "name": "10회권",
  "totalCount": 10,
  "price": 150000,
  "validDays": 90
}
```

### Response: 201 Created

```json
{
  "id": 1,
  "name": "10회권",
  "type": "COUNT_PASS",
  "totalCount": 10,
  "price": 150000,
  "validDays": 90,
  "createdAt": "2026-05-01T10:00:00+09:00"
}
```

### Errors

```plain text
400 VALIDATION_FAILED
```

## 6.2 List Pass Products

```plain text
GET /api/v1/pass-products?page=0&size=20
```

## 6.3 Get Pass Product

```plain text
GET /api/v1/pass-products/{passProductId}
```

### Errors

```plain text
404 PASS_PRODUCT_NOT_FOUND
```

## 6.4 Issue Member Pass

```plain text
POST /api/v1/member-passes
```

### Request

```json
{
  "memberId": 1,
  "passProductId": 1,
  "expiresAt": "2026-08-01T23:59:59+09:00"
}
```

### Response: 201 Created

```json
{
  "id": 1,
  "memberId": 1,
  "passProductId": 1,
  "productNameSnapshot": "10회권",
  "totalCount": 10,
  "remainingCount": 10,
  "status": "ACTIVE",
  "issuedAt": "2026-05-01T10:00:00+09:00",
  "expiresAt": "2026-08-01T23:59:59+09:00"
}
```

### Errors

```plain text
404 MEMBER_NOT_FOUND
404 PASS_PRODUCT_NOT_FOUND
409 MEMBER_INACTIVE
```

## 6.5 List Member Passes By Member

```plain text
GET /api/v1/members/{memberId}/passes?page=0&size=20
```

## 6.6 List Member Pass Usage Histories

```plain text
GET /api/v1/member-passes/{memberPassId}/usage-histories?page=0&size=20
```

### PassUsageHistoryResponse

```json
{
  "id": 1,
  "memberPassId": 1,
  "reservationId": 100,
  "type": "CONSUME",
  "reason": "RESERVATION_CONFIRMED",
  "changedCount": -1,
  "remainingCountAfter": 9,
  "createdAt": "2026-05-01T10:30:00+09:00"
}
```

---

# 7. Class Session API

## 7.1 Create Class Session

```plain text
POST /api/v1/class-sessions
```

### Request

```json
{
  "title": "초급 볼더링 클래스",
  "startsAt": "2026-05-10T19:00:00+09:00",
  "endsAt": "2026-05-10T20:00:00+09:00",
  "capacity": 12
}
```

### Response: 201 Created

```json
{
  "id": 1,
  "title": "초급 볼더링 클래스",
  "startsAt": "2026-05-10T19:00:00+09:00",
  "endsAt": "2026-05-10T20:00:00+09:00",
  "capacity": 12,
  "reservedCount": 0,
  "status": "OPEN",
  "createdAt": "2026-05-01T10:00:00+09:00",
  "canceledAt": null,
  "affectedReservationCount": 0
}
```

## 7.2 List Class Sessions

```plain text
GET /api/v1/class-sessions?page=0&size=20
```

## 7.3 Get Class Session

```plain text
GET /api/v1/class-sessions/{classSessionId}
```

## 7.4 Cancel Class Session

```plain text
PATCH /api/v1/class-sessions/{classSessionId}/cancel
```

### Request

```json
{
  "reason": "운영상 사유로 수업 취소"
}
```

### Errors

```plain text
404 CLASS_SESSION_NOT_FOUND
409 CLASS_SESSION_ALREADY_CANCELED
409 CONCURRENCY_CONFLICT
```

---

# 8. Reservation API

## 8.1 Create Reservation

```plain text
POST /api/v1/reservations
```

### Request

```json
{
  "memberId": 1,
  "classSessionId": 1
}
```

### Response: 201 Created

```json
{
  "id": 100,
  "memberId": 1,
  "classSessionId": 1,
  "memberPassId": 1,
  "status": "CONFIRMED",
  "reservedAt": "2026-05-01T10:30:00+09:00",
  "canceledAt": null,
  "cancelReason": null,
  "classSession": {
    "id": 1,
    "capacity": 12,
    "reservedCount": 1,
    "status": "OPEN"
  },
  "memberPass": {
    "id": 1,
    "remainingCount": 9,
    "status": "ACTIVE"
  }
}
```

### Errors

```plain text
400 VALIDATION_FAILED
404 MEMBER_NOT_FOUND
404 CLASS_SESSION_NOT_FOUND
409 MEMBER_INACTIVE
409 CLASS_SESSION_NOT_OPEN
409 CLASS_SESSION_FULL
409 DUPLICATE_RESERVATION
409 MEMBER_PASS_NOT_AVAILABLE
409 MEMBER_PASS_VERSION_CONFLICT
409 CONCURRENCY_CONFLICT
```

### State Changes

```plain text
ClassSession.reservedCount + 1
Reservation.status = CONFIRMED
MemberPass.remainingCount - 1
PassUsageHistory = CONSUME / RESERVATION_CONFIRMED
OutboxEvent = ReservationConfirmedEvent
```

## 8.2 List Reservations

```plain text
GET /api/v1/reservations?page=0&size=20
```

### Query Parameters

```plain text
memberId optional
classSessionId optional
status optional: CONFIRMED, CANCELED
```

## 8.3 Get Reservation

```plain text
GET /api/v1/reservations/{reservationId}
```

### Errors

```plain text
404 RESERVATION_NOT_FOUND
```

## 8.4 Cancel Reservation

```plain text
PATCH /api/v1/reservations/{reservationId}/cancel
```

### Request

```json
{
  "reason": "USER_REQUESTED"
}
```

MVP에서 사용자 요청 취소 API의 도메인 취소 사유는 항상 `USER_REQUESTED`이다. request body는 생략 가능하게 구현할 수 있다.

### State Changes

```plain text
Reservation.status = CANCELED
ClassSession.reservedCount - 1
MemberPass.remainingCount + 1
PassUsageHistory = RESTORE / RESERVATION_CANCELED
OutboxEvent = ReservationCanceledEvent
```

### Errors

```plain text
404 RESERVATION_NOT_FOUND
409 RESERVATION_ALREADY_CANCELED
409 MEMBER_PASS_RESTORE_NOT_ALLOWED
409 MEMBER_PASS_VERSION_CONFLICT
409 CONCURRENCY_CONFLICT
```

---

# 9. Enum Definitions

```plain text
AdminUserStatus: ACTIVE, INACTIVE
Role: MANAGER, STAFF
MemberStatus: ACTIVE, INACTIVE
PassProductType: COUNT_PASS
MemberPassStatus: ACTIVE, EXHAUSTED, EXPIRED, CANCELED
PassUsageHistoryType: CONSUME, RESTORE
PassUsageReason: RESERVATION_CONFIRMED, RESERVATION_CANCELED, CLASS_SESSION_CANCELED
ClassSessionStatus: OPEN, CLOSED, CANCELED
ReservationStatus: CONFIRMED, CANCELED
ReservationCancelReason: USER_REQUESTED, CLASS_SESSION_CANCELED
```

---

# 10. Error Code Catalog

## Common

```plain text
VALIDATION_FAILED          400
UNAUTHORIZED               401
INVALID_CREDENTIALS        401
FORBIDDEN                  403
RESOURCE_NOT_FOUND         404
CONCURRENCY_CONFLICT       409
INTERNAL_SERVER_ERROR      500
```

## Auth

```plain text
ADMIN_USER_INACTIVE        403
ADMIN_USER_NOT_FOUND       404
DUPLICATE_ADMIN_USER_EMAIL 409
LAST_ACTIVE_MANAGER_REQUIRED 409
ADMIN_USER_ROLE_CHANGE_NOT_ALLOWED 409
ADMIN_USER_STATUS_CHANGE_NOT_ALLOWED 409
```

## Member

```plain text
MEMBER_NOT_FOUND           404
DUPLICATE_MEMBER_PHONE     409
MEMBER_INACTIVE            409
```

## Pass

```plain text
PASS_PRODUCT_NOT_FOUND     404
MEMBER_PASS_NOT_FOUND      404
MEMBER_PASS_NOT_AVAILABLE  409
MEMBER_PASS_RESTORE_NOT_ALLOWED 409
MEMBER_PASS_VERSION_CONFLICT    409
```

## Class Session

```plain text
CLASS_SESSION_NOT_FOUND    404
CLASS_SESSION_NOT_OPEN     409
CLASS_SESSION_FULL         409
CLASS_SESSION_ALREADY_CANCELED 409
```

## Reservation

```plain text
RESERVATION_NOT_FOUND      404
DUPLICATE_RESERVATION      409
RESERVATION_ALREADY_CANCELED 409
```

---

# 11. Transaction and Concurrency Contract

## Reservation Create

```plain text
1. Member 조회 및 ACTIVE 검증
2. ClassSession 비관적 락 조회
3. ClassSession OPEN 및 정원 검증
4. CONFIRMED 중복 예약 검증
5. 사용 가능한 MemberPass 선택
6. ClassSession reservedCount 증가
7. Reservation CONFIRMED 생성
8. MemberPass 차감
9. PassUsageHistory 기록
10. ReservationConfirmedEvent OutboxEvent 기록
11. Commit
```

## Reservation Cancel

```plain text
1. Reservation 조회 및 CONFIRMED 검증
2. ClassSession 비관적 락 조회
3. Reservation CANCELED 변경
4. ClassSession reservedCount 감소
5. MemberPass 복구
6. PassUsageHistory 기록
7. ReservationCanceledEvent OutboxEvent 기록
8. Commit
```

## Class Session Cancel

```plain text
1. ClassSession 비관적 락 조회
2. ClassSession CANCELED 변경
3. 해당 수업의 CONFIRMED Reservation 목록 조회
4. Reservation별 CANCELED 변경
5. Reservation별 MemberPass 복구
6. Reservation별 PassUsageHistory 기록
7. ClassSession reservedCount = 0
8. ClassSessionCanceledEvent OutboxEvent 기록
9. Commit
```

---

# 12. DTO Naming Guide

## Request DTO

```plain text
LoginRequest
CreateAdminUserRequest
ChangeAdminUserRoleRequest
ActivateAdminUserRequest
DeactivateAdminUserRequest
CreateMemberRequest
CreatePassProductRequest
IssueMemberPassRequest
CreateClassSessionRequest
CreateReservationRequest
CancelReservationRequest
CancelClassSessionRequest
```

## Response DTO

```plain text
LoginResponse
AdminUserResponse
MemberResponse
PassProductResponse
MemberPassResponse
PassUsageHistoryResponse
ClassSessionResponse
ReservationResponse
ErrorResponse
PageResponse<T>
```

## Implementation Note

Controller DTO는 Application Command와 분리한다.

```plain text
CreateReservationRequest
→ ReserveClassCommand
→ ReservationApplicationService.reserveClass(command)
→ ReservationResponse
```

---

# 13. Open Decisions

## OD-01. ID 전략

현재 API 명세는 `Long` ID 기준이다. 구현에서 UUID를 선택하면 API path와 DTO 타입을 UUID로 통일한다.

## OD-02. Member activate API 포함 여부

Functional Spec에는 Member 상태 전이에 `INACTIVE -> ACTIVE`가 존재하지만, MVP 기능 목록은 회원 비활성화 중심이다. v0.1 API에서는 activate endpoint를 제외한다.

## OD-03. ClassSession close API 포함 여부

Domain Model에는 `CLOSED` 상태가 있으나 Functional Spec의 MVP 기능은 수업 생성/조회/취소이다. v0.1 API에서는 close endpoint를 제외한다.

## RD-04. AdminUser management API 포함 여부

AdminUser 등록과 Role 변경은 MVP API v0.1에 포함한다. 해당 기능은 MANAGER 전용으로 제공한다.

---

# 14. Acceptance Checklist

- `POST /auth/login`으로 ACTIVE 관리자 JWT 발급 가능
- MANAGER는 AdminUser를 등록할 수 있음
- MANAGER는 AdminUser Role을 변경할 수 있음
- STAFF는 AdminUser 등록과 Role 변경을 수행할 수 없음
- 중복 email AdminUser 등록은 `409 DUPLICATE_ADMIN_USER_EMAIL`
- `POST /members`로 ACTIVE 회원 생성 가능
- 중복 phone 회원 생성은 `409 DUPLICATE_MEMBER_PHONE`
- `POST /pass-products`로 COUNT_PASS 상품 생성 가능
- `POST /member-passes`로 ACTIVE 회원에게 횟수권 발급 가능
- `POST /class-sessions`로 OPEN 수업 생성 가능
- `POST /reservations` 성공 시 예약 생성, 좌석 증가, 이용권 차감, 이력 기록이 함께 처리됨
- 같은 회원의 같은 수업 중복 CONFIRMED 예약은 `409 DUPLICATE_RESERVATION`
- 정원 초과 예약은 `409 CLASS_SESSION_FULL`
- 사용 가능한 이용권이 없으면 `409 MEMBER_PASS_NOT_AVAILABLE`
- `PATCH /reservations/{reservationId}/cancel` 성공 시 예약 취소, 좌석 복구, 이용권 복구, 이력 기록이 함께 처리됨
- 이미 취소된 예약의 취소 요청은 `409 RESERVATION_ALREADY_CANCELED`
- `PATCH /class-sessions/{classSessionId}/cancel` 성공 시 해당 수업의 CONFIRMED 예약이 모두 CANCELED 처리되고 각 MemberPass가 복구됨
- 모든 예약/취소/수업취소 이벤트는 OutboxEvent로 같은 트랜잭션에 기록됨
