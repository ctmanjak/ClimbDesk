# MQ 구현 결과 및 승인 설계 차이

이 문서는 승인된 MQ 설계를 계획으로 반복하지 않고, MQ-T01~MQ-T07이 병합된 `origin/develop`의 실제 코드·설정·migration·테스트 결과를 기록한다. 기존 MVP 문서는 변경하지 않는다.

## 1. 기준과 범위

- 기준 commit: `55504be911a288d607da554add04c5cca6dc0764` (`origin/develop`, PR #67 merge commit)
- 확인일: 2026-09-10
- 승인 설계: Notion `11 - MQ 기반 비동기 이벤트 처리 설계`
- 구현 범위: `ReservationConfirmedEvent`의 Transactional Outbox, polling Publisher, RabbitMQ main/retry/DLQ, 멱등 Consumer, 관측 및 수동 복구
- 결과 저장소 문서: `docs/22-mq-implementation-results.md`
- Notion 결과 페이지: [`12 - MQ 구현 결과 및 승인 설계 차이`](https://app.notion.com/p/3d74c60a73038126b1d5e8a8973981c8)
- 운영 절차: [`docs/19-mq-recovery-observability-runbook.md`](19-mq-recovery-observability-runbook.md)

문서 번호는 요청된 경로 `22`를 사용한다. 기준 commit의 tracked `docs`에는 `19`까지 존재하지만, 기본 worktree에 있는 사용자 소유의 미병합 문서를 건드리거나 번호를 재사용하지 않기 위해 `20` 또는 `21`을 선택하지 않았다.

### 1.1 선행 작업과 병합 근거

| 티켓 | 완료 범위 | 병합 PR | merge commit |
| --- | --- | --- | --- |
| MQ-T01 | 시간 의존 테스트 fixture 복구 | [#59](https://github.com/ctmanjak/ClimbDesk/pull/59) | `57b4b251a0fb3d45ddba7367d50e679518fb5aa0` |
| MQ-T02 | 외부 계약, Outbox 확장, Consumer 멱등 schema | [#60](https://github.com/ctmanjak/ClimbDesk/pull/60) | `d0d1a75afb1ee88301a6464aa1602ba773c023c6` |
| MQ-T03 | RabbitMQ topology와 테스트 환경 | [#61](https://github.com/ctmanjak/ClimbDesk/pull/61) | `ede7749ed49a2235938110723589e9ed542c2a82` |
| MQ-T04 | polling Outbox Publisher | [#62](https://github.com/ctmanjak/ClimbDesk/pull/62) | `27ecfaa348a74668bdaa13fd000a276d9e4f3801` |
| MQ-T02~T04 보완 | migration·confirm timeout·NACK·broker restart 증거 | [#63](https://github.com/ctmanjak/ClimbDesk/pull/63) | `7c427962df2c62afe6cb03ae345b7bb81e0e2e37` |
| MQ-T05 | 예약 완료 멱등 Consumer | [#64](https://github.com/ctmanjak/ClimbDesk/pull/64) | `23f7da04289273e24444645fb4c97e2119fae99f` |
| MQ-T06 | Consumer retry와 DLQ | [#65](https://github.com/ctmanjak/ClimbDesk/pull/65) | `abeb21d05d11acf0b5180248230ca39b37da26af` |
| MQ-T07 | 장애 복구·관측·runbook·E2E | [#66](https://github.com/ctmanjak/ClimbDesk/pull/66) | `24d46e9359dc92491dd687215bc7d6db5989b347` |
| MQ-T07 후속 | queue sampler scheduler와 큐별 실패 격리 | [#67](https://github.com/ctmanjak/ClimbDesk/pull/67) | `55504be911a288d607da554add04c5cca6dc0764` |

위 merge commit은 모두 기준 commit의 ancestor임을 확인했다.

## 2. 최종 구성과 활성화

Gradle dependency resolution에서 확인한 버전은 다음과 같다.

| 구성 요소 | 실제 버전 또는 이미지 | 근거 |
| --- | --- | --- |
| Kotlin | `2.3.20` | `build.gradle.kts` plugin |
| Spring Boot / starter AMQP | `3.5.14` | plugin 및 runtime dependency resolution |
| Spring Rabbit / Spring AMQP | `3.2.10` / `3.2.10` | runtime dependency resolution |
| RabbitMQ Java client | `5.25.0` | runtime dependency resolution |
| Testcontainers | `1.21.4` | test runtime dependency resolution |
| PostgreSQL 통합 환경 | `postgres:16-alpine`; 이번 측정에서 PostgreSQL `16.13` | 테스트 container 로그 |
| RabbitMQ 통합·로컬 환경 | `rabbitmq:4.1-management-alpine` | 테스트와 `compose.rabbitmq.yml` |

이미지의 floating patch tag는 특정 patch/digest 고정이 아니다. 따라서 `4.1-management-alpine`은 검증된 major/minor 이미지 선택이지 모든 실행에서 같은 patch binary를 보장하는 값이 아니다.

### 2.1 활성화 property와 기본값

| property | 환경 변수 | 기본값 | 효과 |
| --- | --- | --- | --- |
| `climbdesk.messaging.rabbitmq.enabled` | `CLIMBDESK_RABBITMQ_ENABLED` | `false` | topology와 Rabbit health 및 queue sampler 활성화 |
| `climbdesk.messaging.rabbitmq.publisher-enabled` | `CLIMBDESK_RABBITMQ_PUBLISHER_ENABLED` | `false` | Outbox Publisher와 전용 scheduler 활성화 |
| `climbdesk.messaging.rabbitmq.listener-enabled` | `CLIMBDESK_RABBITMQ_LISTENER_ENABLED` | `false` | 예약 알림 listener와 failure router/publisher 활성화 |
| `...publisher.poll-interval` | `CLIMBDESK_RABBITMQ_PUBLISHER_POLL_INTERVAL` | `1s` | scheduler fixed delay와 initial delay |
| `...publisher.max-per-tick` | `CLIMBDESK_RABBITMQ_PUBLISHER_MAX_PER_TICK` | `20` | 한 tick의 최대 처리 event 수 |
| `...publisher.max-attempts` | `CLIMBDESK_RABBITMQ_PUBLISHER_MAX_ATTEMPTS` | `5` | 최초 시도를 포함한 총 publish 시도 수 |
| `...publisher.confirm-timeout` | `CLIMBDESK_RABBITMQ_PUBLISHER_CONFIRM_TIMEOUT` | `5s` | Publisher와 failure republish confirm 대기 |
| `...publisher.retry-backoffs` | `CLIMBDESK_RABBITMQ_PUBLISHER_RETRY_BACKOFFS` | `5s,30s,2m,10m` | Publisher 실패 후 네 backoff |
| `...observability.management-base-url` | `CLIMBDESK_RABBITMQ_MANAGEMENT_BASE_URL` | `http://localhost:15672` | management API 기준 URL |
| `...observability.sample-interval` | `CLIMBDESK_RABBITMQ_METRICS_SAMPLE_INTERVAL` | `5s` | queue gauge 표본 주기 |
| `...observability.request-timeout` | `CLIMBDESK_RABBITMQ_METRICS_REQUEST_TIMEOUT` | `2s` | management 연결·요청 제한 |

RabbitMQ 연결 기본값은 host `localhost`, port `5672`, username/password `climbdesk`, virtual host `/`다. Spring 설정은 correlated publisher confirms, publisher returns, mandatory template, manual ACK, `default-requeue-rejected=false`, prefetch `10`, concurrency `1`을 사용한다. MQ 세 활성화 flag는 모두 opt-in이다.

## 3. 실제 처리 흐름과 전달 경계

### 3.1 예약 transaction과 Outbox

`ReservationApplicationService.reserveClass()`의 단일 PostgreSQL transaction 안에서 회원·수업·이용권 검증, 예약과 이용권 사용 이력 저장, 좌석 수 갱신 뒤 `ReservationConfirmedEvent`를 기록한다. `OutboxEventPersistenceAdapter.record()`는 `Propagation.MANDATORY`이므로 caller transaction 밖에서 실행되지 않는다. 예약 생성 실패 시 예약 관련 변경과 Outbox가 함께 rollback된다.

새 예약 확정 Outbox의 초기 상태는 다음과 같다.

- `status=PENDING`
- `publish_target=RABBITMQ`
- `schema_version=1`
- `retry_count=0`
- `occurred_at`은 domain event 발생 시각
- `published_at`, `next_retry_at`, `last_error`는 `null`

Migration V3는 기존 Outbox를 기본 `publish_target=NONE`, `schema_version=1`로 유지한다. 새 `ReservationCanceledEvent`와 `ClassSessionCanceledEvent`도 계속 `NONE`이고, 새 `ReservationConfirmedEvent`만 `RABBITMQ`다. 과거 행은 소급 발행하지 않는다.

### 3.2 polling Publisher

한 scheduler tick은 최대 20번 `publishNext()`를 호출하며 빈 claim 또는 예외에서 즉시 멈춘다. 각 event는 별도의 `REQUIRES_NEW` transaction에서 처리된다.

```text
RABBITMQ 대상 PENDING 또는 due FAILED 한 행
→ SELECT ... FOR UPDATE SKIP LOCKED, LIMIT 1
→ version 1 envelope와 AMQP properties 생성
→ durable topic exchange에 mandatory + persistent publish
→ 최대 5초 correlated confirm과 mandatory return 판정
→ 성공: PUBLISHED, publishedAt 설정, retry 정보 제거
→ 실패: FAILED, retryCount 증가, nextRetryAt/lastError 저장
→ Outbox transaction commit
```

실제 claim SQL은 `publish_target='RABBITMQ'`이고, `PENDING` 또는 `FAILED AND retry_count < :maxAttempts AND next_retry_at <= :now`인 행만 선택한다. 정렬은 `next_retry_at ASC NULLS FIRST, id ASC`다. V4의 partial index `idx_outbox_events_publishable`은 `RABBITMQ`이며 `PENDING`/`FAILED`인 행을 지원한다.

Publisher 성공은 send 성공, 제한 시간 안 confirm ACK, mandatory return 없음이 모두 충족되어야 한다. NACK, timeout, return, connection/channel 오류, 일반 mapping 오류는 실패다. 지원하지 않는 RabbitMQ event type/schema는 재시도해도 회복되지 않으므로 즉시 terminal `FAILED`로 만든다. 일반 실패의 1~4회차 뒤에는 각각 5초, 30초, 2분, 10분 후를 `next_retry_at`으로 저장하고, 5회차 실패는 `next_retry_at=null`인 terminal 상태다. `last_error`는 whitespace를 정규화하고 1,000자로 제한한다.

### 3.3 RabbitMQ topology

모든 exchange와 queue는 durable이며 메시지는 persistent다.

| 단계 | exchange (type) | routing key | queue | TTL / 다음 경로 |
| --- | --- | --- | --- | --- |
| main | `climbdesk.events` (topic) | `reservation.confirmed.v1` | `climbdesk.reservation-notification.v1` | TTL 없음; main queue dead-letter는 DLX/DLQ |
| retry 1 | `climbdesk.events.retry` (direct) | queue 이름과 동일 | `climbdesk.reservation-notification.v1.retry.5s` | `5,000ms`; 만료 후 main exchange/key |
| retry 2 | `climbdesk.events.retry` (direct) | queue 이름과 동일 | `climbdesk.reservation-notification.v1.retry.30s` | `30,000ms`; 만료 후 main exchange/key |
| retry 3 | `climbdesk.events.retry` (direct) | queue 이름과 동일 | `climbdesk.reservation-notification.v1.retry.2m` | `120,000ms`; 만료 후 main exchange/key |
| terminal | `climbdesk.events.dlx` (direct) | `climbdesk.reservation-notification.v1.dlq` | `climbdesk.reservation-notification.v1.dlq` | 자동 replay/return 없음 |

### 3.4 외부 event envelope와 AMQP 계약

실제 mapper가 생성하는 JSON 형식의 예는 다음과 같다.

```json
{
  "eventId": 12345,
  "eventType": "reservation.confirmed",
  "schemaVersion": 1,
  "producer": "climbdesk",
  "aggregateType": "Reservation",
  "aggregateId": 987,
  "occurredAt": "2026-09-10T06:00:00Z",
  "payload": {
    "reservationId": 987,
    "memberId": 201,
    "classSessionId": 301,
    "memberPassId": 401
  }
}
```

- `eventId`: DB가 생성한 `outbox_events.id`; retry와 replay에서 바꾸지 않는다.
- AMQP `messageId`: 같은 eventId의 10진 문자열.
- `schemaVersion`: Outbox와 envelope 및 `x-schema-version` 모두 `1`.
- `occurredAt`: 예약 transaction에서 생성한 domain event의 `Instant`; Consumer 처리 시간이 아니다.
- `producer`: envelope와 `x-producer` 모두 `climbdesk`.
- AMQP `type`: `reservation.confirmed`; `contentType=application/json`.
- payload: ID만 포함하며 이름, 이메일, 전화번호를 포함하지 않는다.

Consumer는 양수 ID, `eventType`, `schemaVersion`, `producer`, `aggregateType`, `aggregateId == payload.reservationId`, AMQP `messageId`, `type`, 숫자형 `x-schema-version`과 payload ID를 모두 검사한다. 손상 JSON, 필수 필드 누락, 불일치, 지원하지 않는 version은 `PERMANENT_MESSAGE`로 즉시 DLQ에 보낸다.

현재 compatibility 규칙은 version 1만 수신하는 명시적 검증이다. 호환 가능한 optional field 추가는 version 1을 유지할 수 있지만, required field의 의미 변경·삭제는 새 version과 routing key가 필요하다. 저장된 과거 payload를 새 version으로 위장해 갱신하지 않는다. version 2 Consumer와 routing은 아직 구현하지 않았다.

### 3.5 Consumer transaction, ACK와 실패 routing

정상 처리 순서는 다음과 같다.

```text
retry header·JSON·계약 검증
→ handler PostgreSQL transaction 시작
→ processed_events INSERT ... ON CONFLICT DO NOTHING
→ conflict면 DUPLICATE business no-op
→ Reservation 현재 상태와 event의 네 ID 일치 확인
→ CONFIRMED면 READY, CANCELED면 SKIPPED_STALE notification 저장
→ processed event와 notification transaction commit
→ listener가 basicAck
```

`processed_events`의 PK는 `(consumer_name, event_id)`이고 Consumer 이름은 `reservation-notification-v1`이다. `reservation_notification_requests.source_event_id` unique constraint가 독립적인 두 번째 방어선이다. insert-first 이후 Reservation 조회나 notification 저장이 실패하면 두 table 변경이 같은 transaction에서 rollback된다.

실패는 cause type으로 분류한다.

| 분류 | 실제 입력 | 동작 |
| --- | --- | --- |
| `PERMANENT_MESSAGE` | JSON/계약 오류, 유효하지 않은 retry header | 즉시 DLQ |
| `DATA_CONSISTENCY` | Reservation 없음 또는 ID 불일치 | Consumer DB rollback 후 즉시 DLQ |
| `TRANSIENT` | Spring transient/recoverable data access, SQL transient/recoverable | 최대 3회 retry |
| `UNKNOWN` | 그 밖의 예외 | 같은 제한 retry 후 DLQ |
| duplicate | insert conflict | 정상 no-op commit 후 ACK |

`x-retry-count` 누락은 0이다. Byte/Short/Int/Long의 `0..Int.MAX_VALUE`만 허용하고, 잘못된 type·음수·overflow는 0으로 초기화하지 않는다. 현재 count 0/1/2는 각각 5초/30초/2분 queue로 republish하면서 outgoing copy에 1/2/3을 기록하고, count 3 이상은 기존 count를 유지한 채 DLQ로 보낸다.

failure republish는 원본 body, messageId, type, `x-schema-version`, `x-producer`, `x-retry-count`와 제한된 조사 header만 복사한다. republish send, confirm ACK, mandatory return 없음이 확인된 뒤에만 원본 delivery를 ACK한다. NACK, timeout, return, send/connection 오류, interrupt이면 failure publisher가 예외를 전파하므로 listener의 마지막 `basicAck`에 도달하지 않는다. listener는 이때 `basicNack`/reject도 하지 않아 원본을 unacked로 보존하고, channel/connection 또는 listener 재시작 뒤 redelivery를 받는다.

### 3.6 at-least-once 경계

이 구현은 exactly-once가 아니다.

- broker가 메시지를 수락하고 confirm을 보낸 뒤 Outbox `PUBLISHED` DB commit 전에 장애가 나면 같은 eventId가 다시 publish될 수 있다.
- Consumer DB commit 뒤 ACK 전 연결이 끊기면 원본이 redelivery된다.
- retry/DLQ republish confirm 뒤 원본 ACK 전에 연결이 끊기면 원본과 republish 사본이 모두 전달될 수 있다.
- DLQ replay confirm 뒤 DLQ 원본 ACK 전에 연결이 끊겨도 중복 가능성이 남는다.

따라서 보장은 `committed Outbox → at-least-once broker publish → at-least-once delivery → DB 멱등성으로 business effect 한 건`이다. 동일 eventId delivery 자체를 한 번으로 만들지 않고, processed PK와 notification unique constraint로 최종 효과를 흡수한다.

## 4. 운영 관측과 복구

### 4.1 metric 계약

Micrometer의 점 이름은 Prometheus에서 underscore와 suffix로 노출된다.

| Prometheus metric | 단위 | label | 집계 시점과 실패 의미 |
| --- | --- | --- | --- |
| `climbdesk_messaging_outbox_events` | events | `state=pending,retry,terminal` | scrape 때 `RABBITMQ` 대상 PostgreSQL 현재 상태 조회; DB 실패 시 해당 scrape 값 `NaN` |
| `climbdesk_messaging_outbox_oldest_unpublished_age_seconds` | seconds | 없음 | scrape 때 미발행 `occurred_at` 최솟값의 age; 행이 없으면 0, DB 실패면 `NaN` |
| `climbdesk_messaging_outbox_publish_failures_total` | cumulative attempts | 없음 | 실패 상태가 commit된 뒤 증가; 현재 backlog 수가 아님 |
| `climbdesk_messaging_consumer_duplicates_total` | cumulative results | 없음 | duplicate handler transaction 반환 뒤 증가 |
| `climbdesk_messaging_consumer_failure_republish_confirmed_total` | cumulative results | `destination=retry,dlq` | confirm ACK이며 mandatory return 없음이 판정된 뒤 증가 |
| `climbdesk_messaging_consumer_failure_republish_failures_total` | cumulative attempts | `destination=retry,dlq` | 안전하게 confirm되지 않은 시도당 한 번 증가 |
| `climbdesk_messaging_rabbitmq_queue_depth` | messages | `queue=main,retry_5s,retry_30s,retry_2m,dlq` | management API 표본의 ready+unacked; 해당 queue 조회 실패만 `NaN` |
| `climbdesk_messaging_rabbitmq_queue_unacknowledged` | messages | 같은 고정 queue label | management API의 현재 unacked; 누적 실패 counter가 아님 |
| `climbdesk_messaging_consumer_processing_latency_seconds` | seconds | 없음 | `PROCESSED` 결과 반환 뒤 Timer 기록; rollback과 duplicate 제외 |
| `climbdesk_messaging_rabbitmq_main_backlog_drain_seconds` | seconds | 없음 | sampler가 main non-empty→empty 전이를 관측할 때 Timer 기록 |

eventId, messageId, 사용자 ID, exception message는 metric label에 사용하지 않는다. queue sampler는 `rabbitMqQueueMetricsTaskScheduler`, Outbox Publisher는 `outboxPublisherTaskScheduler`라는 서로 다른 단일-thread scheduler를 사용한다. 한 queue의 management API 조회 실패는 해당 queue의 depth/unacknowledged만 `NaN`으로 만들고, 나머지 queue snapshot은 계속 갱신한다. main 조회가 실패한 표본에서는 잘못된 drain 전이를 기록하지 않는다.

`/actuator/prometheus`는 기존 보안 정책상 JWT 인증이 필요하고 `/actuator/health`만 public이다. management 자격 증명과 Prometheus bearer token은 비밀로 관리해야 한다.

### 4.2 unacknowledged 포화와 alert

`ClimbDeskReservationConsumerPrefetchSaturated`는 main queue의 unacknowledged가 현재 prefetch와 같은 `10` 이상이고, 최근 2분 동안 정상 처리 Timer count와 duplicate count가 모두 증가하지 않는 조건이 추가 2분 지속될 때 firing한다. 이 조건은 확인되지 않은 failure republish를 유실 없이 unacked로 보존하는 대신 prefetch가 차면 처리 가용성이 멈추는 tradeoff를 감지한다.

추가 규칙은 terminal Outbox가 1분 지속될 때 `ClimbDeskTerminalOutboxPresent`, DLQ depth가 1분 동안 0보다 클 때 `ClimbDeskReservationNotificationDlqPresent`다. repository는 Prometheus rule과 `promtool` fixture까지만 제공하며 Alertmanager, Slack/email 통지는 구현하지 않는다.

### 4.3 terminal Outbox 단건 requeue

전체 절차와 SQL은 runbook 5절을 따른다.

1. eventId로 행을 `SELECT ... FOR UPDATE`하고 현재 배포의 `max-attempts`로 terminal 조건을 확인한다.
2. 같은 eventId이며 `publish_target='RABBITMQ'`, `status='FAILED'`, `retry_count >= :max_publish_attempts OR next_retry_at IS NULL`인 한 행만 조건부 갱신한다.
3. `PENDING`, `retry_count=0`, retry/error/published 시각 `null`, `updated_at=now()`로 새 retry budget을 부여한다.
4. `UPDATE 1`만 commit한다. 0이면 rollback 후 상태를 다시 조사한다.
5. `PUBLISHED`, queue drain, processed/notification 최대 한 건을 확인하고 조작자·UTC 시각·원인·사전/사후 상태를 운영 기록에 남긴다.

`MessagingMetricsIntegrationTest`.`terminal Outbox runbook uses the current policy and requeues only one stranded RabbitMQ row`가 PostgreSQL에서 첫 실행 1행, 재실행 0행, `NONE`/retry 대기/다른 terminal 행 비변경을 검증한다. public requeue API와 일괄 requeue는 없다.

### 4.4 DLQ 단건 조사와 replay

`./gradlew dlqReplayOne`의 기본 `inspect`는 DLQ head 한 건을 ACK 없이 가져오고 body를 출력하지 않은 채 조사 metadata를 출력한 후 requeue한다. `replay`는 양수 `CLIMBDESK_DLQ_REPLAY_EVENT_ID`와 정확히 같은 `CLIMBDESK_DLQ_REPLAY_CONFIRM`을 요구하며, head eventId가 다르면 원본을 DLQ에 둔다.

replay는 같은 body/messageId와 나머지 properties·조사 metadata를 보존하되, 운영자가 원인을 해결하고 명시적으로 승인한 새 처리 시도라는 계약에 따라 `x-retry-count`만 `0`으로 초기화해 main exchange의 `reservation.confirmed.v1`로 mandatory persistent publish한다. correlated confirm ACK와 mandatory return 없음 뒤에만 DLQ 원본을 ACK하며 실패하면 channel이 열려 있을 때 원본을 requeue한다. `ReservationNotificationRabbitMqIntegrationTest`.`single DLQ replay preserves event id and acknowledges original only after confirmed routing`은 실제 RabbitMQ에서 eventId `701`, body와 조사 metadata 보존, retry count `3→0`, DLQ 0을 확인한 뒤 실제 Consumer로 처리해 main 0, processed 1, notification 1을 검증한다. 자동·일괄 replay와 payload 수정 기능은 없다.

## 5. 장애 시나리오 검증 결과

| 시나리오 | 정확한 테스트명 | 실제 최종 상태 |
| --- | --- | --- |
| 예약 transaction rollback | `ReservationCreationOutboxFailureIntegrationTest`의 rollback 시나리오, `OutboxEventPersistenceAdapterIntegrationTest`.`record joins caller transaction and rolls back with it` | 실패 transaction의 Reservation/Outbox 없음 |
| 두 Publisher 동시 claim | `OutboxEventStoreAdapterIntegrationTest`.`skip locked prevents two transactions from claiming the same row`, `skip locked lets two transactions claim different rows` | 같은 행 중복 claim 없음; 처리 가능 행이 둘이면 서로 다른 행 |
| 정상 publish | `OutboxPublisherRabbitMqIntegrationTest`.`publisher records published only after confirmed routed persistent message` | Outbox `PUBLISHED`, persistent main message, 같은 messageId |
| unroutable/return | `OutboxPublisherRabbitMqIntegrationTest`.`mandatory return remains failed even when the broker confirms the publish` | confirm ACK가 있어도 Outbox `FAILED` |
| publish 성공 후 DB 상태 저장 실패 | `OutboxPublisherRabbitMqIntegrationTest`.`confirmed publish followed by database status failure can publish the same event id twice` | broker에 같은 eventId 중복 가능성이 재현됨 |
| broker 중단·복구 | `OutboxPublisherBrokerRecoveryIntegrationTest`.`reservation and outbox commit while broker is down and scheduler publishes after recovery` | 중단 중 HTTP 201, Reservation 1, Outbox `FAILED/retryCount=1`; 복구 후 `PUBLISHED`, main 0, processed 1, notification 1 |
| 같은 broker node restart 내구성 | `RabbitMqRestartIntegrationTest`.`durable production topology and persistent message survive the same broker restart` | 단독 실행에서는 durable topology와 persistent message 유지 확인. 전체 suite에서는 같은 assertion이 세 번 실패했으며 상세 결과는 8절에 기록 |
| Consumer DB rollback 후 redelivery | `ReservationConfirmedNotificationRollbackIntegrationTest`.`notification persistence failure rolls back processed insert and the same event can succeed on redelivery` | 첫 시도 processed/notification 0; 재처리 후 각각 1 |
| DB commit 후 ACK 전 연결 종료 | `ReservationNotificationRabbitMqIntegrationTest`.`database commit followed by connection close before ack redelivers and remains one business result` | redelivered=true; processed 1, notification 1, main 0 |
| Consumer 중단·복구 | `ReservationNotificationRabbitMqIntegrationTest`.`reservation and outbox publish continue while consumer is stopped and backlog drains after restart` | 중단 중 Outbox `PUBLISHED`, main 1, business row 0; 재시작 후 main 0, processed 1, notification 1 |
| 순차·동시 duplicate | `ReservationConfirmedNotificationHandlerIntegrationTest`.`sequential duplicate returns no-op without another business result`, `concurrent duplicate transactions use the PostgreSQL primary key and create one business result` | processed 1, notification 1 |
| stale reservation | `ReservationConfirmedNotificationHandlerIntegrationTest`.`canceled reservation creates a terminal skipped stale result that redelivery does not change` | processed 1, notification `SKIPPED_STALE` 1 |
| 실제 5초 transient recovery | `ReservationNotificationRetryRabbitMqIntegrationTest`.`transient rollback recovers via real 5s TTL with publisher disabled` | 두 처리 시도 뒤 main/retry/DLQ 0, processed 1, notification 1 |
| transient/unknown retry 소진 | `ReservationNotificationRetryRabbitMqIntegrationTest`.`production stages route in order and exhausted delivery leaves exactly one DLQ` | 5s→30s→2m 순서, business row 0, DLQ 1, 다른 queue 0 |
| permanent/data consistency | `permanent message goes directly to one DLQ without business rows`, `reservation consistency errors roll back processed insert and go directly to DLQ` | retry 없이 DLQ 1, processed/notification 0 |
| failure republish 미확인 | `mandatory return leaves manual delivery unacked and original redelivers after connection close`, `background container also preserves unconfirmed delivery without default DLX or tight requeue` | ACK/NACK/reject 없음; 연결/리스너 종료 뒤 동일 body/messageId redelivery |
| unacked prefetch 포화·복구 | `ten unconfirmed failure republishes saturate prefetch then recover by binding repair and listener restart` | 실패 중 unacked 10, failed counter +10, confirmed +0, tight loop 없음; 복구 후 queue 0, processed 1, notification 1 |
| retry confirm 뒤 ACK 전 연결 종료 | `confirmed retry then connection loss before ACK creates duplicates absorbed by DB constraints` | 원본 redelivery와 retry delivery를 모두 받아도 processed 1, notification 1 |
| 큐별 sampler 장애 | `RabbitMqQueueMetricsIntegrationTest`.`one unavailable queue does not hide healthy main and DLQ gauges` | 삭제된 retry_5s gauge만 `NaN`; main/DLQ 정상 값 유지 |
| terminal 단건 requeue | `MessagingMetricsIntegrationTest`.`terminal Outbox runbook uses the current policy and requeues only one stranded RabbitMQ row` | 대상만 첫 UPDATE 1, 재실행 0 |
| DLQ 단건 replay | `ReservationNotificationRabbitMqIntegrationTest`.`single DLQ replay preserves event id and acknowledges original only after confirmed routing` | 동일 eventId/body/조사 metadata, retry count 0, DLQ/main 0, processed 1, notification 1 |

중복 delivery가 최종 business effect 한 건으로 수렴하는 핵심 증거는 ACK-gap, retry-republish ACK-gap, 순차·동시 duplicate의 네 경계를 실제 PostgreSQL unique contract와 함께 검증한 위 테스트들이다.

### 5.1 latency와 backlog drain

2026-09-10 현재 기준 commit의 완료 전 전체 회귀 실행에서 다음 값을 얻었다.

- Consumer 재시작 backlog drain: `5.042685s`
- event 발생부터 정상 Consumer 결과까지 processing latency: `2.297684s`
- 환경: macOS 로컬 Docker, PostgreSQL `16.13` (`postgres:16-alpine`), `rabbitmq:4.1-management-alpine`, Spring Boot `3.5.14`, 단일 Consumer concurrency 1/prefetch 10

drain은 5초 management sampling이 main non-empty와 다음 empty를 관측한 차이이고, latency는 event `occurredAt`부터 `PROCESSED` 반환 후 Timer 기록 시점까지다. 두 값은 container 시작 상태, management 통계 갱신, scheduler timing과 로컬 부하에 좌우되는 한 번의 기능 검증 관측치다. production 성능, percentile SLO 또는 처리량 보장이 아니다.

## 6. 승인 설계와 실제 구현 차이

| 설계 항목 | 승인 설계 | 실제 구현 | 상태 | 차이 또는 결정 이유 | 근거 |
| --- | --- | --- | --- | --- | --- |
| 예약 transaction + Outbox 원자성 | caller transaction에 Outbox 저장 | `MANDATORY` recorder, 예약 transaction 내부 저장 | 구현 일치 | 없음 | `ReservationApplicationService`, `OutboxEventPersistenceAdapter`, rollback tests |
| 역사 event 발행 방지 | 기존/취소 event `NONE`, 새 confirmed만 `RABBITMQ` | 동일 | 구현 일치 | 없음 | V3, persistence adapter, migration tests |
| polling claim | 한 행 `FOR UPDATE SKIP LOCKED`, due/attempt 제한 | 동일 | 구현 일치 | 없음 | `OutboxEventJpaRepository`, store integration tests |
| Publisher 정책 | 1s, tick 20, 총 5회, 5s/30s/2m/10m, confirm 5s | 동일 기본값 | 구현 일치 | 없음 | `application.yml`, `RabbitMqProperties`, policy tests |
| topology | durable topic main, direct retry/DLX, 5s/30s/2m | 상세 설계 이름·TTL과 동일 | 구현 일치 | 설계 architecture 그림의 축약 queue label보다 12.3절의 상세 이름을 구현 | topology constants/integration tests |
| 외부 계약 | Outbox ID, version 1, ID-only payload | 동일 | 구현 일치 | 없음 | envelope mapper/test |
| Consumer 멱등성 | insert-first 복합 PK + 결과 unique, 한 transaction | 동일 | 구현 일치 | 없음 | V3, handler, handler integration tests |
| ACK 경계 | DB commit 뒤 ACK | 정상/duplicate commit 반환 뒤 ACK | 구현 일치 | 없음 | listener 및 ACK-gap tests |
| Consumer retry/DLQ | typed 분류, 3회 delayed retry, confirm 뒤 원본 ACK | 동일 정책 | 구현 일치 | 없음 | classifier/router/failure publisher tests |
| failure republish 실패 | 확인되지 않으면 원본 ACK 금지 | ACK/NACK/reject 없이 unacked 유지 | 의도적으로 변경 | 유실 방지가 우선이며 자동 requeue/DLX loop를 막는다. 대신 prefetch 포화로 가용성이 낮아질 수 있어 원인 해결 후 listener/connection 재시작이 필요하다. | PR #65/#66, saturation test, runbook |
| DLQ 오류 정보 | 최종 exception class와 축약 message | class 이름 + 분류별 고정 `x-error-summary`; 실제 exception message는 복사하지 않음 | 의도적으로 변경 | SQL/PII/credential 유출 방지를 위해 진단 상세보다 보안을 선택했다. | `ReservationNotificationFailureRouter`, permanent/log tests |
| retry/DLQ metric | retry와 DLQ 상태를 관측 | 목적지별 confirmed/failure republish counter + queue depth gauge | 의도적으로 변경 | 처리 시도 counter와 broker의 현재 적재 상태를 분리해 해석한다. | `MessagingMetrics`, queue metrics tests, runbook |
| queue sampling | main/retry/DLQ depth | 5개 queue depth+unacked, 큐별 실패 격리 | 구현 일치 | PR #67에서 Outbox scheduler와 분리하고 실패 queue만 `NaN`으로 보완 | PR #67, scheduler isolation/partial failure tests |
| terminal Outbox 복구 | 제한된 script/runbook으로 한 건 requeue | 조건부 SQL runbook, PostgreSQL rehearsal | 구현 일치 | 애플리케이션 command/API 대신 SQL 절차를 선택 | runbook 5절, metrics integration test |
| DLQ 복구 | 같은 eventId 한 건 수동 replay | `dlqReplayOne`, `x-retry-count=0` 새 budget, confirm 뒤 ACK, 실제 Consumer 처리 | 구체화 | 운영자가 원인을 해결하고 승인한 replay를 새 처리 시도로 정의 | runbook 6절, reservation notification integration test |
| 전체 application 재시작 | durable DB·queue state 복구 | broker node restart, broker stop/start, Consumer stop/start는 자동 검증; JVM 전체 restart E2E는 없음 | 부분 구현 | 핵심 durability 경계는 분리 검증했지만 실제 애플리케이션 프로세스 전체 재시작 시나리오는 자동화하지 않았다. | restart/broker-recovery/consumer-recovery tests |
| Outbox `PUBLISHED` retention | 구현 후 정책 기록 | 삭제 job과 retention 정책 없음; 현행 보존 | 미구현 | dedup replay 기간과 함께 별도 운영 결정이 필요하다. | repository와 runbook에 cleanup 없음 |
| 실제 외부 알림 | 제외 | DB에 알림 요청만 기록 | 제외 범위 | provider idempotency와 provider/DB dual-write가 미해결 | handler, runbook |
| broker HA/quorum | 단일 container 한계 명시 | 단일 node durable restart만 검증 | 제외 범위 | cluster HA 보장을 주장하지 않음 | Testcontainers image/tests, runbook |
| 자동 DLQ replay/public API | 제외 | 없음 | 제외 범위 | 원인 확인 없는 순환 replay 방지 | `dlqReplayOne`, runbook |

### 6.1 ADR-1: 미확인 failure republish에서 원본을 unacked로 유지

**Context.** Consumer DB 처리가 실패한 뒤 retry/DLQ republish가 NACK, timeout, mandatory return 또는 connection 오류로 확인되지 않을 수 있다. 이때 원본을 ACK하면 유실되고, 즉시 NACK/requeue하면 tight loop 또는 예기치 않은 main queue DLX가 생길 수 있다.

**Decision.** republish가 confirm ACK이고 mandatory return이 없을 때만 원본을 ACK한다. 확인 실패에서는 ACK/NACK/reject를 하지 않고 channel/connection이 복구될 때까지 unacked로 둔다.

**Alternatives considered.** 실패 후 원본 ACK, 즉시 `basicNack(requeue=true)`, container default error handler/DLX를 검토했다. 앞의 방식은 유실 위험, 뒤의 두 방식은 hot loop와 retry policy 우회 위험 때문에 선택하지 않았다.

**Consequences.** 메시지 보존과 retry budget의 의미는 명확해지지만, 같은 장애가 10건 누적되면 prefetch가 포화되어 Consumer 가용성이 멈춘다. 별도 unacknowledged gauge와 포화 alert가 필요하며, 원인 해결 뒤 listener 또는 connection 재시작이 운영 절차다.

**Verification.** `mandatory return leaves manual delivery unacked and original redelivers after connection close`, `background container also preserves unconfirmed delivery without default DLX or tight requeue`, `ten unconfirmed failure republishes saturate prefetch then recover by binding repair and listener restart`.

### 6.2 ADR-2: exception message 대신 고정 오류 요약 저장

**Context.** 승인 설계는 DLQ 조사에 축약 오류 message를 제안했지만 실제 exception message에는 SQL, PII, credential 또는 payload 일부가 포함될 수 있다.

**Decision.** `x-exception-class`는 영숫자/underscore로 제한하고, `x-error-summary`는 `PERMANENT_MESSAGE`, `DATA_CONSISTENCY`, `TRANSIENT`, `UNKNOWN`별 고정 문구를 최대 120자로 기록한다. 원본 exception message와 stack trace는 header에 복사하지 않는다.

**Alternatives considered.** exception message를 길이만 잘라 저장하거나 전체 stack trace를 저장하는 방법은 진단 정보가 많지만 민감정보 유출 경계가 불명확해 제외했다.

**Consequences.** DLQ만으로 세부 원인을 모두 알 수 없어 eventId와 서버 로그·DB 상태를 함께 조사해야 한다. 반면 broker header와 애플리케이션 로그의 민감정보 노출 위험은 낮아진다.

**Verification.** `contract body original routing and first failure survive while last failure changes`, `permanent message goes directly to one DLQ without business rows`, `background container also preserves unconfirmed delivery without default DLX or tight requeue`.

### 6.3 ADR-3: queue sampler를 Publisher scheduler와 격리

**Context.** management API 지연 또는 한 queue 조회 실패가 Outbox 발행 주기와 다른 queue의 관측값을 함께 멈추면 장애 중 진단성이 더 나빠진다.

**Decision.** queue sampler와 Outbox Publisher에 서로 다른 단일-thread `ThreadPoolTaskScheduler`를 사용하고, 5개 queue를 개별 try/catch로 조회한다. 실패한 queue의 gauge만 `NaN`으로 만든다.

**Alternatives considered.** 공용 scheduler와 전체 refresh 단위 실패는 bean 수가 적지만 느린 management API가 publish를 지연시키고 부분 성공을 버린다.

**Consequences.** scheduler가 하나 늘어나지만 발행과 관측의 failure domain이 분리되고 부분 장애에서도 정상 queue 값이 남는다.

**Verification.** PR #67, `MessagingSchedulerIsolationTest`.`queue sampling and outbox publishing use distinct schedulers`, `RabbitMqQueueMetricsIntegrationTest`.`one unavailable queue does not hide healthy main and DLQ gauges`.

## 7. 구현 완료 체크리스트와 티켓 판정

| 기준 | 판정 | 근거 또는 남은 범위 |
| --- | --- | --- |
| MQ-T01: 전체 기준선과 시간 독립 fixture | 완료 | PR #59; 현재 전체 실행에서 반복된 restart 실패 관측은 아래 8절에 별도 기록 |
| MQ-T02: migration, target/version, DB 멱등 constraint, PII 없는 envelope | 완료 | V3/V4, migration·mapper·persistence tests |
| MQ-T03: durable topology, routing, TTL, confirm/return, PostgreSQL+RabbitMQ container, disabled context | 완료 | topology/config와 integration tests; HA는 명시적 제외 |
| MQ-T04: confirm 후 PUBLISHED, 실패 상태, terminal 제외, `SKIP LOCKED`, broker recovery, ambiguous duplicate | 완료 | Publisher unit/PostgreSQL/RabbitMQ tests |
| MQ-T05: 정상·duplicate·stale·rollback·ACK gap·Consumer stop/start | 완료 | handler/listener/E2E tests; 실제 provider 제외 |
| MQ-T06: typed retry, 3단계 TTL, permanent/DLQ, republish confirm, ID 보존, 무한 loop 금지 | 완료 | classifier/router/failure publisher와 retry integration tests |
| MQ-T07: correlation, metric, broker/Consumer recovery, terminal/DLQ 단건 runbook, latency/drain | 완료 | PR #66/#67, runbook, observability/recovery tests |
| 승인 설계: publish 성공 + 상태 update 실패 중복 | 완료 | `confirmed publish followed by database status failure can publish the same event id twice` |
| 승인 설계: commit + ACK 전 종료 | 완료 | 실제 raw channel connection close/redelivery test |
| 승인 설계: 실제 application JVM 전체 restart | 부분 구현 | durable broker restart와 Publisher/Consumer stop-start는 검증; 전체 JVM restart E2E 없음 |
| 승인 설계: 현재/미구현 범위를 README·architecture·면접 자료에서 일치 | 부분 구현 | 이 결과 문서와 README 링크는 추가; 보호 대상 MVP architecture 본문과 별도 면접 자료는 변경하지 않음 |
| MQ-T08: repository 결과 문서, 차이/ADR, 수치, 전체 결과 | 완료 | 이 문서와 8절 |
| MQ-T08: Notion 결과 페이지 및 Epic/T01~T08 backlink | 완료 | 새 결과 페이지를 만들고 9개 대상 페이지에 backlink를 추가한 뒤 모두 재조회해 검증 |

## 8. 검증 실행 결과

### 8.1 작업 시작 기준선

최신 `origin/develop`의 새 worktree에서 `./gradlew test --rerun-tasks`를 실행했다.

- tests: `418`
- failures: `1`
- errors: `0`
- skips: `0`
- 실패: `RabbitMqRestartIntegrationTest`.`durable production topology and persistent message survive the same broker restart` (`RabbitMqRestartIntegrationTest.kt:89`)

같은 class 단독 재실행은 `2 tests, failures/errors/skips 0`으로 통과했다. 이어 `RabbitMqRestartIntegrationTest`, `RabbitMqTopologyIntegrationTest`, `OutboxPublisherBrokerRecoveryIntegrationTest` 묶음은 `12 tests, failures/errors/skips 0`으로 통과했다. 이 관측은 full-suite의 Spring context/Testcontainer timing과 연관될 수 있지만, 한 번의 후속 성공만으로 단순 flake라고 확정하지 않는다. 이 기준선에서는 production/test 코드를 바꾸지 않았으며, 반복되면 별도 안정화 작업에서 원인을 다룬다.

### 8.2 완료 전 결과

- tests: `418`
- failures: `1`
- errors: `0`
- skips: `0`
- 실패: `RabbitMqRestartIntegrationTest`.`durable production topology and persistent message survive the same broker restart` (`RabbitMqRestartIntegrationTest.kt:89`, expected `1`, actual `0`)

작업 시작과 최초 완료 전의 두 full-suite 실행에서 같은 실패가 재현됐다. 반면 해당 class 단독 실행은 2/2, restart·topology·broker recovery 관련 묶음 실행은 12/12 통과했다. 따라서 최초 문서 변경의 회귀로 보지는 않지만 단순 일회성 flake로도 분류하지 않으며, full-suite 실행 순서·Spring context·Testcontainer timing을 포함한 별도 안정화 조사가 필요하다.

### 8.3 review 반영 후 결과

DLQ replay가 새 retry budget으로 실제 Consumer 처리까지 이어지도록 production code와 통합 테스트를 수정한 뒤 다시 실행했다.

- 대상 단일 replay 테스트: `1 test, failures/errors/skips 0`
- replay Consumer·queue metrics 관련 묶음: `6 tests, failures/errors/skips 0`
- 전체 suite: `418 tests, failures 1, errors 0, skips 0`
- 전체 suite 실패: 앞선 두 실행과 같은 `RabbitMqRestartIntegrationTest`.`durable production topology and persistent message survive the same broker restart` (`RabbitMqRestartIntegrationTest.kt:89`, expected `1`, actual `0`)

review 변경 대상인 replay 테스트는 전체 suite에서도 통과했다. restart 실패는 이로써 세 full-suite 실행에서 반복됐으며 별도 안정화 범위로 유지한다.

추가 완료 조건:

- `git diff --check`
- commit 전후 `git diff --cached --check`
- 보호 대상 `docs/01`~`08`의 `origin/develop` 대비 diff 없음
- 문서의 property, metric, topology, SQL 조건, 코드 경로와 테스트명 존재 확인

## 9. 현재 구현, 미구현과 주의사항

### 현재 구현

- 새 예약 확정 event의 Transactional Outbox와 at-least-once RabbitMQ 발행
- confirm/return 기반 Publisher 상태 전이와 제한 retry
- durable main/retry/DLQ topology와 manual-ACK Consumer
- PostgreSQL insert-first 멱등 처리와 `READY`/`SKIPPED_STALE` 결과 기록
- transient/unknown 제한 retry, permanent/data consistency 직접 DLQ
- Outbox·Consumer·queue metric, 포화/terminal/DLQ alert rule
- terminal Outbox와 DLQ 단건 수동 복구 절차

### 미구현 또는 후속 결정

- 실제 email/SMS/Push provider와 외부 전송
- provider idempotency 및 provider 호출/DB 사이 dual-write 해결
- 자동 DLQ replay, 일괄 replay, public replay/requeue API
- RabbitMQ cluster HA, quorum queue, Kubernetes 운영 검증
- Outbox `PUBLISHED` 및 `processed_events` retention/cleanup 정책
- 실제 애플리케이션 JVM 전체 재시작 E2E
- version 2 event/Consumer, 여러 Consumer, 순서가 필요한 projection
- production SLO·용량 시험·Alertmanager 통지
- Kafka, CDC/Debezium, Redis, XA/exactly-once

### 보안·운영상 주의사항

- MQ는 기본 비활성이므로 세 flag를 배포 역할에 맞게 명시해야 한다. `publisher-enabled` 또는 `listener-enabled`만 true로 두고 전체 `enabled=false`이면 구성은 활성화되지 않는다.
- 기본 `climbdesk/climbdesk` 자격 증명은 로컬 개발용이다. production에서는 secret manager와 최소 권한 virtual host/user를 사용한다.
- `/actuator/prometheus`를 public으로 열지 않고 bearer token 파일의 권한과 만료를 관리한다.
- payload, exception message, SQL, credential, PII를 header, metric label, 로그에 추가하지 않는다.
- terminal/DLQ 복구는 원인을 먼저 해결하고 eventId 한 건만 수행한다. 무조건적 일괄 requeue와 payload 추측 수정은 금지한다.
- unacked 포화는 데이터 보존을 위한 의도된 정지 상태일 수 있다. binding/connection 원인을 해결하지 않은 listener 반복 재시작은 redelivery만 반복시킨다.
- 단일-node durable restart와 로컬 측정값을 cluster HA 또는 production 성능 보장으로 표현하지 않는다.
