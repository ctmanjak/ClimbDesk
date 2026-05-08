# ClimbDesk Domain Model v0.2

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `03 - Domain Model`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: ClimbDesk MVP의 도메인 경계를 명확히 정의하고, AI-Driven Development에서 엔티티/서비스가 무분별하게 섞이지 않도록 기준을 제공한다.

---

# 1. Scope Trimming Check

## MVP에서 유지할 것

- 관리자 인증
- 관리자 Role 기반 권한
- 관리자 계정 생성
- 관리자 Role 변경
- 회원 등록/조회/비활성화
- 횟수권 상품 생성
- 회원 횟수권 발급
- 수업 생성/조회/취소
- 수업 취소 시 기존 예약 자동 취소
- 수업 예약
- 예약 취소
- 횟수권 차감/복구
- 사용/복구 이력 기록
- 중복 예약 방지
- 정원 초과 방지
- 예약 도메인 이벤트 발행
- 수업 취소 도메인 이벤트 발행

## MVP에서 제외할 것

- 기간권/일일권
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

## 확장 포인트로만 둘 것

- SQS/Kafka 외부 메시징
- Transactional Outbox Pattern
- Notification Context
- 기간권/일일권 정책
- 출석 체크
- 멀티 지점 운영

---

# 2. Bounded Context 후보

## MVP 기준 Context

```plain text
Auth Context
Member Context
Pass Context
Class Context
Reservation Context
Event Context (Infrastructure/Future)
```

| Context | 책임 | MVP 구현 수준 |
| --- | --- | --- |
| Auth | 관리자 인증/권한 | 최소 구현 |
| Member | 회원 상태 관리 | 구현 |
| Pass | 횟수권 상품/회원 보유 이용권/사용 이력 | 구현 |
| Class | 수업 생성/취소/정원 관리 | 구현 |
| Reservation | 예약 생성/취소, 예약 정합성 orchestration | 핵심 구현 |
| Event | 도메인 이벤트 기록/발행, 향후 메시징 확장 | 포트/어댑터 수준 |

### 명칭 원칙

- 인증/인가 관련 Context 명칭은 `Auth`로 통일한다.
- `Identity` 명칭은 사용하지 않는다.

---

# 3. Aggregate 설계 원칙

- Aggregate는 강한 정합성이 필요한 단위로만 묶는다.
- 모든 관계를 객체 참조로 연결하지 않는다.
- 다른 Aggregate는 ID로 참조한다.
- 트랜잭션 경계는 Application Service에서 조율한다.
- Aggregate 내부 상태 변경은 명시적인 메서드로 수행한다.
- public setter 기반 변경은 피한다.
- Aggregate 간 직접 참조나 직접 메서드 호출은 피한다.
- 즉시 정합성이 필요한 유스케이스에서는 Application Service가 여러 Aggregate를 조회하고 조율할 수 있다.
- 다른 Aggregate 또는 다른 Bounded Context의 후속 처리는 Domain Event를 통해 전달한다.
- 이벤트 소실 방지가 필요한 경우 Domain Event를 OutboxEvent로 같은 트랜잭션에 기록한다.

---

# 4. Aggregate Root 정의

## 4.1 AdminUser Aggregate

### Bounded Context

`Auth Context`

### Aggregate Root

`AdminUser`

### 책임

- 관리자 계정 식별
- 관리자 인증 대상 관리
- 관리자 Role 관리
- 관리자 활성/비활성 상태 관리

### 주요 상태

```plain text
ACTIVE
INACTIVE
```

### 주요 Role

```plain text
MANAGER
STAFF
```

### 주요 규칙

- 비활성 관리자 계정은 로그인할 수 없다.
- `MANAGER`는 관리자 생성/권한 변경이 가능하다.
- `STAFF`는 운영 기능 중 제한된 기능만 수행한다.
- 비밀번호 원문은 도메인 모델에 저장하지 않고 `passwordHash`만 저장한다.
- JWT 발급/검증은 Infrastructure 책임이며, AdminUser Aggregate는 JWT에 직접 의존하지 않는다.

### 주요 메서드 후보

```plain text
activate()
deactivate()
changeRole(role)
changePasswordHash(passwordHash)
```

## 4.2 Member Aggregate

### Aggregate Root

`Member`

### 책임

- 회원 기본 정보 관리
- 회원 활성/비활성 상태 관리

