-- The Client-Context a submission returned, so an idempotent replay of it can return the same context.
create table ccd.case_event_client_context (
    case_event_id bigint primary key references ccd.case_event(id) on delete cascade,
    client_context text not null
);
