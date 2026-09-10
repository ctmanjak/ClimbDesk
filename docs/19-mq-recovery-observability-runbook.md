# MQ 장애 복구·관측 runbook

이 문서는 `ReservationConfirmedEvent`의 Publisher, RabbitMQ, `reservation-notification-v1` Consumer를 관측하고 terminal Outbox 또는 DLQ 메시지 한 건을 복구하는 절차다. 자동 복구 API나 일괄 replay를 제공하지 않는다.

## 1. 운영 경계

- 예약, 좌석, 이용권, 사용 이력, Outbox 저장은 기존 PostgreSQL transaction에 함께 commit된다. Broker나 Consumer 장애가 예약 API transaction을 rollback시키지 않는다.
- Publisher와 Consumer 전달은 at-least-once다. publish/confirm/Outbox commit 및 Consumer DB commit/ACK 사이에는 중복 가능성이 남는다.
- `(consumer_name, event_id)`와 `reservation_notification_requests.source_event_id` 제약이 중복 business effect를 한 건으로 흡수한다. delivery 자체가 exactly-once인 것은 아니다.
- retry/DLQ republish가 확인되지 않으면 원본은 unacked로 남는다. 데이터 보존을 우선하므로 단일 Consumer의 prefetch 10이 차면 소비가 멈출 수 있다. 원인을 해결한 뒤 listener 또는 connection을 재시작해야 redelivery된다.
- 단일 RabbitMQ Testcontainer 검증은 broker cluster HA나 quorum replication을 증명하지 않는다. 로컬 latency와 drain 측정값도 production 성능 보장이 아니다.
- 실제 email/SMS/Push provider, provider idempotency, provider 호출과 DB 사이 dual-write는 범위 밖이다.

## 2. 지표와 로그

`/actuator/prometheus`는 JWT 인증이 필요하다. `/actuator/health`만 public이며 management endpoint를 무인증 public으로 노출하지 않는다.

| Prometheus metric | 의미와 단위 | label | 갱신/집계 시점 |
| --- | --- | --- | --- |
| `climbdesk_messaging_outbox_events` | RabbitMQ 대상 미완료 Outbox 현재 건수(events) | `state=pending\|retry\|terminal` | scrape 때 PostgreSQL 조회. `publish_target=NONE` 제외. retry는 `FAILED`, `retry_count < max-attempts`, `next_retry_at is not null`; terminal은 그 외 재시도 불가능한 `FAILED` (`retry_count >= max-attempts` 또는 `next_retry_at is null`) |
| `climbdesk_messaging_outbox_oldest_unpublished_age_seconds` | RabbitMQ 대상 `status != PUBLISHED` 중 가장 오래된 `occurred_at`의 나이(seconds) | 없음 | scrape 때 조회. 없으면 0 |
| `climbdesk_messaging_outbox_publish_failures_total` | 실패 상태 저장까지 commit된 publish 시도(cumulative attempts) | 없음 | Outbox transaction commit 후 증가. 현재 backlog gauge가 아님 |
| `climbdesk_messaging_consumer_duplicates_total` | DB transaction이 duplicate no-op으로 commit된 delivery(cumulative results) | 없음 | handler transaction 반환 후 증가 |
| `climbdesk_messaging_consumer_failure_republish_confirmed_total` | mandatory return 없이 confirm ACK된 failure republish(cumulative results) | `destination=retry\|dlq` | confirm 판정 후 증가 |
| `climbdesk_messaging_consumer_failure_republish_failures_total` | confirm되지 않은 failure republish(cumulative attempts) | `destination=retry\|dlq` | failure publisher에서 한 번만 증가. container error handler는 집계하지 않음 |
| `climbdesk_messaging_rabbitmq_queue_depth` | ready + unacknowledged 현재 메시지(messages) | 고정 `queue=main\|retry_5s\|retry_30s\|retry_2m\|dlq` | RabbitMQ management API를 5초마다 조회 |
| `climbdesk_messaging_rabbitmq_queue_unacknowledged` | broker가 보고한 현재 unacked(messages) | 같은 고정 queue label | RabbitMQ management API를 5초마다 조회. 누적 실패 counter를 대신하지 않음 |
| `climbdesk_messaging_consumer_processing_latency_seconds` | event 발생부터 정상 business result commit까지(seconds) | 없음 | `PROCESSED` 반환 후 Timer 기록. rollback/duplicate 제외 |
| `climbdesk_messaging_rabbitmq_main_backlog_drain_seconds` | 관측된 main depth가 0보다 큰 시점부터 다음 0까지(seconds) | 없음 | management sampler가 non-empty→empty를 관측할 때 Timer 기록 |