### 주요 상태

```plain text
ACTIVE
INACTIVE
```

### 주요 규칙

- 비활성 회원은 예약할 수 없다.
- 회원은 물리 삭제하지 않고 비활성화한다.

### 참조 방식

- Reservation, MemberPass에서 `memberId`로 참조한다.

### 주요 메서드 후보

```plain text
activate()
deactivate()
```

## 4.3 PassProduct Aggregate

### Aggregate Root

`PassProduct`

### 책임

- 판매용 이용권 상품 정의

### MVP 상품 유형

```plain text
COUNT_PASS
```

### 주요 규칙

- MVP에서는 횟수권 상품만 지원한다.
- 상품 정보 변경이 이미 발급된 MemberPass에 영향을 주지 않도록 한다.
- MemberPass 발급 시 필요한 상품 정보를 스냅샷으로 복사한다.

## 4.4 MemberPass Aggregate

### Aggregate Root

`MemberPass`

### 내부 Entity

`PassUsageHistory`

### 책임

- 회원이 실제로 보유한 이용권 관리
- 잔여 횟수 차감
- 예약 취소 시 잔여 횟수 복구
- 수업 취소에 따른 잔여 횟수 복구
- 사용/복구 이력 기록

### 주요 상태

```plain text
ACTIVE
EXHAUSTED
EXPIRED
CANCELED
```

### 주요 규칙

- 잔여 횟수는 0 미만이 될 수 없다.
- 만료된 이용권은 새 예약에 사용할 수 없다.
- 취소된 이용권은 사용할 수 없다.
- 차감과 사용 이력 기록은 함께 처리되어야 한다.
- 복구와 복구 이력 기록은 함께 처리되어야 한다.
- `remainingCount`가 0이 되면 `EXHAUSTED` 상태가 된다.
- `EXHAUSTED` 상태에서 복구되어 `remainingCount > 0`이 되면, 만료되지 않은 경우 `ACTIVE` 상태로 돌아갈 수 있다.
- `EXPIRED` 상태에서도 과거 예약 취소에 따른 회계적 복구는 허용할 수 있으나, 상태는 `EXPIRED`로 유지한다.
- `CANCELED` 상태에서는 차감/복구를 허용하지 않는다.

### 동시성 정책

- MemberPass는 낙관적 락을 사용한다.
- `version` 필드를 둔다.
- `consume()` 또는 `restore()` 저장 시 version 충돌이 발생하면 전체 트랜잭션을 롤백한다.
- 예약 생성 중 MemberPass version 충돌이 발생하면 예약 생성은 실패 처리한다.
- MVP 기본 정책은 자동 재시도 없이 실패 반환이다.

### 사용 가능한 이용권 선택 정책

```plain text
1. 만료일이 가장 빠른 이용권 우선
2. 만료일이 같으면 발급일이 가장 빠른 이용권 우선
3. 그래도 같으면 memberPassId 오름차순
```

### 주요 메서드 후보

```plain text
consume(reservationId)
restore(reservationId, reason)
expire()
cancel()
```

### PassUsageHistory 기록 유형

```plain text
CONSUME
RESTORE
```

### PassUsageHistory 사유

```plain text
RESERVATION_CONFIRMED
RESERVATION_CANCELED
CLASS_SESSION_CANCELED
```

## 4.5 ClassSession Aggregate

### Aggregate Root

`ClassSession`

### 책임

- 수업 일정과 정원 관리
- 예약 가능 상태 관리
- 정원 증가/감소 처리
- 수업 취소 상태 관리

### 주요 상태

```plain text
OPEN
CLOSED
CANCELED
```

### 주요 규칙

- `OPEN` 상태의 수업만 예약할 수 있다.
- `CLOSED` 상태의 수업은 예약할 수 없다.
- `CANCELED` 상태의 수업은 예약할 수 없다.
- 예약 인원은 정원을 초과할 수 없다.
- 예약 생성 시 `reservedCount`를 증가시킨다.
- 예약 취소 시 `reservedCount`를 감소시킨다.
- 수업 취소 시 기존 `CONFIRMED` 예약은 자동 취소한다.
- 수업 취소 시 각 예약에 사용된 MemberPass의 사용 횟수를 복구한다.
- 수업 취소로 복구된 내역은 PassUsageHistory에 `CLASS_SESSION_CANCELED` 사유로 기록한다.
- 수업 취소 완료 후 `ClassSessionCanceledEvent`를 발행한다.

