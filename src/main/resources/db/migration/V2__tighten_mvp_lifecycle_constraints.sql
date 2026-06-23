alter table members
  drop constraint ck_members_deactivated_at,
  add constraint ck_members_deactivated_at check (
    (status = 'INACTIVE' and deactivated_at is not null)
    or (status = 'ACTIVE' and deactivated_at is null)
  );

update class_sessions
set cancel_reason = 'Migrated without cancel reason'
where status = 'CANCELED'
  and cancel_reason is null;

alter table class_sessions
  drop constraint ck_class_sessions_cancel_fields,
  add constraint ck_class_sessions_cancel_fields check (
    (status = 'CANCELED' and canceled_at is not null and cancel_reason is not null)
    or (status <> 'CANCELED' and canceled_at is null and cancel_reason is null)
  );

alter table outbox_events
  drop constraint ck_outbox_events_published_at,
  add constraint ck_outbox_events_published_at check (
    (status = 'PUBLISHED' and published_at is not null)
    or (status <> 'PUBLISHED' and published_at is null)
  );
