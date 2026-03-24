create type ccd.task_action AS ENUM ('cancel', 'complete', 'initiate', 'reconfigure');

create table ccd.task_outbox (
    id bigserial primary key,
    case_id bigint not null references ccd.case_data(reference) on delete cascade,
    payload jsonb not null,
    action ccd.task_action not null,
    status text not null default 'NEW',
    attempt_count integer not null default 0,
    created timestamp not null default now(),
    updated timestamp not null default now(),
    next_attempt_at timestamp
);

create table ccd.task_outbox_history (
    id bigserial primary key,
    task_outbox_id bigint not null references ccd.task_outbox(id) on delete cascade,
    status text not null,
    response_code integer,
    error text,
    created timestamp not null default now()
);

create index idx_task_outbox_status_created on ccd.task_outbox(status, created);
create index idx_task_outbox_status_next_attempt on ccd.task_outbox(status, next_attempt_at);
create index idx_task_outbox_history_outbox_id on ccd.task_outbox_history(task_outbox_id, id desc);
