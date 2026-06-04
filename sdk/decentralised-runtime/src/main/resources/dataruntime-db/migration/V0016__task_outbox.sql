create type ccd.task_action AS ENUM ('cancel', 'complete', 'initiate', 'reconfigure');
create type ccd.task_outbox_status AS ENUM ('NEW', 'WAITING', 'PROCESSING', 'PROCESSED', 'FAILED');

create table ccd.task_outbox (
    id bigserial primary key,
    case_id bigint not null references ccd.case_data(reference) on delete cascade,
    payload jsonb not null,
    requested_action ccd.task_action not null,
    status ccd.task_outbox_status not null default 'NEW',
    attempt_count integer not null default 0,
    created timestamp not null default now(),
    updated timestamp not null default now(),
    next_attempt_at timestamp
);

create table ccd.task_outbox_history (
    id bigserial primary key,
    task_outbox_id bigint not null references ccd.task_outbox(id) on delete cascade,
    status ccd.task_outbox_status not null,
    response_code integer,
    error text,
    created timestamp not null default now()
);

create index idx_task_outbox_status_created on ccd.task_outbox(status, created);
create index idx_task_outbox_status_next_attempt on ccd.task_outbox(status, next_attempt_at);
create index idx_task_outbox_history_outbox_id on ccd.task_outbox_history(task_outbox_id, id desc);

create or replace function ccd.notify_task_outbox_complete_finished()
returns trigger as $$
begin
    if new.requested_action = 'complete'::ccd.task_action
       and old.status = 'PROCESSING'::ccd.task_outbox_status
       and new.status in (
           'PROCESSED'::ccd.task_outbox_status,
           'FAILED'::ccd.task_outbox_status
       )
    then
        perform pg_notify(
            'task_outbox_complete_finished',
            json_build_object(
                'id', new.id,
                'case_id', new.case_id,
                'requested_action', new.requested_action,
                'old_status', old.status,
                'new_status', new.status,
                'attempt_count', new.attempt_count,
                'updated', new.updated
            )::text
        );
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_task_outbox_complete_finished
after update of status on ccd.task_outbox
for each row
execute function ccd.notify_task_outbox_complete_finished();
