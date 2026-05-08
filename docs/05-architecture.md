# ClimbDesk Architecture v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `05 - Architecture`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> 목적: ClimbDesk MVP의 애플리케이션 구조, 레이어 책임, 패키지 경계, 의존성 규칙, 트랜잭션/동시성/이벤트 처리 방식을 정의한다.

---

# 1. 문서 상태

## Status

```plain text
Draft
```

## Source Documents

- `01 - PRD`: MVP 범위, 성공 기준, 핵심 비즈니스 규칙
- `02 - Functional Spec`: 기능 행위, 권한 정책, 트랜잭션 규칙
- `03 - Domain Model`: Aggregate, Repository 경계, 이벤트 정책
- `04 - API Spec`: REST API 계약, DTO, error code

## 작성 원칙

- 도메인 규칙과 프레임워크 세부 구현을 분리한다.
- 예약 정합성, 동시성 제어, 트랜잭션 경계를 명확히 드러낸다.
- MVP 구현 가능성을 우선하되, 향후 Outbox Publisher와 외부 메시징으로 확장 가능한 구조를 유지한다.
- Database schema, index, lock query 상세는 `06 - Database Design`에서 별도 정의한다.

---

# 2. Architecture Style

## Primary Style

```plain text
Layered Architecture + Hexagonal Architecture principles
```

ClimbDesk는 Spring 기반 Layered Architecture를 기본으로 하되, 도메인과 외부 기술 의존성을 분리하기 위해 Hexagonal Architecture의 Port/Adapter 관점을 적용한다.

## 핵심 방향

```plain text
Controller
→ Application Service
→ Domain Model
→ Repository Port
→ Persistence Adapter
```

## 적용 이유

- API 변경이 Domain Model에 직접 영향을 주지 않도록 한다.
- JPA, JWT, Outbox 저장소 등 Infrastructure 구현이 Domain에 침투하지 않도록 한다.
- 예약 생성/취소처럼 여러 Aggregate를 조율하는 유스케이스의 트랜잭션 경계를 Application Service에 둔다.
- 포트폴리오에서 도메인 모델링, 트랜잭션, 동시성 제어 역량을 명확히 보여준다.

---

# 3. Layer Responsibilities

## 3.1 Presentation Layer

### 책임

- HTTP request 수신
- request DTO validation
- 인증된 사용자 정보 추출
- Application Command 생성
- Application Service 호출
- response DTO 반환
- exception을 공통 error response로 변환

### 포함 요소

```plain text
Controller
Request DTO
Response DTO
Exception Handler
Authentication Principal Adapter
```

### 금지 사항

- 도메인 상태 직접 변경 금지
- Repository 직접 호출 금지
- 트랜잭션 orchestration 금지
- JPA Entity 직접 반환 금지

## 3.2 Application Layer

### 책임

- 유스케이스 orchestration
- 트랜잭션 경계 관리
- 권한 정책 적용
- 여러 Aggregate 조회 및 조율
- Repository Port 사용
- Domain Event 기록 요청
- DTO가 아닌 Command/Result 중심 처리

### 대표 서비스

```plain text
AuthApplicationService
AdminUserApplicationService
MemberApplicationService
PassApplicationService
ClassSessionApplicationService
ReservationApplicationService
```

### 금지 사항

- HTTP request/response 객체 의존 금지
- JPA 구현체 직접 의존 금지
- 외부 메시징 구현체 직접 의존 금지

## 3.3 Domain Layer

### 책임

- Aggregate 상태와 불변조건 보장
- Entity, Value Object 정의
- 도메인 행위 메서드 제공
- Domain Event 표현

### 주요 Aggregate

```plain text
AdminUser
Member
PassProduct
MemberPass
ClassSession
Reservation
```

### 금지 사항

- Spring annotation 의존 최소화
- Controller DTO 의존 금지
- JPA Repository 의존 금지
- JWT, HTTP, messaging client 직접 의존 금지

## 3.4 Infrastructure Layer

### 책임

- Persistence 구현
- JWT 발급/검증 구현
- OutboxEvent 저장/조회 구현
- 외부 메시징 확장 어댑터 구현
- Spring Security 설정
- Transaction manager, clock 등 기술 구성

### 금지 사항

- 비즈니스 규칙을 Infrastructure에 숨기지 않는다.
- Aggregate 행위를 우회해 상태를 변경하지 않는다.

---

# 4. Dependency Rule

## 허용 방향

```plain text
Presentation → Application → Domain
Infrastructure → Application / Domain Port
```

