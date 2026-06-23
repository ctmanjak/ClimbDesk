# ClimbDesk PRD v0.2

> **Source of truth notice**
>
> The source of truth for this document is the Notion page `01 - PRD`.
> This Markdown file is a repository-local snapshot exported on 2026-05-08 for implementation reference.
> Do not treat this snapshot as an independent design decision record when it conflicts with Notion.

> **Portfolio-driven Backend Project**
>
> 회원권 기반 수업 예약 도메인을 통해 트랜잭션, 동시성 제어, 도메인 모델링 역량을 증명하는 백엔드 포트폴리오 프로젝트.

---

# 1. Product Overview

## One-line Summary

클라이밍장 운영자를 위한 회원권 기반 수업 예약 관리 시스템.

## Problem

분산된 운영 방식으로 인해 예약 정합성, 이용권 차감, 취소 처리에서 오류 가능성이 있다.

## Solution

다음 흐름을 하나의 시스템으로 관리한다.

```plain text
회원
→ 이용권 보유
→ 수업 예약
→ 이용권 차감
→ 취소 시 복구
```

---

# 2. Goals

## Primary Goal

다음 백엔드 역량을 증명하는 포트폴리오 프로젝트 구축.

- 도메인 모델링
- 트랜잭션 설계
- 동시성 제어
- 테스트 자동화
- API 설계

## Product Goal

회원권 기반 예약 흐름의 정합성을 안정적으로 관리한다.

---

# 3. MVP Scope

## 3.1 Auth

- 관리자 로그인
- JWT 인증
- Role 기반 권한 (MANAGER, STAFF)
- 관리자 계정 생성
- 관리자 권한 변경

## 3.2 Member

- 회원 등록
- 회원 조회
- 회원 비활성화

## 3.3 Pass

- 횟수권 상품 생성
- 회원 이용권 발급
- 이용권 차감
- 이용권 복구
- 사용 이력 기록

## 3.4 Class

- 수업 생성
- 수업 조회
- 수업 취소
- 정원 관리

## 3.5 Reservation

- 수업 예약
- 예약 취소
- 중복 예약 방지
- 정원 초과 방지
- 예약 이벤트 발행

---

# 4. Core Business Rules

## Reservation Rules

예약 생성 조건:

- ACTIVE 회원만 예약 가능
- OPEN 상태 수업만 예약 가능
- 중복 예약 불가
- 정원 초과 불가
- 사용 가능한 횟수권 필요

## Transaction Rule

다음은 하나의 트랜잭션으로 처리한다.

```plain text
예약 생성
좌석 차감
이용권 차감
사용 이력 기록
```

## Concurrency Rule

항상 다음을 만족해야 한다.

```plain text
reservedCount <= capacity
```

MVP에서는 비관적 락을 사용한다.

## Cancellation Rule

취소 시:

```plain text
예약 취소
좌석 복구
이용권 복구
이력 기록
```

## Duplicate Reservation Rule

중복 예약 금지는 cross-instance invariant로 보고 다음 두 방식으로 보장한다.

- Application pre-check
- Database unique constraint

---

# 5. Core User Scenarios

## 이용권 발급

```plain text
회원 조회
→ 횟수권 발급
→ MemberPass 생성
```

## 수업 예약

```plain text
회원 검증
→ 수업 검증
→ 예약 생성
→ 횟수 차감
→ 이벤트 발행
```

## 예약 취소

```plain text
예약 취소
→ 횟수 복구
→ 이벤트 발행
```

---

# 6. Non Functional Requirements

## Reliability

- 정원 초과 불가
- 중복 예약 불가
- 이용권 음수 차감 불가

## Testability

테스트 대상:

- 동시 예약 테스트
- 중복 예약 테스트
- 예약 취소 복구 테스트

---

# 7. Success Criteria

## Functional

- 관리자 로그인 가능
- MANAGER가 관리자 계정 생성 가능
- MANAGER가 관리자 권한 변경 가능
- 회원 등록 가능
- 횟수권 발급 가능
- 수업 예약 가능
- 취소 시 이용권 복구
- 정원 초과 방지

## Technical

- 트랜잭션 정합성 보장
- 비관적 락 기반 동시성 테스트 통과
- API 문서 제공
- Docker 로컬 실행 가능

---

# Appendix A. Trade-offs

- 여러 이용권 유형 대신 횟수권만 우선 구현
- 복잡한 RBAC 대신 단순 Role 모델 사용
- 수업 수정 대신 취소 후 재생성
- 검색보다 예약 정합성 구현 우선
- 실제 PG/알림 실연동 제외

---

# Appendix B. Out of Scope

- 기간권/일일권
- 수업 수정
- 대기 예약
- 출석 체크
- 멀티 지점 운영
- 통계 대시보드
- 프론트엔드 앱

---

# Appendix C. Future Expansion

- SQS/Kafka 이벤트 처리
- Transactional Outbox Pattern
- 알림 시스템
- 멀티 브랜치 운영
- 출석 체크

---

# Appendix D. Architecture Notes

## Hybrid DDD Approach

Aggregate 내부 불변조건은 Aggregate가 책임지고, 복수 Aggregate를 조율하는 유스케이스 규칙은 Application Service가 책임진다.

## Event Evolution Path

```plain text
Domain Event
→ ApplicationEvent Adapter
→ External Broker
→ Transactional Outbox
```

## Event Principle

- DomainEventPublisher 포트 사용
- Spring 이벤트는 어댑터에 국한
- 외부 메시징 전환 가능성 고려

---

# Product Statement

> ClimbDesk는 회원권 검증, 예약 생성, 이용권 차감, 동시성 제어를 통해 백엔드 설계 역량을 증명하는 포트폴리오 프로젝트다.
