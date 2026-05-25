# ClimbDesk Roadmap v0.1

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `09 - Roadmap`.
> This Markdown file is a repository-local snapshot synced on 2026-05-24 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> ClimbDesk MVP를 단계적으로 구현하기 위한 개발 로드맵이다.
> PRD, Domain Model, API Spec, Architecture, Database Design, Test Strategy를 기준으로 구현 순서, 마일스톤, 완료 기준을 정의한다.

---

## 1. Purpose

이 문서는 ClimbDesk MVP를 완성하기 위한 실행 계획을 정의한다.
ClimbDesk는 단순 CRUD 프로젝트가 아니라, 회원권 기반 수업 예약 도메인을 통해 다음 백엔드 역량을 증명하는 포트폴리오 프로젝트다.

- 도메인 모델링
- 트랜잭션 설계
- 동시성 제어
- 테스트 자동화
- API 설계
- 데이터 정합성 보장

Roadmap의 목적은 기능을 나열하는 것이 아니라, **어떤 순서로 구현해야 핵심 도메인 정합성을 안정적으로 증명할 수 있는지**를 관리하는 것이다.

---

## 2. Roadmap Principles

### 2.1 Domain First

구현은 API부터 시작하지 않고, 도메인 규칙과 상태 전이를 먼저 확정한 뒤 진행한다.

특히 다음 규칙은 구현 전반의 기준이 된다.

- ACTIVE 회원만 예약 가능하다.
- OPEN 상태 수업만 예약 가능하다.
- 동일 회원은 동일 수업에 중복 예약할 수 없다.
- 수업 정원은 초과될 수 없다.
- 사용 가능한 횟수권이 있어야 예약할 수 있다.
- 예약 취소 시 좌석과 이용권 횟수는 복구되어야 한다.

### 2.2 Consistency Over Feature Count

MVP에서는 많은 기능보다 핵심 흐름의 정합성을 우선한다.

```plain text
예약 생성
→ 좌석 차감
→ 이용권 차감
→ 사용 이력 기록
```

취소 시에는 다음 흐름이 하나의 트랜잭션으로 보장되어야 한다.

```plain text
예약 취소
→ 좌석 복구
→ 이용권 복구
→ 사용 이력 기록
```

### 2.3 Test-Backed Implementation

핵심 비즈니스 규칙은 테스트로 증명한다.

- 동시 예약 테스트
- 중복 예약 테스트
- 정원 초과 방지 테스트
- 이용권 차감 테스트
- 예약 취소 복구 테스트
- 권한별 접근 제어 테스트

### 2.4 MVP Scope Control

MVP 제외 대상은 다음과 같다.

- 기간권 / 일일권
- 수업 수정
- 대기 예약
- 출석 체크
- 멀티 지점 운영
- 통계 대시보드
- 프론트엔드 앱
- 실제 결제 연동
- 실제 알림 연동

---

## 3. MVP Delivery Strategy

ClimbDesk MVP는 다음 순서로 구현한다.

```plain text
Project Setup
→ Auth & Admin
→ Member
→ Database Baseline
→ Pass
→ Class
→ Reservation
→ Cancellation
→ Concurrency & Test Hardening
→ API Docs & Portfolio Polish
```

이 순서는 예약 도메인의 핵심 트랜잭션을 구현하기 전에 필요한 선행 도메인을 안정적으로 확보하기 위한 것이다.
예약 기능은 Member, Pass, Class가 모두 준비된 이후 구현한다.

---

## 4. Milestone Overview