### 주요 메서드 후보

```plain text
reserveSeat()
cancelSeat()
cancel()
close()
```

### 동시성 정책

- 예약 생성 시 ClassSession을 비관적 락으로 조회한다.
- 같은 수업에 대한 예약 요청은 순차 처리한다.
- 수업 취소 시에도 ClassSession을 비관적 락으로 조회한다.
- 수업 취소와 수업 예약이 동시에 들어오면 ClassSession 락 순서에 의해 둘 중 하나가 먼저 완료된다.

## 4.6 Reservation Aggregate

### Aggregate Root

`Reservation`

### 책임

- 수업 예약 상태 관리
- 사용자 요청에 의한 예약 취소 상태 관리
- 수업 취소에 의한 예약 자동 취소 상태 관리
- 예약 도메인 이벤트의 기준 객체 역할

### 주요 상태

```plain text
CONFIRMED
CANCELED
```

### 취소 사유

```plain text
USER_REQUESTED
CLASS_SESSION_CANCELED
```

### 주요 규칙

- 이미 취소된 예약은 다시 취소할 수 없다.
- 같은 회원은 같은 수업에 동시에 하나의 `CONFIRMED` 예약만 가질 수 있다.
- 취소된 예약이 있는 회원은 같은 수업에 다시 예약할 수 있다.
- 중복 예약 방지는 Application 검증 + DB unique constraint로 보장한다.

### DB 제약 전략

DB가 partial unique index를 지원하는 경우:

```plain text
unique(member_id, class_session_id) where status = 'CONFIRMED'
```

DB가 partial unique index를 지원하지 않는 경우:

```plain text
active_reservation_key 컬럼을 둔다.
CONFIRMED 상태일 때만 active_reservation_key = member_id + ':' + class_session_id
CANCELED 상태일 때는 active_reservation_key = null
unique(active_reservation_key)
```

### 주요 메서드 후보

```plain text
confirm()
cancel(reason)
```

### 참조 방식

```plain text
memberId
classSessionId
memberPassId
```

---

# 5. Application Service 경계

## ReservationApplicationService

예약 생성/취소 유스케이스의 트랜잭션 경계를 가진다.

### reserveClass 흐름

```plain text
reserveClass(command)
├─ Member 조회 및 ACTIVE 검증
├─ ClassSession 비관적 락 조회
├─ ClassSession 예약 가능 상태 검증
├─ CONFIRMED 중복 Reservation 검증
├─ 사용 가능한 MemberPass 선택
│  └─ 만료일 ASC, 발급일 ASC, memberPassId ASC
├─ ClassSession.reserveSeat()
├─ Reservation 생성
├─ MemberPass.consume(reservationId)
├─ PassUsageHistory 기록
├─ Reservation 저장
├─ MemberPass 저장
├─ ClassSession 저장
└─ ReservationConfirmedEvent 기록/발행 요청
```

### cancelReservation 흐름

```plain text
cancelReservation(command)
├─ Reservation 조회
├─ Reservation 상태 검증
├─ ClassSession 비관적 락 조회
├─ Reservation.cancel(USER_REQUESTED)
├─ ClassSession.cancelSeat()
├─ MemberPass 조회
├─ MemberPass.restore(reservationId, RESERVATION_CANCELED)
├─ PassUsageHistory 기록
├─ Reservation 저장
├─ MemberPass 저장
├─ ClassSession 저장
└─ ReservationCanceledEvent 기록/발행 요청
```

## ClassSessionApplicationService

### cancelClassSession 흐름

```plain text
cancelClassSession(command)
├─ ClassSession 비관적 락 조회
├─ ClassSession.cancel()
├─ 해당 수업의 CONFIRMED Reservation 목록 조회
├─ 각 Reservation에 대해 반복
│  ├─ Reservation.cancel(CLASS_SESSION_CANCELED)
│  ├─ ClassSession.cancelSeat()
│  ├─ MemberPass 조회
│  ├─ MemberPass.restore(reservationId, CLASS_SESSION_CANCELED)
│  └─ PassUsageHistory 기록
├─ ClassSession 저장
├─ Reservation 목록 저장
├─ MemberPass 목록 저장
└─ ClassSessionCanceledEvent 기록/발행 요청
```

