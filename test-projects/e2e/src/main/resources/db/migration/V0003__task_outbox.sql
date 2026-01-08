create table task_outbox (
    id bigserial primary key,
    task_id text not null,
    case_id text not null,
    case_type_id text not null,
    payload jsonb not null,
    status text not null default 'NEW',
    attempt_count integer not null default 0,
    created timestamp not null default now(),
    updated timestamp not null default now(),
    processed timestamp,
    last_response_code integer,
    last_error text
);

create index idx_task_outbox_status_created on task_outbox(status, created);
create index idx_task_outbox_task_id on task_outbox(task_id);
