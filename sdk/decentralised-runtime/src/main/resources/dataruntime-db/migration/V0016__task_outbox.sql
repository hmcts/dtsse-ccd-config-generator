create type ccd.task_action AS ENUM ('cancel', 'complete', 'initiate', 'reconfigure');

create table ccd.task_outbox (
    id bigserial primary key,
    case_id text not null,
    case_type_id text not null,
    payload jsonb not null,
    action ccd.task_action not null,
    status text not null default 'NEW',
    attempt_count integer not null default 0,
    created timestamp not null default now(),
    updated timestamp not null default now(),
    processed timestamp,
    next_attempt_at timestamp,
    last_response_code integer,
    last_error text
);

create index idx_task_outbox_status_created on ccd.task_outbox(status, created);
create index idx_task_outbox_status_next_attempt on ccd.task_outbox(status, next_attempt_at);