## 트랜잭션 원칙

- 예약 생성, 좌석 증가, 이용권 차감, 이력 기록은 하나의 트랜잭션으로 처리한다.
- 수업 취소, 예약 자동 취소, 좌석 감소, 이용권 복구, 복구 이력 기록은 하나의 트랜잭션으로 처리한다.
- 이벤트는 도메인 이벤트 객체로 표현하되, 이벤트 소실 방지가 필요한 경우 OutboxEvent를 같은 트랜잭션에 저장한다.

---

# 6. Domain Events

## MVP Domain Events

```plain text
ReservationConfirmedEvent
ReservationCanceledEvent
ClassSessionCanceledEvent
```

## 이벤트 설계 원칙

- 이벤트는 순수 Kotlin 객체로 정의한다.
- 도메인 모델은 Spring ApplicationEvent에 의존하지 않는다.
- Application Service는 `DomainEventPublisher` 또는 `DomainEventRecorder` 포트를 통해 이벤트를 다룬다.
- Spring ApplicationEvent는 Infrastructure Adapter에서만 사용한다.
- 외부 메시징으로 전환 가능한 구조를 유지한다.

## 이벤트 발행 정책

```plain text
Domain Event
- 도메인에서 발생한 사실을 표현하는 순수 객체

OutboxEvent
- 이벤트 소실 방지를 위해 DB에 저장하는 발행 대기 레코드

External Message
- SQS/Kafka 등 외부 브로커로 실제 발행되는 메시지
```

### 핵심 판단

커밋 이후 단순 발행은 이벤트 소실 가능성이 있고, 외부 브로커 발행을 DB 트랜잭션 내부에서 직접 수행하면 롤백과 원자성을 맞추기 어렵다.

```plain text
Business Transaction
├─ 도메인 상태 변경 저장
├─ PassUsageHistory 저장
└─ OutboxEvent 저장

Commit 이후
└─ Outbox Publisher가 OutboxEvent를 조회해 외부 브로커로 발행
```

### MVP 결정

```plain text
Option A - Recommended
최소 OutboxEvent 테이블을 구현한다.
도메인 상태 변경과 OutboxEvent 저장을 같은 트랜잭션으로 묶는다.
실제 외부 메시징은 구현하지 않더라도, 발행 대기 이벤트가 DB에 남도록 한다.

Option B - Simple
DomainEventPublisher 포트 호출만 구현한다.
이 경우 이벤트 소실 가능성을 허용하는 단순 MVP로 본다.
```

---

# 7. Value Object 후보

## Email

- 빈 값 불가
- 이메일 형식 검증
- 소문자 정규화 여부는 정책으로 결정

## PasswordHash

- 원문 비밀번호 저장 금지
- 해시 알고리즘은 Infrastructure 정책으로 둔다.

## Role

```plain text
MANAGER
STAFF
```

## Money

MVP에서는 실제 결제 연동이 없으므로 필수는 아니다. PassProduct 가격을 표현할 경우 사용 가능.

## PassCount

- 0 미만 불가
- consume 시 1 감소
- restore 시 1 증가

## ClassCapacity

- capacity는 1 이상
- reservedCount는 0 이상
- reservedCount는 capacity를 초과할 수 없음

---

# 8. Repository 경계

## Repository 후보

```plain text
AdminUserRepository
MemberRepository
PassProductRepository
MemberPassRepository
ClassSessionRepository
ReservationRepository
OutboxEventRepository
```

## 특수 조회

```plain text
AdminUserRepository.findByEmail(email)
AdminUserRepository.countActiveManagers()
ClassSessionRepository.findByIdForUpdate(classSessionId)
ReservationRepository.existsConfirmedByMemberIdAndClassSessionId(memberId, classSessionId)
ReservationRepository.findConfirmedByClassSessionId(classSessionId)
MemberPassRepository.findAvailablePassForUse(memberId)
OutboxEventRepository.findPendingEvents(limit)
```

## MemberPass 조회 정렬

```plain text
findAvailablePassForUse(memberId)
order by expiresAt asc, issuedAt asc, memberPassId asc
```

---

# 9. 주요 불변조건

