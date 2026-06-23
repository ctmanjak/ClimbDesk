# ClimbDesk Functional Spec v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `02 - Functional Spec`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: PRD의 MVP 범위를 실제 구현 가능한 기능 단위로 분해하고, 각 기능의 입력, 처리 규칙, 상태 변화, 예외, 테스트 기준을 정의한다.

---

# 1. 문서 상태

## Status

```plain text
Draft
```

## Source Documents

- `01 - PRD`: MVP 범위, 핵심 비즈니스 규칙, 성공 기준
- `03 - Domain Model`: Aggregate, 상태 전이, 트랜잭션 경계, 이벤트 정책

## 작성 원칙

- 이 문서는 API endpoint 목록이 아니라 기능 행위와 비즈니스 규칙을 정의한다.
- API path, request/response DTO, status code는 `04 - API Spec`에서 구체화한다.
- DB schema, index, lock query는 `06 - Database Design`에서 구체화한다.
- MVP 구현 가능성과 포트폴리오 설득력을 우선한다.

---

# 2. MVP Functional Scope

## Included

- 관리자 로그인
- JWT 기반 인증
- Role 기반 권한 제어
- AdminUser 생성
- AdminUser Role 변경
- AdminUser 활성화
- AdminUser 비활성화
- 회원 등록, 조회, 비활성화
- 횟수권 상품 생성
- 회원 횟수권 발급
- 이용권 차감, 복구, 이력 기록
- 수업 생성, 조회, 취소
- 수업 예약
- 예약 취소
- 정원 초과 방지
- 중복 예약 방지
- 예약/취소/수업취소 도메인 이벤트 기록 또는 발행

## Out of Scope

- 기간권/일일권
- 수업 수정
- 반복 수업
- 강사 배정
- 출석 체크
- 대기 예약
- 실제 결제 연동
- 실제 알림 발송
- 멀티 지점 운영
- 통계 대시보드
- 프론트엔드 앱

---

# 3. Actors and Permissions

| Actor | 설명 | 주요 권한 |
| --- | --- | --- |
| MANAGER | 클라이밍장 운영 관리자 | 전체 운영 기능, 관리자 계정/권한 관리 |
| STAFF | 현장 운영 스태프 | 회원, 이용권, 수업, 예약 운영 기능 |

## Permission Policy

- 인증이 필요한 모든 운영 기능은 JWT를 요구한다.
- `MANAGER`는 모든 MVP 기능을 수행할 수 있다.
- `STAFF`는 회원, 이용권, 수업, 예약 등 운영 기능을 수행할 수 있다.
- `STAFF`는 관리자 계정 생성/Role 변경/활성화/비활성화를 할 수 없다.
- 비활성 관리자 계정은 로그인할 수 없다.

---

# 4. Common Functional Policies

## ID Policy

- 모든 Aggregate는 시스템 내부 식별자를 가진다.
- 다른 Aggregate 참조는 객체 참조가 아니라 ID 참조를 기본으로 한다.

## Error Policy

- 비즈니스 규칙 위반은 명시적인 도메인/애플리케이션 예외로 처리한다.
- 동일한 실패 원인은 테스트 가능한 error code로 매핑한다.
- 동시성 충돌은 숨기지 않고 예약 실패로 반환한다.
- MVP 기본 정책은 자동 재시도 없음이다.

## Transaction Policy

- 예약 생성은 하나의 트랜잭션으로 처리한다.
- 예약 취소는 하나의 트랜잭션으로 처리한다.
- 수업 취소와 기존 예약 자동 취소는 하나의 트랜잭션으로 처리한다.
- MVP는 OutboxEvent를 포함한다.
- 도메인 상태 변경과 OutboxEvent 저장은 같은 비즈니스 트랜잭션 안에서 처리한다.

---

# 5. Auth Functions

## 5.1 관리자 로그인

### Goal

관리자가 이메일/비밀번호로 로그인하고 JWT를 발급받는다.

### Preconditions

- AdminUser가 존재해야 한다.
- AdminUser 상태가 `ACTIVE`여야 한다.

### Input

```plain text
email
password
```

### Functional Flow

```plain text
1. email로 AdminUser 조회
2. AdminUser 존재 여부 확인
3. AdminUser ACTIVE 상태 확인
4. password 검증
5. JWT access token 발급
6. 로그인 결과 반환
```

### Business Rules

- 비활성 관리자 계정은 로그인할 수 없다.
- 비밀번호 원문은 저장하지 않는다.
- JWT 발급/검증은 Infrastructure 책임이다.

### Acceptance Criteria