## 의존성 원칙

- Domain은 어떤 상위 레이어에도 의존하지 않는다.
- Application은 Domain에 의존하되, Infrastructure 구현체에는 의존하지 않는다.
- Infrastructure는 Domain/Application의 Port를 구현한다.
- Presentation은 Application Service를 호출한다.

## 금지 예시

```plain text
Domain → Controller DTO
Domain → Spring Security
Application → JpaRepository 구현체
Controller → Domain Aggregate 직접 변경
Controller → Repository 직접 호출
```

---

# 5. Bounded Context and Package Structure

## 5.1 Context 기준 패키지

```plain text
com.climbdesk
├─ auth
├─ member
├─ pass
├─ classsession
├─ reservation
├─ event
└─ common
```

## 5.2 Context 내부 구조

```plain text
{context}
├─ presentation
│  ├─ controller
│  └─ dto
├─ application
│  ├─ service
│  ├─ command
│  ├─ result
│  └─ port
├─ domain
│  ├─ model
│  ├─ event
│  ├─ exception
│  └─ repository
└─ infrastructure
   ├─ persistence
   └─ adapter
```

## 5.3 Auth Context 예시

```plain text
auth
├─ presentation
│  ├─ AuthController
│  ├─ AdminUserController
│  └─ dto
├─ application
│  ├─ AuthApplicationService
│  ├─ AdminUserApplicationService
│  ├─ LoginCommand
│  ├─ CreateAdminUserCommand
│  ├─ ChangeAdminUserRoleCommand
│  ├─ ActivateAdminUserCommand
│  └─ DeactivateAdminUserCommand
├─ domain
│  ├─ AdminUser
│  ├─ Role
│  ├─ AdminUserStatus
│  └─ AdminUserRepository
└─ infrastructure
   ├─ JwtTokenProvider
   ├─ SecurityConfig
   └─ AdminUserPersistenceAdapter
```

## 5.4 Reservation Context 예시

```plain text
reservation
├─ presentation
│  ├─ ReservationController
│  └─ dto
├─ application
│  ├─ ReservationApplicationService
│  ├─ CreateReservationCommand
│  └─ CancelReservationCommand
├─ domain
│  ├─ Reservation
│  ├─ ReservationStatus
│  ├─ ReservationCancelReason
│  ├─ ReservationConfirmedEvent
│  ├─ ReservationCanceledEvent
│  └─ ReservationRepository
└─ infrastructure
   └─ ReservationPersistenceAdapter
```

---

# 6. Context Responsibilities

## Auth Context

- AdminUser 로그인
- JWT 기반 인증
- Role 기반 인가
- AdminUser 등록
- AdminUser Role 변경
- AdminUser password hash 관리
- 비활성 AdminUser 로그인 차단
- AdminUser 활성화
- AdminUser 비활성화

### Application Services

```plain text
AuthApplicationService.login(command)
AdminUserApplicationService.createAdminUser(command)
AdminUserApplicationService.changeRole(command)
AdminUserApplicationService.activate(command)
AdminUserApplicationService.deactivate(command)
```

### 권한 정책

- AdminUser 등록, Role 변경, 활성화, 비활성화는 MANAGER 전용이다.
- STAFF는 회원, 이용권, 수업, 예약 운영 기능을 수행할 수 있다.
- STAFF는 AdminUser 관리 기능을 수행할 수 없다.

## Member Context

- 회원 등록
- 회원 조회
- 회원 비활성화
- 회원 상태 검증 제공

## Pass Context

- 횟수권 상품 생성
- 회원 횟수권 발급
- 사용 가능한 이용권 선택
- 이용권 차감/복구
- 사용/복구 이력 기록

## Class Context

- 수업 생성
- 수업 조회
- 수업 취소
- 예약 정원 증가/감소
- 수업 상태 검증

## Reservation Context

- 예약 생성 orchestration
- 예약 취소 orchestration
- 중복 예약 방지
- 예약 이벤트 생성 또는 기록 요청

## Event Context

- Domain Event를 OutboxEvent로 기록
- pending OutboxEvent 조회
- 향후 외부 메시징 발행 확장

---

# 7. Application Flow

## Login Flow

```plain text
AuthController.login(request)
→ LoginCommand 생성
→ AuthApplicationService.login(command)
→ AdminUserRepository.findByEmail(email)
→ AdminUser 상태 검증
→ 인증 정보 검증 Adapter 호출
→ TokenProvider 발급
→ LoginResponse 반환
```

## AdminUser Create Flow