| Milestone | Name | Goal | Primary Output |
| --- | --- | --- | --- |
| M0 | Project Setup | 개발 기반 구축 | Spring Boot 프로젝트, Neon PostgreSQL 설정, 공통 설정 |
| M1 | Auth & Admin | 관리자 인증/권한 구현 | 로그인, JWT, Role 기반 권한 |
| M2 | Member | 회원 관리 구현 | 회원 등록, 조회, 비활성화 |
| M2.5 | Database Baseline | MVP 스키마 계약 고정 | Flyway baseline migration, JPA validate, migration 기반 테스트 전환 |
| M3 | Pass | 횟수권 관리 구현 | 상품 생성, 이용권 발급, 차감/복구 기반 |
| M4 | Class | 수업 관리 구현 | 수업 생성, 조회, 취소, 정원 관리 |
| M5 | Reservation Core | 예약 생성 핵심 플로우 구현 | 예약 생성, 중복 방지, 이용권 차감 |
| M6 | Reservation Cancellation | 예약 취소 플로우 구현 | 예약 취소, 좌석 복구, 이용권 복구 |
| M7 | Concurrency & Test Hardening | 정합성 검증 강화 | 비관적 락, 동시성 테스트, 통합 테스트 |
| M8 | API Docs & Portfolio Polish | 포트폴리오 완성 | API 문서, README, 시연 시나리오 |

---

## 5. Milestone Details

## M0. Project Setup

### Goal

ClimbDesk 개발을 시작하기 위한 백엔드 프로젝트 기반을 구축한다.

### Scope

- Spring Boot 프로젝트 생성
- Kotlin 기반 패키지 구조 정리
- Gradle 설정
- Neon PostgreSQL 개발/배포 DB 설정
- 환경 변수 기반 datasource 설정
- 환경별 설정 분리
- 공통 예외 응답 구조 정의
- 공통 테스트 환경 구성

### Done Criteria

- 로컬에서 애플리케이션이 정상 실행된다.
- Neon PostgreSQL 연결 설정이 환경 변수 기반으로 구성된다.
- 기본 health check 또는 smoke API가 동작한다.
- 테스트 실행 환경이 구성된다.
- 주요 패키지 구조가 정리되어 있다.
- Database Design 기준의 MVP baseline migration이 적용된다.
- JPA는 schema 생성이 아니라 migration 결과 검증에 사용된다.

---

## M1. Auth & Admin

### Scope

- 관리자 계정 생성
- 관리자 로그인
- JWT 발급
- JWT 인증 필터
- Role 정의
  - MANAGER
  - STAFF
- MANAGER 전용 관리자 생성 API
- MANAGER 전용 관리자 권한 변경 API
- 인증/인가 실패 응답 정의

### Done Criteria

- 관리자가 로그인할 수 있다.
- 로그인 성공 시 JWT가 발급된다.
- 인증이 필요한 API는 JWT 없이 호출할 수 없다.
- MANAGER만 관리자 계정을 생성할 수 있다.
- MANAGER만 관리자 권한을 변경할 수 있다.
- STAFF는 권한 변경 API를 호출할 수 없다.
- 인증/인가 테스트가 통과한다.

---

## M2. Member

### Scope

- 회원 등록
- 회원 단건 조회
- 회원 목록 조회
- 회원 비활성화
- 회원 상태 정의
  - ACTIVE
  - INACTIVE
- 예약 가능 회원 검증 로직 구현

### Done Criteria

- 회원을 등록할 수 있다.
- 회원을 조회할 수 있다.
- 회원을 비활성화할 수 있다.
- INACTIVE 회원은 예약할 수 없다.
- 회원 상태 전이 테스트가 통과한다.

---

## M2.5. Database Baseline

### Goal

M3 Pass 구현 전에 전체 MVP 데이터베이스 계약을 Flyway baseline migration으로 고정한다.

### Scope

- Flyway 도입
- `V1__create_mvp_schema.sql` 작성
- Database Design 기준의 전체 MVP 테이블, 제약조건, 인덱스 반영
- 이후 마일스톤 테이블도 FK 정합성을 위해 baseline에 포함
- Hibernate schema 생성 의존 제거
- JPA mapping 검증을 `validate` 또는 동등한 방식으로 전환
- PostgreSQL Testcontainers 통합 테스트가 migration schema를 사용하도록 전환

