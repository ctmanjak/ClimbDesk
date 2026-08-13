-- Recovery after a failed build: drop the invalid index concurrently,
-- run Flyway repair, then rerun migrations.
create index concurrently idx_outbox_events_publishable
  on outbox_events (status, next_retry_at asc nulls first, id asc)
  where publish_target = 'RABBITMQ'
    and status in ('PENDING', 'FAILED');