```plain text
AdminUserController.create(request)
→ CreateAdminUserCommand 생성
→ AdminUserApplicationService.createAdminUser(command)
→ 요청자 Role 검증
→ AdminUserRepository.existsByEmail(email)
→ password hash 생성
→ AdminUser 생성
→ AdminUserRepository.save(adminUser)
→ AdminUserResponse 반환
```

## Reservation Create Flow

```plain text
ReservationController.create(request)
→ CreateReservationCommand 생성
→ ReservationApplicationService.reserveClass(command)
→ Member 조회 및 ACTIVE 검증
→ ClassSession 비관적 락 조회
→ ClassSession 예약 가능 상태 검증
→ CONFIRMED 중복 Reservation 검증
→ 사용 가능한 MemberPass 선택
→ ClassSession.reserveSeat()
→ Reservation 생성
→ MemberPass.consume(reservationId)
→ PassUsageHistory 기록
→ ReservationConfirmedEvent OutboxEvent 기록
→ Commit
→ ReservationResponse 반환
```

## Reservation Cancel Flow

```plain text
ReservationController.cancel(request)
→ CancelReservationCommand 생성
→ ReservationApplicationService.cancelReservation(command)
→ Reservation 조회 및 CONFIRMED 검증
→ ClassSession 비관적 락 조회
→ Reservation.cancel(USER_REQUESTED)
→ ClassSession.cancelSeat()
→ MemberPass.restore(reservationId, RESERVATION_CANCELED)
→ PassUsageHistory 기록
→ ReservationCanceledEvent OutboxEvent 기록
→ Commit
→ ReservationResponse 반환
```

## Class Session Cancel Flow

```plain text
ClassSessionController.cancel(request)
→ CancelClassSessionCommand 생성
→ ClassSessionApplicationService.cancelClassSession(command)
→ ClassSession 비관적 락 조회
→ ClassSession.cancel()
→ 해당 수업의 CONFIRMED Reservation 목록 조회
→ 각 Reservation 취소
→ 각 MemberPass 복구
→ 각 PassUsageHistory 기록
→ ClassSessionCanceledEvent OutboxEvent 기록
→ Commit
→ ClassSessionResponse 반환
```

---

# 8. Transaction Boundary

## 원칙

- 트랜잭션은 Application Service method 단위로 선언한다.
- Controller에는 트랜잭션을 두지 않는다.
- Aggregate 내부 메서드는 트랜잭션을 직접 열지 않는다.
- 도메인 상태 변경과 OutboxEvent 저장은 같은 트랜잭션에 포함한다.

## Transactional Use Cases

```plain text
AdminUser 생성
AdminUser Role 변경
AdminUser 활성화
AdminUser 비활성화
Member 생성/비활성화
PassProduct 생성
MemberPass 발급
Reservation 생성
Reservation 취소
ClassSession 생성/취소
```

---

# 9. Concurrency Strategy

## ClassSession

- 예약 생성 시 ClassSession을 비관적 락으로 조회한다.
- 수업 취소 시 ClassSession을 비관적 락으로 조회한다.
- 같은 수업에 대한 예약 생성과 수업 취소는 순차 처리된다.

## MemberPass

- MemberPass는 낙관적 락을 사용한다.
- 차감/복구 저장 시 version 충돌이 발생하면 전체 유스케이스를 실패 처리한다.
- MVP에서는 자동 재시도하지 않는다.

## Duplicate Reservation

- Application pre-check와 DB unique constraint를 함께 사용한다.
- CONFIRMED 예약만 중복 불가하다.
- CANCELED 예약 이력은 재예약을 막지 않는다.

---

# 10. Event and Outbox Architecture

## Event Types

```plain text
ReservationConfirmedEvent
ReservationCanceledEvent
ClassSessionCanceledEvent
```

## Event Recording Flow

```plain text
Application Service
→ Domain Event 생성 또는 수집
→ OutboxEventRecorder port 호출
→ OutboxEvent 저장
→ Business Transaction Commit
```

## Outbox Publisher 확장

MVP에서는 OutboxEvent 저장까지 구현한다. 외부 메시징은 future expansion으로 둔다.

```plain text
OutboxPublisher
→ pending OutboxEvent 조회
→ External Message 변환
→ Broker 발행
→ 발행 완료 처리
```

---

# 11. Security Architecture

## Password Handling

- AdminUser 생성 API는 password를 request-only 필드로 입력받는다.
- password는 응답에 포함하지 않는다.
- password 원문은 저장하지 않고 passwordHash만 저장한다.
- password hash 생성과 검증은 Infrastructure/Security Adapter 책임이다.