- ACTIVE 관리자는 올바른 비밀번호로 로그인할 수 있다.
- 잘못된 인증 정보는 토큰을 발급하지 않는다.
- INACTIVE 관리자는 토큰을 발급받을 수 없다.

## 5.2 AdminUser 생성

### Goal

MANAGER가 신규 운영자 계정을 등록한다.

### Preconditions

- 요청자는 인증된 MANAGER여야 한다.

### Input

```plain text
email
password
role
```

### Functional Flow

```plain text
1. 요청자 확인
2. 요청자 Role 확인
3. email 중복 여부 확인
4. password hash 생성
5. AdminUser 생성
6. status = ACTIVE 설정
7. AdminUser 저장
```

### Business Rules

- MANAGER만 AdminUser를 등록할 수 있다.
- STAFF는 AdminUser를 등록할 수 없다.
- email은 unique로 관리한다.
- 신규 AdminUser의 기본 상태는 ACTIVE이다.
- 생성 가능한 Role은 MANAGER, STAFF이다.
- password는 AdminUser 생성 요청에서만 입력받는다.
- password 원문은 저장하지 않고 passwordHash만 저장한다.
- 생성된 AdminUser는 별도 초대 플로우 없이 로그인할 수 있다.

## 5.3 AdminUser Role 변경

### Functional Flow

```plain text
1. 요청자 확인
2. 요청자 Role 확인
3. 대상 AdminUser 조회
4. Last Active Manager Protection 검증
5. 대상 AdminUser.changeRole(role) 실행
6. 대상 AdminUser 저장
```

### Business Rules

- MANAGER만 AdminUser Role을 변경할 수 있다.
- 변경 가능한 Role은 MANAGER, STAFF이다.
- 비활성 AdminUser의 Role 변경은 허용한다.
- 마지막 ACTIVE MANAGER는 STAFF로 변경할 수 없다.
- Role 변경 후에도 최소 1명의 ACTIVE MANAGER가 남아 있어야 한다.

## 5.4 AdminUser 활성화/비활성화

### Functional Flow

```plain text
1. 요청자 확인
2. 요청자 Role 확인
3. 대상 AdminUser 조회
4. targetStatus가 INACTIVE인 경우 Last Active Manager Protection 검증
5. targetStatus에 따라 activate() 또는 deactivate() 실행
6. AdminUser 저장
```

### Business Rules

- MANAGER만 AdminUser 상태를 변경할 수 있다.
- INACTIVE AdminUser는 로그인할 수 없다.
- 이미 같은 상태인 경우 멱등 처리할 수 있다.
- 마지막 ACTIVE MANAGER는 INACTIVE로 변경할 수 없다.

---

# 6. Member Functions

## 6.1 회원 등록

### Input

```plain text
name
phone
email(optional)
```

### Functional Flow

```plain text
1. 회원 등록 요청 검증
2. 회원 중복 정책 확인
3. Member 생성
4. Member 상태를 ACTIVE로 설정
5. Member 저장
```

### Business Rules

- 신규 회원의 기본 상태는 `ACTIVE`이다.
- 회원은 물리 삭제하지 않고 비활성화한다.
- 회원 phone은 unique로 관리한다.
- 같은 phone을 가진 회원은 중복 등록할 수 없다.

## 6.2 회원 조회

- MVP에서는 고급 검색/필터링을 제공하지 않는다.
- 기본 조회는 최신 등록순 또는 ID 역순 정렬을 사용한다.
- 회원 목록과 단일 회원 조회를 제공한다.
- 존재하지 않는 회원 ID는 not found로 처리한다.

## 6.3 회원 비활성화

```plain text
1. Member 조회
2. Member 상태 확인
3. Member.deactivate() 실행
4. Member 저장
```

- INACTIVE 회원은 신규 예약을 생성할 수 없다.
- 기존 예약 자동 취소는 MVP 기본 동작에 포함하지 않는다.
- 이미 INACTIVE인 회원에 대한 요청은 멱등 처리 가능하다.

---

# 7. Pass Functions

## 7.1 횟수권 상품 생성

### Input

```plain text
name
totalCount
price(optional)
validDays(optional)
```

- MVP에서는 `COUNT_PASS`만 지원한다.
- `totalCount`는 1 이상이어야 한다.
- 상품 변경이 이미 발급된 MemberPass에 영향을 주지 않도록, 발급 시 상품 정보를 스냅샷으로 복사한다.

## 7.2 회원 횟수권 발급

### Preconditions

- Member가 존재해야 한다.
- Member 상태가 `ACTIVE`여야 한다.
- PassProduct가 존재해야 한다.

