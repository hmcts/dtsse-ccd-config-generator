-- Keep the existing queue's exclusive DDL lock short and fail rather than wait indefinitely.
set local lock_timeout = '5s';

-- Constant default: existing rows get normal priority without rewriting the table.
alter table bundling.bundle_job add column priority smallint not null default 0;

-- One row per refresh key and definition whose cases were queued, committed with those jobs.
create table bundling.bundle_refresh (
    refresh_key text not null,
    definition text not null,
    queued_at timestamptz not null default now(),
    primary key (refresh_key, definition)
);
