-- Keep the existing queue's exclusive DDL lock short and fail rather than wait indefinitely.
set local lock_timeout = '5s';

-- Constant default: existing rows get normal priority without rewriting the table.
alter table bundling.bundle_job add column priority smallint not null default 0;

-- Completion means queue population finished, not PDF rendering. Unfinished scans restart safely.
create table bundling.bundle_backfill (
    token varchar(128) not null,
    definition text not null,
    completed_at timestamptz not null default now(),
    primary key (token, definition)
);