```plain text
ClassSession.reservedCount <= ClassSession.capacity
MemberPass.remainingCount >= 0
Member.status == ACTIVE 인 회원만 예약 가능
ClassSession.status == OPEN 인 수업만 예약 가능
Reservation은 CONFIRMED -> CANCELED 로만 변경 가능
같은 회원은 같은 수업에 CONFIRMED 예약을 2개 이상 가질 수 없음
예약 생성 시 좌석 증가와 이용권 차감은 같은 트랜잭션에 포함되어야 함
예약 취소 시 좌석 감소와 이용권 복구는 같은 트랜잭션에 포함되어야 함
수업 취소 시 기존 예약 자동 취소와 이용권 복구는 같은 트랜잭션에 포함되어야 함
MemberPass 차감/복구 이력은 MemberPass 상태 변경과 함께 기록되어야 함
```

---

# 10. 상태 전이표

## AdminUser 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| ACTIVE | deactivate() | INACTIVE | 허용 | 로그인 불가 상태로 전환 |
| INACTIVE | activate() | ACTIVE | 허용 | 관리자 재활성화 |
| ACTIVE | activate() | ACTIVE | 멱등 허용 가능 | 구현 정책으로 결정 |
| INACTIVE | deactivate() | INACTIVE | 멱등 허용 가능 | 구현 정책으로 결정 |

## Member 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| ACTIVE | deactivate() | INACTIVE | 허용 | 이후 신규 예약 불가 |
| INACTIVE | activate() | ACTIVE | 허용 | 신규 예약 가능 |
| ACTIVE | activate() | ACTIVE | 멱등 허용 가능 | 구현 정책으로 결정 |
| INACTIVE | deactivate() | INACTIVE | 멱등 허용 가능 | 구현 정책으로 결정 |

## MemberPass 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| ACTIVE | consume(), remainingCount > 0 | ACTIVE | 허용 | 사용 이력 기록 |
| ACTIVE | consume(), remainingCount == 0 | EXHAUSTED | 허용 | 사용 이력 기록 |
| EXHAUSTED | restore(), remainingCount > 0, not expired | ACTIVE | 허용 | 복구 이력 기록 |
| EXHAUSTED | expire() | EXPIRED | 허용 | 더 이상 신규 사용 불가 |
| ACTIVE | expire() | EXPIRED | 허용 | 더 이상 신규 사용 불가 |
| EXPIRED | restore() | EXPIRED | 제한 허용 | 회계적 복구만 허용, 신규 사용 불가 |
| ACTIVE | cancel() | CANCELED | 허용 | 이후 차감/복구 불가 |
| EXHAUSTED | cancel() | CANCELED | 허용 | 이후 차감/복구 불가 |
| CANCELED | consume() | CANCELED | 불가 | 예외 |
| CANCELED | restore() | CANCELED | 불가 | 예외 |

## ClassSession 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| OPEN | reserveSeat() | OPEN | 허용 | reservedCount 증가 |
| OPEN | cancelSeat() | OPEN | 허용 | reservedCount 감소 |
| OPEN | close() | CLOSED | 허용 | 신규 예약 불가 |
| CLOSED | open() | OPEN | MVP 제외 | 재오픈 정책은 MVP 제외 |
| OPEN | cancel() | CANCELED | 허용 | 기존 예약 자동 취소 |
| CLOSED | cancel() | CANCELED | 허용 | 기존 예약이 있으면 자동 취소 |
| CANCELED | reserveSeat() | CANCELED | 불가 | 예외 |
| CANCELED | close() | CANCELED | 불가 | 예외 |
| CANCELED | cancel() | CANCELED | 멱등 허용 가능 | 구현 정책으로 결정 |

## Reservation 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| CONFIRMED | cancel(USER_REQUESTED) | CANCELED | 허용 | 사용자 요청 취소 |
| CONFIRMED | cancel(CLASS_SESSION_CANCELED) | CANCELED | 허용 | 수업 취소에 따른 자동 취소 |
| CANCELED | cancel(any) | CANCELED | 불가 | 중복 취소 예외 또는 멱등 처리 중 택일 |
| CANCELED | confirm() | CONFIRMED | 불가 | 취소된 예약 재확정 불가 |

## OutboxEvent 상태 전이

| 현재 상태 | 이벤트/메서드 | 다음 상태 | 허용 여부 | 비고 |
| --- | --- | --- | --- | --- |
| PENDING | markPublished() | PUBLISHED | 허용 | 외부 발행 성공 |
| PENDING | markFailed() | FAILED | 허용 | 재시도 가능 |
| FAILED | markPendingForRetry() | PENDING | 허용 | 재시도 대상 |
| PUBLISHED | markPendingForRetry() | PUBLISHED | 불가 | 중복 발행 방지 |

