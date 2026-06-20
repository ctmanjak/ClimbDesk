# ClimbDesk MVP API 사용 시나리오

## 1. 문서 목적

이 문서는 ClimbDesk API 전체 계약을 다시 나열하지 않고, 새 독자가 MVP의 핵심 흐름인 회원 등록부터 예약 생성·조회·취소까지 순서대로 실행해보도록 안내한다.

상세 endpoint, validation, paging, 전체 error code는 [API Spec](04-api-spec.md)을 기준으로 한다. 로컬 실행, 환경 변수, 첫 MANAGER 생성 방법은 [README](../README.md)를 먼저 확인한다.

## 2. 전제와 예시 규칙

- 애플리케이션은 `http://localhost:8080`에서 실행 중이라고 가정한다.
- 첫 `MANAGER` 계정은 README의 bootstrap 절차로 이미 생성되어 있어야 한다.
- 로그인 외 API는 `MANAGER` 또는 `STAFF` Bearer token이 필요하다. 이 문서는 전체 흐름을 `MANAGER`로 실행한다.
- 아래 ID, timestamp, token, trace ID는 응답 형태를 설명하기 위한 예시다. 실제 실행 값으로 바꿔 사용한다.
- 모든 API date-time은 ISO-8601 UTC instant 형식이다.
- ClimbDesk MVP는 프론트엔드, 결제, 실제 알림 발송, 대기 예약, 출석 체크, 노쇼, 반복 수업을 제공하지 않는다.

공통 shell 변수:

```shell
BASE_URL="http://localhost:8080/api/v1"
TOKEN="<로그인 응답의 accessToken>"
MEMBER_ID=1
PASS_PRODUCT_ID=1
MEMBER_PASS_ID=1
CLASS_SESSION_ID=1
RESERVATION_ID=100
```

## 3. 인증 흐름

### 3.1 MANAGER 로그인

```shell
curl -i -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "manager@climbdesk.local",
    "password": "password1234"
  }'
```

성공 응답은 `200 OK`다.

```json
{
  "accessToken": "<예시-JWT-access-token>",
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

이후 요청에는 다음 header를 사용한다.

```plain text
Authorization: Bearer <accessToken>
```

토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`, 필요한 권한이 없으면 `403 FORBIDDEN`이다.

### 3.2 첫 MANAGER bootstrap 이후 관리자 추가

`POST /api/v1/admin-users` 자체가 MANAGER 전용이므로 첫 계정은 API로 만들 수 없다. 첫 MANAGER를 README 절차로 생성하고 로그인한 뒤에만 다른 MANAGER 또는 STAFF를 추가한다.

```shell
curl -i -X POST "$BASE_URL/admin-users" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "staff@climbdesk.local",
    "password": "password1234",
    "role": "STAFF"
  }'
```

성공 응답은 `201 Created`다.

```json
{
  "id": 2,
  "email": "staff@climbdesk.local",
  "role": "STAFF",
  "status": "ACTIVE",
  "createdAt": "2026-06-20T01:00:00Z"
}
```

## 4. 기본 실행 흐름

### 4.1 회원 생성

```shell
curl -i -X POST "$BASE_URL/members" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "홍길동",
    "phone": "010-1234-5678",
    "email": "hong@example.com"
  }'
```

성공 응답은 `201 Created`다. 반환된 `id`를 `MEMBER_ID`로 사용한다.

```json
{
  "id": 1,
  "name": "홍길동",
  "phone": "010-1234-5678",
  "email": "hong@example.com",
  "status": "ACTIVE",
  "createdAt": "2026-06-20T01:05:00Z",
  "deactivatedAt": null
}
```

### 4.2 이용권 상품 생성

MVP는 횟수권인 `COUNT_PASS`만 지원한다.

```shell
curl -i -X POST "$BASE_URL/pass-products" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "10회권",
    "totalCount": 10,
    "price": 150000,
    "validDays": 90
  }'
```

성공 응답은 `201 Created`다. 반환된 `id`를 `PASS_PRODUCT_ID`로 사용한다.

```json
{
  "id": 1,
  "name": "10회권",
  "type": "COUNT_PASS",
  "totalCount": 10,
  "price": 150000,
  "validDays": 90,
  "createdAt": "2026-06-20T01:10:00Z"
}
```

### 4.3 회원 이용권 발급

```shell
curl -i -X POST "$BASE_URL/member-passes" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"memberId\": $MEMBER_ID,
    \"passProductId\": $PASS_PRODUCT_ID,
    \"expiresAt\": \"2026-09-30T23:59:59Z\"
  }"
```

성공 응답은 `201 Created`다. 반환된 `id`를 `MEMBER_PASS_ID`로 사용한다.

```json
{
  "id": 1,
  "memberId": 1,
  "passProductId": 1,
  "productNameSnapshot": "10회권",
  "totalCount": 10,
  "remainingCount": 10,
  "status": "ACTIVE",
  "issuedAt": "2026-06-20T01:15:00Z",
  "expiresAt": "2026-09-30T23:59:59Z"
}
```