### Functional Flow

```plain text
1. Member 조회 및 ACTIVE 검증
2. PassProduct 조회
3. PassProduct 스냅샷 생성
4. MemberPass 생성
5. remainingCount = totalCount 설정
6. MemberPass 상태를 ACTIVE로 설정
7. MemberPass 저장
```

## 7.3 이용권 차감

### Trigger

- 수업 예약 생성

### Preconditions

- MemberPass 상태가 `ACTIVE`여야 한다.
- MemberPass가 만료되지 않아야 한다.
- remainingCount가 1 이상이어야 한다.

### Available Pass Selection Policy

```plain text
1. 만료일이 가장 빠른 이용권 우선
2. 만료일이 같으면 발급일이 가장 빠른 이용권 우선
3. 그래도 같으면 memberPassId 오름차순
```

### Business Rules

- remainingCount는 0 미만이 될 수 없다.
- 차감과 사용 이력 기록은 같은 트랜잭션에 포함되어야 한다.
- MemberPass는 낙관적 락을 사용한다.
- version 충돌 시 예약 생성 전체를 실패 처리한다.

## 7.4 이용권 복구

### Triggers

- 사용자 요청에 의한 예약 취소
- 수업 취소에 의한 예약 자동 취소

### Functional Flow

```plain text
1. Reservation에서 memberPassId 확인
2. MemberPass 조회
3. MemberPass.restore(reservationId, reason) 실행
4. remainingCount 1 증가
5. PassUsageHistory 기록
6. 필요한 경우 EXHAUSTED -> ACTIVE 상태 복구
```

### Business Rules

- CANCELED 상태의 MemberPass는 복구할 수 없다.
- EXPIRED 상태에서도 과거 예약 취소에 따른 회계적 복구는 허용할 수 있으나 상태는 EXPIRED로 유지한다.
- EXHAUSTED 상태에서 복구되어 remainingCount > 0이고 만료되지 않았다면 ACTIVE로 돌아갈 수 있다.
- 복구와 복구 이력 기록은 같은 트랜잭션에 포함되어야 한다.

---

# 8. Class Session Functions

## 8.1 수업 생성

```plain text
1. 수업 생성 요청 검증
2. ClassSession 생성
3. status = OPEN 설정
4. reservedCount = 0 설정
5. ClassSession 저장
```

- capacity는 1 이상이어야 한다.
- startsAt은 endsAt보다 빨라야 한다.
- 신규 수업의 기본 상태는 `OPEN`이다.
- MVP에서는 반복 수업과 강사 배정은 제외한다.

## 8.2 수업 조회

- 수업 목록을 조회할 수 있다.
- 수업 ID로 단일 수업을 조회할 수 있다.
- 수업 상태와 reservedCount를 확인할 수 있다.

## 8.3 수업 취소

```plain text
1. ClassSession을 비관적 락으로 조회
2. ClassSession.cancel() 실행
3. 해당 수업의 CONFIRMED Reservation 목록 조회
4. 각 Reservation.cancel(CLASS_SESSION_CANCELED) 실행
5. 각 Reservation의 MemberPass 복구
6. 각 복구에 대해 PassUsageHistory 기록
7. ClassSession.reservedCount 감소 처리
8. ClassSessionCanceledEvent 기록 또는 발행
9. 전체 변경 저장
```

- 수업 취소는 하나의 트랜잭션으로 처리한다.
- 수업 취소 중 하나의 MemberPass 복구라도 실패하면 전체 트랜잭션을 롤백한다.
- 수업 취소와 수업 예약이 동시에 들어오면 ClassSession 비관적 락 순서에 의해 하나가 먼저 완료된다.
- 취소 완료 후 ClassSession 상태는 `CANCELED`이다.
- 취소 완료 후 해당 수업의 CONFIRMED 예약은 없어야 한다.

---

# 9. Reservation Functions

## 9.1 수업 예약

### Preconditions

- Member가 존재해야 한다.
- Member 상태가 `ACTIVE`여야 한다.
- ClassSession이 존재해야 한다.
- ClassSession 상태가 `OPEN`이어야 한다.
- ClassSession reservedCount가 capacity보다 작아야 한다.
- 같은 회원의 같은 수업 CONFIRMED 예약이 없어야 한다.
- 사용 가능한 MemberPass가 있어야 한다.

### Functional Flow