---

# 11. Aggregate 경계 결정 요약

| Aggregate | Root | 내부 구성 | 강한 정합성 |
| --- | --- | --- | --- |
| AdminUser | AdminUser | - | 관리자 상태/권한 |
| Member | Member | - | 회원 상태 |
| PassProduct | PassProduct | - | 상품 정의 |
| MemberPass | MemberPass | PassUsageHistory | 차감/복구/이력 |
| ClassSession | ClassSession | - | 정원 관리/수업 취소 |
| Reservation | Reservation | - | 예약 상태 |
| OutboxEvent | OutboxEvent | - | 이벤트 발행 상태 |

---

# 12. 설계 판단

## Auth Context로 명칭을 통일한 이유

MVP에서 인증/인가 기능은 관리자 로그인과 Role 기반 권한에 집중한다. 문서와 코드의 혼선을 줄이기 위해 `Identity` 대신 `Auth` 명칭으로 통일한다.

## AdminUser를 모델에 추가한 이유

PRD에서 관리자 로그인, JWT 인증, Role 기반 권한이 MVP 범위에 포함되어 있다. 따라서 최소한의 관리자 계정 모델이 필요하다. 단, JWT 발급/검증 자체는 도메인 책임이 아니라 Infrastructure 책임으로 둔다.

## Reservation이 모든 것을 소유하지 않는 이유

Reservation이 MemberPass, ClassSession을 직접 소유하면 Aggregate가 과도하게 커진다. 각 Aggregate는 독립적인 생명주기와 규칙을 가지므로 Reservation은 ID로 참조하고, Application Service에서 유스케이스 단위로 조율한다.

## PassUsageHistory를 MemberPass 내부 Entity로 둔 이유

사용 이력은 MemberPass의 상태 변경 결과이며 독립적으로 변경될 이유가 적다. 따라서 별도 Aggregate로 분리하지 않고 MemberPass 내부 이력으로 관리한다.

## ClassSession을 별도 Aggregate로 둔 이유

수업 정원은 예약 생성 시 강한 정합성이 필요한 핵심 규칙이다. ClassSession은 reservedCount와 capacity를 관리하는 Aggregate Root로 둔다.

## MemberPass에 낙관적 락을 사용하는 이유

동일 회원이 동시에 여러 예약을 시도하면 같은 MemberPass의 remainingCount가 경쟁 조건에 놓일 수 있다. ClassSession은 수업 정원 보호를 위해 비관적 락을 사용하지만, MemberPass는 충돌 빈도가 상대적으로 낮고 특정 회원 단위 경쟁이므로 낙관적 락을 사용한다.

## 수업 취소 시 예약을 자동 취소하는 이유

수업이 취소되면 해당 수업의 기존 예약은 더 이상 유효하지 않다. 예약을 그대로 두면 예약 상태와 수업 상태가 불일치하므로, 수업 취소 트랜잭션 안에서 기존 CONFIRMED 예약을 자동 취소하고 사용 횟수를 복구한다.

## Transactional Outbox를 고려하는 이유

커밋 이후 단순 발행은 이벤트 소실 가능성이 있고, DB 트랜잭션 내부의 외부 브로커 직접 발행은 롤백과 원자성을 맞추기 어렵다. 도메인 상태 변경과 OutboxEvent 저장을 같은 트랜잭션에 포함하면 이벤트 발행 대상 자체는 소실되지 않는다.

---

# 13. 기타

## Last Active Manager Protection

AdminUser Role 변경 또는 비활성화는 시스템 전체의 ACTIVE MANAGER 수에 영향을 줄 수 있다.
이 규칙은 단일 AdminUser Aggregate 내부에서 판단할 수 없는 cross-aggregate invariant이다.
따라서 Application Service는 Role 변경 또는 비활성화 전에 다음을 검증한다.

```plain text
대상 AdminUser가 ACTIVE MANAGER이고,
요청 결과 대상이 더 이상 ACTIVE MANAGER가 아니게 되며,
현재 ACTIVE MANAGER 수가 1명이라면 실패한다.
실패 시 LAST_ACTIVE_MANAGER_REQUIRED로 처리한다.
```