## Authentication

- 로그인 성공 시 JWT access token을 발급한다.
- 보호 API는 bearer token을 요구한다.
- Token parsing과 principal 구성은 Infrastructure/Security Adapter 책임이다.

## Authorization

- Controller 또는 Application Service 진입부에서 Role 기반 접근을 검증한다.
- AdminUser 등록, Role 변경, 활성화, 비활성화는 MANAGER만 가능하다.
- STAFF는 운영 기능에 접근할 수 있으나 AdminUser 관리 기능에는 접근할 수 없다.

## Inactive AdminUser

- INACTIVE AdminUser는 로그인할 수 없다.
- INACTIVE AdminUser는 MANAGER에 의해 다시 ACTIVE로 변경될 수 있다.
- 마지막 ACTIVE MANAGER는 INACTIVE로 변경할 수 없다.
- 이미 발급된 token의 만료/무효화 정책은 MVP 이후 확장 정책으로 둔다.

---

# 12. Error Handling Architecture

## Global Exception Handler

Presentation Layer에 공통 예외 처리기를 둔다.

```plain text
DomainException
ApplicationException
ValidationException
AuthenticationException
AuthorizationException
InfrastructureException
```

## Error Mapping

- validation 실패: `400 VALIDATION_FAILED`
- 인증 실패: `401 UNAUTHORIZED` 또는 `401 INVALID_CREDENTIALS`
- 권한 없음: `403 FORBIDDEN`
- 리소스 없음: `404 *_NOT_FOUND`
- 비즈니스 규칙 위반: `409 *_CONFLICT` 또는 명시적 error code
- 서버 내부 오류: `500 INTERNAL_SERVER_ERROR`

---

# 13. DTO and Command Mapping

- Controller Request DTO와 Application Command를 분리한다.
- Controller Response DTO와 Domain Aggregate를 분리한다.
- API 스펙 변경이 Domain Model에 직접 영향을 주지 않도록 한다.

```plain text
CreateReservationRequest
→ CreateReservationCommand
→ ReservationApplicationService.reserveClass(command)
→ ReservationResult
→ ReservationResponse
```

---

# 14. Testing Architecture

## Unit Test

- Aggregate 상태 전이 테스트
- Value Object validation 테스트
- Domain Policy 테스트

## Application Service Test

- 예약 생성 성공/실패 테스트
- 예약 취소 복구 테스트
- 수업 취소 일괄 취소 테스트
- AdminUser 등록/권한/상태 변경 테스트
- 마지막 ACTIVE MANAGER 보호 테스트

## Integration Test

- Repository persistence 테스트
- transaction rollback 테스트
- 동시 예약 테스트
- 중복 예약 constraint 테스트
- OutboxEvent 저장 테스트

## API Test

- Controller request validation 테스트
- 인증/인가 테스트
- error response mapping 테스트

---

# 15. Resolved Decisions

## RD-01. Architecture Style

```plain text
Layered Architecture를 기본으로 하고 Hexagonal Port/Adapter 원칙을 적용한다.
```

## RD-02. Transaction Boundary

```plain text
유스케이스 트랜잭션 경계는 Application Service에 둔다.
```

## RD-03. Domain Dependency

```plain text
Domain은 Spring Security, Controller DTO, JPA 구현체에 의존하지 않는다.
```

## RD-04. Event Reliability

```plain text
MVP는 OutboxEvent 저장을 포함한다.
외부 메시징 발행은 future expansion으로 둔다.
```

## RD-05. AdminUser Management

```plain text
AdminUser 등록과 Role 변경은 Auth Context의 Application Service에서 처리한다.
AdminUser status 변경은 MVP에 포함한다.
INACTIVE AdminUser는 로그인할 수 없다.
마지막 ACTIVE MANAGER는 STAFF로 변경할 수 없다.
마지막 ACTIVE MANAGER는 INACTIVE로 변경할 수 없다.
```

---

# 16. Open Decisions

## OD-01. Multi-module 여부

MVP에서는 single module package 구조를 우선한다. 필요 시 향후 Gradle multi-module로 분리한다.

## OD-02. Token Revocation

INACTIVE AdminUser의 신규 로그인은 차단한다. 이미 발급된 token의 즉시 무효화 정책은 MVP 이후 결정한다.

## OD-03. Outbox Publisher 구현 시점

MVP는 OutboxEvent 저장까지 포함한다. polling publisher와 외부 broker 연동은 확장 단계에서 구현한다.