```plain text
1. Member 조회 및 ACTIVE 검증
2. ClassSession 비관적 락 조회
3. ClassSession 예약 가능 상태 검증
4. CONFIRMED 중복 Reservation 검증
5. 사용 가능한 MemberPass 선택
6. ClassSession.reserveSeat() 실행
7. Reservation 생성
8. MemberPass.consume(reservationId) 실행
9. PassUsageHistory 기록
10. ReservationConfirmedEvent 기록 또는 발행
11. Reservation, ClassSession, MemberPass 저장
```

### Business Rules

- OPEN 상태 수업만 예약할 수 있다.
- reservedCount는 capacity를 초과할 수 없다.
- 같은 회원은 같은 수업에 CONFIRMED 예약을 동시에 2개 이상 가질 수 없다.
- CANCELED 예약 이력이 있어도 같은 수업에 다시 예약할 수 있다.
- 좌석 증가, 예약 생성, 이용권 차감, 사용 이력 기록은 하나의 트랜잭션으로 처리한다.
- ClassSession은 비관적 락으로 동시 예약을 순차 처리한다.
- MemberPass는 낙관적 락으로 차감 충돌을 감지한다.
- 중복 예약 방지는 Application pre-check와 DB unique constraint를 함께 사용한다.

### Failure Cases

| Case | Expected Result |
| --- | --- |
| Member 없음 또는 INACTIVE | 예약 실패 |
| ClassSession 없음 또는 CLOSED/CANCELED | 예약 실패 |
| 정원 초과 | 예약 실패 |
| CONFIRMED 중복 예약 | 예약 실패 |
| 사용 가능한 MemberPass 없음 | 예약 실패 |
| MemberPass version 충돌 | 예약 실패 및 전체 롤백 |

## 9.2 예약 취소

### Preconditions

- Reservation이 존재해야 한다.
- Reservation 상태가 `CONFIRMED`여야 한다.

### Functional Flow

```plain text
1. Reservation 조회
2. Reservation 상태 검증
3. ClassSession 비관적 락 조회
4. Reservation.cancel(USER_REQUESTED) 실행
5. ClassSession.cancelSeat() 실행
6. Reservation의 memberPassId로 MemberPass 조회
7. MemberPass.restore(reservationId, RESERVATION_CANCELED) 실행
8. PassUsageHistory 기록
9. ReservationCanceledEvent 기록 또는 발행
10. Reservation, ClassSession, MemberPass 저장
```

- 이미 CANCELED인 예약은 다시 취소할 수 없다.
- 예약 취소, 좌석 복구, 이용권 복구, 복구 이력 기록은 하나의 트랜잭션으로 처리한다.
- 예약 취소 후 같은 회원은 같은 수업에 다시 예약할 수 있다.
- 취소된 예약은 이력으로 남긴다.

---

# 10. State Transition Summary

## Member

```plain text
ACTIVE -> INACTIVE    deactivate
```

`INACTIVE -> ACTIVE` 회원 재활성화는 MVP public use case/API에 포함하지 않는다.
필요해지면 별도 product decision으로 API와 권한 정책을 먼저 정의한 뒤 구현한다.

## MemberPass

```plain text
ACTIVE -> ACTIVE       consume, remainingCount > 0
ACTIVE -> EXHAUSTED    consume, remainingCount == 0
EXHAUSTED -> ACTIVE    restore, remainingCount > 0 and not expired
```

`EXPIRED`와 `CANCELED` 상태는 MVP에서 사용 가능 여부와 복구 가능 여부를 판단하는 내부/future lifecycle state로 유지한다.
MemberPass `expire()`/`cancel()` public use case/API, batch expiration, pass cancellation workflow는 MVP에 포함하지 않는다.
필요해지면 별도 product decision으로 Functional Spec/API Spec/Test Strategy를 먼저 갱신한다.

## ClassSession

```plain text
OPEN -> CANCELED
```

`CLOSED` 상태는 MVP에서 예약 가능 여부를 판단하는 내부/future lifecycle state로 유지한다.
ClassSession `close()` public use case/API와 class update/closing workflow는 MVP에 포함하지 않는다.
MVP public use case는 수업 생성/조회/취소이며, `OPEN -> CANCELED` 수업 취소만 노출한다.

## Reservation

```plain text
CONFIRMED -> CANCELED
```

---

# 11. Domain Events

## MVP Events

```plain text
ReservationConfirmedEvent
ReservationCanceledEvent
ClassSessionCanceledEvent
```

## Event Recording Policy

- 이벤트는 도메인에서 발생한 사실을 표현한다.
- 도메인 모델은 Spring ApplicationEvent에 직접 의존하지 않는다.
- Application Service는 이벤트 발행 또는 기록을 위한 포트에 의존한다.
- MVP는 OutboxEvent 저장을 포함한다.
- 도메인 상태 변경과 OutboxEvent 저장은 같은 트랜잭션에 포함한다.