DB 또는 management API 수집 실패는 예약/메시지 transaction 밖에서 처리한다. management API 실패는 해당 queue gauge만 `NaN`으로 만들고 다른 queue의 정상 수집값은 유지한다. queue sampler는 Outbox Publisher와 별도 scheduler에서 실행된다. payload, credential, SQL, exception message를 로그에 남기지 않으며 label에는 eventId, messageId, 사용자 ID, exception message가 없다.

Outbox 실패와 Consumer commit 로그는 검증된 숫자 eventId만 correlation 값으로 기록한다. 잘못된 messageId는 `unavailable`로 기록하고 payload, 이메일, 전화번호, 전체 nested stack trace를 기록하지 않는다.

## 3. 로컬 수집과 alert rule 평가

RabbitMQ management URL은 `CLIMBDESK_RABBITMQ_MANAGEMENT_BASE_URL`이며 기본값은 `http://localhost:15672`이다. 애플리케이션과 RabbitMQ를 실행하고 로그인 API에서 받은 JWT 문자열만 `.gitignore`된 파일에 저장한다.

```bash
mkdir -p ops/prometheus
chmod 700 ops/prometheus
printf '%s' "$CLIMBDESK_PROMETHEUS_BEARER_TOKEN" > ops/prometheus/climbdesk-token
chmod 600 ops/prometheus/climbdesk-token
docker compose -f compose.observability.yml up -d
```

Prometheus는 `127.0.0.1:9090`에만 bind되고 JWT로 보호된 `/actuator/prometheus`를 scrape한다. JWT 만료 시 token 파일을 갱신하고 Prometheus를 reload/restart한다.

prefetch 포화 경보는 main `messages_unacknowledged >= 10`이면서 지난 2분간 정상 처리 Timer count와 duplicate no-op count가 모두 증가하지 않은 상태가 추가 2분 지속될 때 firing된다. 10은 현재 단일 Consumer의 prefetch와 같고, 두 시간 조건은 정상적인 짧은 prefetch 사용을 즉시 장애로 오인하지 않기 위한 초기값이다. 실제 운영 측정 후 조정한다.

규칙 문법과 firing/non-firing fixture는 다음으로 평가한다.

```bash
docker run --rm \
  --entrypoint /bin/promtool \
  -v "$PWD/ops/prometheus:/work:ro" \
  -w /work \
  prom/prometheus:v3.5.0 \
  test rules climbdesk-alerts.test.yml
```

이 구성은 rule 평가까지만 제공한다. Alertmanager, Slack/email 같은 실제 외부 통지와 자동 listener 복구는 구성하지 않는다.

## 4. 장애 triage

1. `outbox_events`, oldest age, publish failure 증가를 함께 본다. 누적 counter만으로 현재 장애를 판단하지 않는다.
2. main/retry/DLQ depth와 main unacked를 확인한다. ready backlog와 unacked 포화를 구분한다.
3. eventId로 Outbox와 결과를 조회한다.

```sql
select id, event_type, publish_target, status, retry_count,
       occurred_at, published_at, next_retry_at, last_error
from outbox_events
where id = :event_id;

select consumer_name, event_id, event_type, processed_at
from processed_events
where consumer_name = 'reservation-notification-v1' and event_id = :event_id;

select source_event_id, reservation_id, member_id, notification_type, status, created_at
from reservation_notification_requests
where source_event_id = :event_id;
```

`PENDING`은 아직 시도 전이다. retry 가능한 `FAILED`는 `retry_count < max-attempts`이고 `next_retry_at is not null`이다. 이 조건을 만족하지 않아 현재 Publisher가 claim할 수 없는 `FAILED`는 terminal이다. 먼저 현재 배포의 `CLIMBDESK_RABBITMQ_PUBLISHER_MAX_ATTEMPTS` 값과 routing, credential, broker 연결, schema/code/data 원인을 확인한다.

## 5. Terminal Outbox 한 건 requeue

Publisher를 멈출 필요는 없다. terminal 행은 publish claim 대상이 아니며, 아래 transaction이 행을 잠근다. 반드시 eventId 한 건과 현재 terminal 조건을 함께 사용한다.

아래 `:max_publish_attempts`에는 현재 배포의 `CLIMBDESK_RABBITMQ_PUBLISHER_MAX_ATTEMPTS` 값을 사용한다. 설정 변경 전 생성된 행도 현재 claim 조건을 기준으로 판정한다.

```sql
begin;

select id, event_type, publish_target, status, retry_count, next_retry_at, last_error
from outbox_events
where id = :event_id
for update;

update outbox_events
set status = 'PENDING',
    retry_count = 0,
    next_retry_at = null,
    last_error = null,
    published_at = null,
    updated_at = now()
where id = :event_id
  and publish_target = 'RABBITMQ'
  and status = 'FAILED'
  and (retry_count >= :max_publish_attempts or next_retry_at is null);

-- psql은 UPDATE 1인지 확인한다. 0이면 rollback하고 상태를 다시 조사한다.
commit;
```

