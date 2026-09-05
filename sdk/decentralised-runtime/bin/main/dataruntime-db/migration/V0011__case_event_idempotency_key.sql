alter table ccd.case_event
    add column idempotency_key uuid;

update ccd.case_event
set idempotency_key = gen_random_uuid()
where idempotency_key is null;

alter table ccd.case_event
    alter column idempotency_key set not null;

alter table ccd.case_event
    add constraint case_event_idempotency_key_key unique (case_data_id, idempotency_key);

drop table ccd.completed_events;