---

# 12. Concurrency and Consistency

## ClassSession Locking

- 예약 생성 시 ClassSession을 비관적 락으로 조회한다.
- 수업 취소 시 ClassSession을 비관적 락으로 조회한다.
- 동일 수업에 대한 예약 생성과 수업 취소는 순차 처리된다.

## MemberPass Locking

- MemberPass는 낙관적 락을 사용한다.
- 차감/복구 저장 시 version 충돌이 발생하면 전체 유스케이스를 실패 처리한다.
- MVP에서는 자동 재시도하지 않는다.

## Duplicate Reservation Constraint

DB가 partial unique index를 지원하는 경우:

```plain text
unique(member_id, class_session_id) where status = 'CONFIRMED'
```

DB가 partial unique index를 지원하지 않는 경우:

```plain text
active_reservation_key = member_id + ':' + class_session_id
CONFIRMED일 때만 값 설정
CANCELED일 때 null
unique(active_reservation_key)
```

## Required Invariants

```plain text
ClassSession.reservedCount <= ClassSession.capacity
MemberPass.remainingCount >= 0
Member.status == ACTIVE 인 회원만 예약 가능
ClassSession.status == OPEN 인 수업만 예약 가능
같은 회원은 같은 수업에 CONFIRMED 예약을 2개 이상 가질 수 없음
예약 생성 시 좌석 증가와 이용권 차감은 같은 트랜잭션에 포함
예약 취소 시 좌석 감소와 이용권 복구는 같은 트랜잭션에 포함
수업 취소 시 기존 예약 자동 취소와 이용권 복구는 같은 트랜잭션에 포함
```

---

# 13. Test Scenarios

## Auth

- ACTIVE AdminUser는 올바른 인증 정보로 로그인 성공
- INACTIVE AdminUser는 로그인 실패
- MANAGER는 AdminUser 등록 성공
- STAFF는 AdminUser 등록 실패
- 중복 email AdminUser 등록 실패
- MANAGER는 AdminUser Role 변경 성공
- STAFF는 AdminUser Role 변경 실패
- 마지막 ACTIVE MANAGER Role 변경 실패
- 마지막 ACTIVE MANAGER 비활성화 실패

## Reservation

- ACTIVE 회원 + OPEN 수업 + 사용 가능한 이용권이면 예약 성공
- INACTIVE 회원은 예약 실패
- CLOSED/CANCELED 수업은 예약 실패
- 사용 가능한 이용권이 없으면 예약 실패
- 같은 회원의 같은 수업 중복 CONFIRMED 예약은 실패
- 취소된 예약이 있으면 같은 수업 재예약 가능
- capacity 초과 동시 요청에서도 reservedCount <= capacity 유지

## Cancellation

- CONFIRMED 예약 취소 성공
- 예약 취소 시 reservedCount 1 감소
- 예약 취소 시 MemberPass remainingCount 1 복구
- 이미 CANCELED 예약 취소 요청은 명시적 실패
- 예약 취소 후 재예약 가능

## Class Cancellation

- 수업 취소 시 ClassSession 상태 CANCELED
- 수업 취소 시 모든 CONFIRMED Reservation CANCELED
- 수업 취소 시 각 MemberPass 복구
- 하나의 MemberPass 복구 실패 시 전체 수업 취소 롤백
- 수업 취소와 예약 생성 동시 요청 시 불변조건 유지

## Pass

- MemberPass consume 시 remainingCount 감소
- remainingCount 0 도달 시 EXHAUSTED
- EXHAUSTED 상태에서 restore 후 ACTIVE 복구 가능
- remainingCount는 음수가 될 수 없음
- 사용/복구 이력은 상태 변경과 함께 기록

---

# 14. Resolved Decisions

## RD-01. 회원 중복 기준

```plain text
phone unique로 확정
```

## RD-02. 예약 취소 멱등성

```plain text
이미 CANCELED인 예약 취소 요청은 명시적 실패로 처리
```

## RD-03. OutboxEvent MVP 포함 여부

```plain text
OutboxEvent 포함
```

## RD-04. STAFF 권한 범위

```plain text
STAFF는 운영 기능 가능
관리자 계정 생성/권한 변경만 제한
```

## RD-05. 만료된 MemberPass 복구 정책

```plain text
복구 허용
상태는 EXPIRED 유지
```

## RD-06. AdminUser 관리 API 포함

```plain text
AdminUser 등록과 Role 변경은 MVP에 포함
MANAGER 전용 기능으로 제공
```