### Done Criteria

- Flyway baseline migration이 PostgreSQL Testcontainers에서 성공적으로 적용된다.
- 기존 Auth/Member 통합 테스트가 migration 기반 schema에서 통과한다.
- JPA mapping이 migration schema와 불일치하면 테스트/애플리케이션 시작 단계에서 실패한다.
- M3 Pass 테이블과 FK 대상 테이블이 baseline schema에 포함되어 있다.

### Ticket

- Introduce MVP baseline database migration: https://www.notion.so/36a4c60a7303814ba776ec58edeb5a31

---

## M3. Pass

### Scope

- 횟수권 상품 생성
- 횟수권 상품 조회
- 회원 이용권 발급
- 회원 이용권 조회
- 이용권 잔여 횟수 차감
- 이용권 잔여 횟수 복구
- 이용권 사용 이력 기록
- 이용권 상태 정의
  - ACTIVE
  - EXHAUSTED
  - EXPIRED
  - CANCELED

### Entry Criteria

- Database Design 기준의 MVP baseline migration이 먼저 적용되어 있다.
- baseline migration은 이후 마일스톤 테이블까지 포함해 FK와 constraint 계약을 고정한다.
- 통합 테스트는 Hibernate schema 생성이 아니라 migration이 적용된 PostgreSQL Testcontainers에서 실행한다.

### Done Criteria

- 횟수권 상품을 생성할 수 있다.
- 회원에게 횟수권을 발급할 수 있다.
- 사용 가능한 이용권을 조회할 수 있다.
- 이용권 잔여 횟수를 차감할 수 있다.
- 잔여 횟수가 0 미만으로 내려가지 않는다.
- 예약 취소 시 사용할 수 있도록 이용권 복구 로직이 준비되어 있다.
- 이용권 사용 이력이 기록된다.

---

## M4. Class

### Scope

- 수업 생성
- 수업 단건 조회
- 수업 목록 조회
- 수업 취소
- 수업 상태 정의
  - OPEN
  - CANCELED
  - CLOSED
- 정원 관리
- 예약 가능 수업 검증 로직 구현

### Done Criteria

- 수업을 생성할 수 있다.
- 수업을 조회할 수 있다.
- 수업을 취소할 수 있다.
- CANCELED 수업에는 예약할 수 없다.
- reservedCount는 capacity를 초과할 수 없다.
- 좌석 차감/복구 메서드가 도메인 규칙을 보장한다.

---

## M5. Reservation Core

### Scope

- 예약 생성 API
- ACTIVE 회원 검증
- OPEN 수업 검증
- 중복 예약 사전 검증
- DB unique constraint 기반 중복 예약 방지
- 사용 가능한 이용권 검증
- 좌석 차감
- 이용권 차감
- 이용권 사용 이력 기록
- 예약 이벤트 발행

### Transaction Boundary

```plain text
예약 생성
좌석 차감
이용권 차감
사용 이력 기록
```

### Done Criteria

- ACTIVE 회원은 OPEN 수업을 예약할 수 있다.
- INACTIVE 회원은 예약할 수 없다.
- CANCELED 수업은 예약할 수 없다.
- 동일 회원은 동일 수업에 중복 예약할 수 없다.
- 정원이 초과되면 예약이 실패한다.
- 사용 가능한 이용권이 없으면 예약이 실패한다.
- 예약 성공 시 reservedCount가 증가한다.
- 예약 성공 시 이용권 잔여 횟수가 감소한다.
- 예약 성공 시 사용 이력이 기록된다.
- 예약 이벤트가 발행된다.

---

## M6. Reservation Cancellation

### Scope

- 예약 취소 API
- 예약 상태 정의
  - RESERVED
  - CANCELED