승인된 현재 Publisher 정책에 따라 원인이 해결된 terminal event에 새 retry budget을 부여하므로 `retry_count=0`으로 초기화한다. retry 대기 중인 행이나 여러 행을 일괄 초기화하지 않는다. commit 뒤 Outbox가 `PUBLISHED`가 되는지, queue가 drain되는지, processed/notification 결과가 각각 최대 한 건인지 확인한다. 이미 Consumer 처리된 event면 재발행될 수 있지만 DB 멱등성으로 business result는 추가되지 않아야 한다.

조작자, UTC 시각, eventId, 사전 상태/retryCount, 원인과 해결, 조건부 UPDATE row count, 사후 Outbox/queue/business 결과를 incident/ticket에 남긴다. 범용 DB audit table은 추가하지 않는다.

## 6. DLQ 한 건 조사와 replay

다음 도구는 DLQ 맨 앞 메시지 한 건만 다룬다. `inspect`는 body를 출력하지 않고 메시지를 ACK 없이 가져온 뒤 requeue한다.

```bash
CLIMBDESK_DLQ_REPLAY_ACTION=inspect ./gradlew dlqReplayOne
```

출력된 messageId/eventId, event type, schema version, failure category, retry count, original exchange/routing/queue, first/last failure 시각을 기록한다. 4절 SQL로 Reservation, Outbox, processed, notification 현재 상태를 확인하고 원인을 먼저 해결한다.

손상 payload를 추측해 고치거나 새 eventId를 발급하지 않는다. 계약 자체를 복구해야 하면 원본을 DLQ에 둔 채 별도 incident에서 producer contract와 신뢰 가능한 source data를 확인한다. 이 도구로 임의 payload를 생성하지 않는다.

재처리 시 운영자가 원인을 해결하고 한 건 replay를 명시적으로 확인한 것을 새 처리 시도로 간주해 `x-retry-count`를 `0`으로 초기화한다. original routing, first/last-failed-at, failure metadata와 나머지 body/properties는 그대로 유지한다. replay 뒤 실패가 `TRANSIENT` 또는 `UNKNOWN`으로 분류될 때만 현재 Consumer 정책의 5초→30초→2분 retry budget을 다시 사용한다. 계약 오류인 `PERMANENT_MESSAGE`나 예약 정합성 오류인 `DATA_CONSISTENCY`는 retry count와 관계없이 즉시 DLQ로 돌아간다.

```bash
export CLIMBDESK_DLQ_REPLAY_ACTION=replay
export CLIMBDESK_DLQ_REPLAY_EVENT_ID=701
export CLIMBDESK_DLQ_REPLAY_CONFIRM=701
./gradlew dlqReplayOne
```

도구는 DLQ head eventId가 요청값과 정확히 일치할 때만 같은 messageId/eventId와 body/properties로 `climbdesk.events` / `reservation.confirmed.v1`에 mandatory persistent republish하며, `x-retry-count`만 `0`으로 덮어쓴다. correlated publisher confirm ACK와 return 부재를 확인한 뒤에만 DLQ 원본을 ACK한다. NACK, timeout, return, connection 오류면 원본을 requeue한다. republish confirm과 DLQ ACK 사이 connection 손실에는 중복 가능성이 남으며 Consumer DB 멱등성이 이를 흡수한다.

완료 후 main/retry/DLQ depth, processed event 한 건, notification 최대 한 건과 상태를 확인하고 조작자·UTC 시각·eventId·원인·confirm 결과·DLQ ACK 결과를 incident/ticket에 기록한다.

## 7. stop-start 검증 결과 해석

- broker 중단: 예약 API와 예약/Outbox commit은 성공한다. Publisher 실패는 별도 transaction에서 `FAILED`와 다음 retry를 남긴다. broker 복구와 AMQP readiness 후 기존 policy가 같은 eventId를 publish하며 최종 processed/notification은 한 건이다.
- Consumer 중단: Publisher는 `PUBLISHED`까지 진행하고 main queue에 메시지가 보존된다. listener 재시작 후 queue가 drain되며 processed/notification은 한 건이다.
- republish destination binding 장애: confirm된 retry/DLQ counter는 증가하지 않고 failure counter 및 실제 main unacked가 증가한다. 자동 nack/requeue loop 없이 prefetch 10에서 멈춘다. binding 복구 뒤 listener/connection을 재시작하면 redelivery된다.

RabbitMQ readiness 검증은 테스트의 `RabbitTemplate.awaitAmqpReady(rabbitMq)`를 재사용한다. 최대 2분, 200ms polling이며 실패 시 마지막 AMQP 오류, container 상태, RabbitMQ 로그 마지막 200줄을 보고한다.