`expiresAt`은 선택값이다. 생략하면 응답에서도 `null`이다.

### 4.4 수업 생성

```shell
curl -i -X POST "$BASE_URL/class-sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "초급 볼더링 클래스",
    "startsAt": "2026-07-10T10:00:00Z",
    "endsAt": "2026-07-10T11:00:00Z",
    "capacity": 12
  }'
```

성공 응답은 `201 Created`다. 반환된 `id`를 `CLASS_SESSION_ID`로 사용한다.

```json
{
  "id": 1,
  "title": "초급 볼더링 클래스",
  "startsAt": "2026-07-10T10:00:00Z",
  "endsAt": "2026-07-10T11:00:00Z",
  "capacity": 12,
  "reservedCount": 0,
  "status": "OPEN",
  "createdAt": "2026-06-20T01:20:00Z",
  "canceledAt": null,
  "affectedReservationCount": 0
}
```

### 4.5 예약 생성

```shell
curl -i -X POST "$BASE_URL/reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"memberId\": $MEMBER_ID,
    \"classSessionId\": $CLASS_SESSION_ID
  }"
```

성공 응답은 `201 Created`다. 반환된 `id`를 `RESERVATION_ID`로 사용한다.

```json
{
  "id": 100,
  "memberId": 1,
  "classSessionId": 1,
  "memberPassId": 1,
  "status": "CONFIRMED",
  "reservedAt": "2026-06-20T01:25:00Z",
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

한 번의 예약 생성 트랜잭션에서 다음 변경이 함께 commit된다.

- 예약이 `CONFIRMED`로 생성된다.
- 수업 `reservedCount`가 `0`에서 `1`로 증가한다.
- 선택된 회원 이용권 `remainingCount`가 `10`에서 `9`로 감소한다.
- `CONSUME / RESERVATION_CONFIRMED` 사용 이력이 저장된다.
- `ReservationConfirmedEvent` outbox event가 저장된다.

이 중 하나라도 실패하면 예약, 좌석, 이용권, 이력, outbox 변경 전체가 rollback되어야 한다.

### 4.6 예약 조회

```shell
curl -i "$BASE_URL/reservations/$RESERVATION_ID" \
  -H "Authorization: Bearer $TOKEN"
```

성공 응답은 `200 OK`이며 예약 상태와 현재 수업·이용권 상태를 함께 반환한다.

```json
{
  "id": 100,
  "memberId": 1,
  "classSessionId": 1,
  "memberPassId": 1,
  "status": "CONFIRMED",
  "reservedAt": "2026-06-20T01:25:00Z",
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

목록과 필터가 필요하면 `GET /api/v1/reservations?memberId=1&classSessionId=1&status=CONFIRMED`를 사용한다. 목록 응답은 `items`, `page`, `size`, `totalElements`, `totalPages`를 포함한다.

### 4.7 예약 취소

현재 구현은 request body 없이 취소하며 취소 사유를 `USER_REQUESTED`로 기록한다.

```shell
curl -i -X PATCH "$BASE_URL/reservations/$RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

성공 응답은 `200 OK`다.

```json
{
  "id": 100,
  "memberId": 1,
  "classSessionId": 1,
  "memberPassId": 1,
  "status": "CANCELED",
  "reservedAt": "2026-06-20T01:25:00Z",
  "canceledAt": "2026-06-20T01:35:00Z",
  "cancelReason": "USER_REQUESTED",
  "classSession": {
    "id": 1,
    "capacity": 12,
    "reservedCount": 0,
    "status": "OPEN"
  },
  "memberPass": {
    "id": 1,
    "remainingCount": 10,
    "status": "ACTIVE"
  }
}
```

한 번의 예약 취소 트랜잭션에서 다음 변경이 함께 commit된다.

- 예약이 `CANCELED`로 변경되고 `cancelReason`은 `USER_REQUESTED`가 된다.
- 수업 `reservedCount`가 `1`에서 `0`으로 감소한다.
- 회원 이용권 `remainingCount`가 `9`에서 `10`으로 복구된다.
- `RESTORE / RESERVATION_CANCELED` 사용 이력이 저장된다.
- `ReservationCanceledEvent` outbox event가 저장된다.

### 4.8 이용권 사용 이력 확인

```shell
curl -i "$BASE_URL/member-passes/$MEMBER_PASS_ID/usage-histories?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

성공 응답은 `200 OK`다. 최신 이력이 먼저 반환되므로 취소 복구 이력이 예약 차감 이력보다 앞에 나온다.

```json
{
  "items": [
    {
      "id": 2,
      "memberPassId": 1,
      "reservationId": 100,
      "type": "RESTORE",
      "reason": "RESERVATION_CANCELED",
      "changedCount": 1,
      "remainingCountAfter": 10,
      "createdAt": "2026-06-20T01:35:00Z"
    },
    {
      "id": 1,
      "memberPassId": 1,
      "reservationId": 100,
      "type": "CONSUME",
      "reason": "RESERVATION_CONFIRMED",
      "changedCount": -1,
      "remainingCountAfter": 9,
      "createdAt": "2026-06-20T01:25:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

## 5. 상태 변화 확인

| 확인 대상 | 발급/생성 직후 | 예약 생성 후 | 예약 취소 후 |
| --- | ---: | ---: | ---: |
| 수업 `reservedCount` | 0 | 1 | 0 |
| 회원 이용권 `remainingCount` | 10 | 9 | 10 |
| 예약 상태 | 없음 | `CONFIRMED` | `CANCELED` |
| 사용 이력 | 없음 | `CONSUME` 1건 | `CONSUME`, `RESTORE` 각 1건 |
| 예약 outbox event | 없음 | `ReservationConfirmedEvent` | 확인/취소 event 각 1건 |

`reservedCount`와 `remainingCount`는 예약 생성·취소 응답 또는 예약 단건 조회로 확인할 수 있다. 사용 이력은 API로 확인한다.

OutboxEvent는 내부 구현 요소이므로 public API가 없다. PostgreSQL에서 확인해야 한다.

```sql
select
  event_type,
  aggregate_type,
  aggregate_id,
  status,
  payload,
  occurred_at
from outbox_events
where aggregate_type = 'Reservation'
  and aggregate_id = 100
order by id;
```

위 기본 흐름을 완료하면 다음 두 행이 저장되어야 한다.

```plain text
ReservationConfirmedEvent | Reservation | 100 | PENDING
ReservationCanceledEvent  | Reservation | 100 | PENDING
```

MVP는 outbox 저장까지 구현하며 실제 알림 발송이나 외부 broker 연동을 제공하지 않는다.

## 6. 주요 실패 시나리오

실패 응답은 공통적으로 `timestamp`, `status`, `code`, `message`, `path`, `traceId`를 포함한다. 아래 값도 예시다.

### 6.1 중복 예약

예약을 취소하기 전에 4.5의 요청을 같은 `memberId`, `classSessionId`로 다시 호출한다.

```shell
curl -i -X POST "$BASE_URL/reservations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"memberId\": $MEMBER_ID,
    \"classSessionId\": $CLASS_SESSION_ID
  }"