- 예약 취소 가능 여부 검증
- 좌석 복구
- 이용권 복구
- 이용권 사용 이력 기록
- 예약 취소 이벤트 발행

### Transaction Boundary

```plain text
예약 취소
좌석 복구
이용권 복구
사용 이력 기록
```

### Done Criteria

- RESERVED 예약을 취소할 수 있다.
- 이미 CANCELED 상태인 예약은 다시 취소할 수 없다.
- 예약 취소 시 reservedCount가 감소한다.
- 예약 취소 시 이용권 잔여 횟수가 복구된다.
- 예약 취소 이력이 기록된다.
- 예약 취소 이벤트가 발행된다.
- 취소 플로우 전체가 하나의 트랜잭션으로 처리된다.

---

## M7. Concurrency & Test Hardening

### Scope

- 비관적 락 기반 수업 조회 구현
- 동시 예약 테스트
- 정원 초과 방지 테스트
- 중복 예약 경쟁 조건 테스트
- 이용권 동시 차감 테스트
- 예약 생성 통합 테스트
- 예약 취소 통합 테스트
- 실패 케이스 예외 응답 테스트

### Core Invariant

```plain text
reservedCount <= capacity
```

### Done Criteria

- 동일 수업에 여러 예약 요청이 동시에 들어와도 정원이 초과되지 않는다.
- 동일 회원이 동일 수업에 동시에 예약 요청을 보내도 중복 예약이 생성되지 않는다.
- 이용권 잔여 횟수가 동시 요청으로 음수가 되지 않는다.
- 핵심 유스케이스 통합 테스트가 통과한다.
- 실패 케이스의 응답 코드와 에러 메시지가 일관된다.

---

## M8. API Docs & Portfolio Polish

### Scope

- API 문서 정리
- README 작성
- 실행 방법 정리
- Neon PostgreSQL 연결 설정 가이드 작성
- 주요 시나리오 정리
- 테스트 실행 방법 정리
- 아키텍처 설명 정리
- 동시성 제어 설명 정리
- 트랜잭션 설계 설명 정리

### Done Criteria

- README만 보고 로컬 실행이 가능하다.
- API 문서를 통해 주요 기능을 호출할 수 있다.
- 핵심 시나리오가 문서화되어 있다.
- 동시성 제어 전략이 설명되어 있다.
- 트랜잭션 경계가 설명되어 있다.
- 테스트 실행 결과로 핵심 정합성을 증명할 수 있다.

---

## 6. MVP Completion Criteria

### Functional Criteria

- 관리자 로그인 가능
- MANAGER가 관리자 계정 생성 가능
- MANAGER가 관리자 권한 변경 가능
- 회원 등록 가능
- 회원 비활성화 가능
- 횟수권 상품 생성 가능
- 회원에게 횟수권 발급 가능
- 수업 생성 가능
- 수업 취소 가능
- 수업 예약 가능
- 예약 취소 가능
- 예약 취소 시 이용권 복구 가능

### Business Rule Criteria

- ACTIVE 회원만 예약 가능
- OPEN 수업만 예약 가능
- 중복 예약 불가
- 정원 초과 불가
- 사용 가능한 횟수권 필요
- 예약 생성 시 이용권 차감
- 예약 취소 시 이용권 복구
- 이용권 잔여 횟수 음수 불가

### Technical Criteria

- 예약 생성 플로우가 하나의 트랜잭션으로 처리된다.
- 예약 취소 플로우가 하나의 트랜잭션으로 처리된다.
- 비관적 락 기반 동시성 제어가 적용된다.
- 동시 예약 테스트가 통과한다.
- 중복 예약 테스트가 통과한다.
- 예약 취소 복구 테스트가 통과한다.
- API 문서가 제공된다.
- Neon PostgreSQL 기반 개발/배포 실행 설정이 제공된다.

---

## 7. Post-MVP Roadmap

## P1. Event Architecture Evolution

- Domain Event 고도화
- ApplicationEvent Adapter 정리
- Transactional Outbox Pattern 도입
- 외부 메시지 브로커 연동 준비
- Kafka 또는 SQS 연동 검토

## P2. Notification System

- 예약 완료 알림
- 예약 취소 알림
- 수업 취소 알림
- 이메일 또는 메시징 채널 연동

## P3. Attendance Check

- 출석 체크
- 노쇼 처리
- 출석 이력 조회
- 출석률 통계 기반 준비

## P4. Additional Pass Types

- 기간권
- 일일권
- 복합 이용권 정책
- 이용권 우선순위 선택 정책

## P5. Multi-Branch Operation

- 지점 도메인 추가
- 지점별 수업 관리
- 지점별 회원권 정책
- 지점별 관리자 권한

---

## 8. Risks & Mitigation

| Risk | Description | Mitigation |
| --- | --- | --- |
| 예약 트랜잭션 복잡도 증가 | Member, Pass, Class, Reservation이 하나의 유스케이스에서 조율됨 | Application Service에서 트랜잭션 경계를 명확히 정의 |
| 동시성 테스트 불안정 | 멀티스레드 테스트는 환경에 따라 flaky할 수 있음 | CountDownLatch 기반 테스트 구조 사용, DB 락 전략 명시 |
| 도메인 로직 분산 | 비즈니스 규칙이 Controller/Service에 흩어질 수 있음 | Aggregate 내부 불변조건은 도메인 객체에 위치 |
| 범위 확장 | 알림, 통계, 결제 등으로 MVP 범위가 커질 수 있음 | Out of Scope를 명확히 유지 |
| 포트폴리오 설명 부족 | 구현은 되었지만 설계 의도가 드러나지 않을 수 있음 | README와 문서에 트랜잭션/동시성 설계 의도를 명시 |

---

## 9. Current Status

| Milestone | Status | Notes |
| --- | --- | --- |
| M0. Project Setup | Planned | 프로젝트 기반 구성 필요 |
| M1. Auth & Admin | Planned | JWT, Role 기반 권한 구현 예정 |
| M2. Member | Done | 회원 등록, 목록/단건 조회, 비활성화, ACTIVE 예약 가능성 검증 구현 및 테스트 통과 |
| M2.5. Database Baseline | Ready | M3 착수 전 Flyway MVP baseline migration 적용. Ticket: Introduce MVP baseline database migration |
| M3. Pass | Planned | 착수 전 MVP baseline migration 적용 필요. 이후 횟수권 차감/복구 모델 구현 |
| M4. Class | Planned | 정원 관리와 상태 모델 필요 |
| M5. Reservation Core | Planned | MVP 핵심 기능 |
| M6. Reservation Cancellation | Planned | 좌석/이용권 복구 검증 필요 |
| M7. Concurrency & Test Hardening | Planned | 포트폴리오 핵심 증명 구간 |
| M8. API Docs & Portfolio Polish | Planned | README/API 문서/시연 시나리오 정리 |

---

## 10. Roadmap Summary

ClimbDesk MVP의 핵심은 예약 기능 자체가 아니라, 예약을 둘러싼 데이터 정합성을 안정적으로 보장하는 것이다.

```plain text
선행 도메인 구축
→ Database Baseline 고정
→ 예약 트랜잭션 구현
→ 취소 복구 구현
→ 동시성 검증
→ 포트폴리오 문서화
```

최종적으로 ClimbDesk는 다음을 증명하는 프로젝트가 되어야 한다.

- 비즈니스 규칙을 도메인 모델에 반영할 수 있다.
- 복수 Aggregate를 Application Service에서 일관되게 조율할 수 있다.
- 트랜잭션 경계를 명확히 설계할 수 있다.
- 동시 요청 상황에서도 핵심 불변조건을 보장할 수 있다.
- 테스트와 문서를 통해 설계 의도를 설명할 수 있다.