```

응답은 `409 Conflict`다.

```json
{
  "timestamp": "2026-06-20T01:26:00Z",
  "status": 409,
  "code": "DUPLICATE_RESERVATION",
  "message": "Member already has a confirmed reservation for this class session.",
  "path": "/api/v1/reservations",
  "traceId": "example-trace-duplicate"
}
```

기존 예약, `reservedCount`, `remainingCount`, 사용 이력, outbox event는 추가로 변경되지 않아야 한다.

### 6.2 정원 초과

`reservedCount == capacity`인 수업에 다른 ACTIVE 회원이 예약을 시도하면 다음 결과가 반환된다.

```plain text
HTTP 409 Conflict
code: CLASS_SESSION_FULL
message: Class session is full.
```

실패한 회원의 이용권은 차감되지 않고 예약·이력·outbox event도 생성되지 않아야 한다.

### 6.3 사용 가능한 이용권 없음

이용권을 발급하지 않은 ACTIVE 회원으로 예약 생성을 호출한다.

```plain text
HTTP 409 Conflict
code: MEMBER_PASS_NOT_AVAILABLE
message: Member pass is not available.
```

`EXHAUSTED`, `EXPIRED`, `CANCELED`, 잔여 횟수 0인 이용권도 예약에 사용할 수 없다.

### 6.4 비활성 회원 예약 시도

먼저 회원을 비활성화한다.

```shell
curl -i -X PATCH "$BASE_URL/members/$MEMBER_ID/deactivate" \
  -H "Authorization: Bearer $TOKEN"
```

그 회원으로 예약 생성을 호출하면 다음 결과가 반환된다.

```plain text
HTTP 409 Conflict
code: MEMBER_INACTIVE
message: Member is inactive.
```

회원 비활성화는 기존 예약을 자동 취소하지 않지만 신규 예약은 차단한다.

### 6.5 이미 취소된 예약 취소 시도

4.7의 취소 요청을 같은 `RESERVATION_ID`로 다시 호출한다.

```shell
curl -i -X PATCH "$BASE_URL/reservations/$RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

응답은 다음과 같다.

```plain text
HTTP 409 Conflict
code: RESERVATION_ALREADY_CANCELED
message: Reservation is already canceled.
```

좌석과 이용권은 두 번 복구되지 않으며 사용 이력과 outbox event도 추가 생성되지 않아야 한다.

## 7. 다음 문서

- 상세 endpoint, DTO, status code, error code: [API Spec](04-api-spec.md)
- 기능 규칙과 트랜잭션 정책: [Functional Spec](02-functional-spec.md)
- 테스트 범위와 검증 기준: [Test Strategy](07-test-strategy.md)
- 로컬 실행과 첫 MANAGER bootstrap: [README](../README.md)
