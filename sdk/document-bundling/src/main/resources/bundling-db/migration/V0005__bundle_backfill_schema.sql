-- Keep the existing queue's exclusive DDL lock short and fail rather than wait indefinitely.
set local lock_timeout = '5s';

-- Constant default: existing rows get normal priority without rewriting the table.
alter table bundling.bundle_job add column priority smallint not null default 0;

-- One scan per trigger token and definition: how far it has queued, and when it finished.
-- Completion means queue population finished, not PDF rendering.
create table bundling.bundle_backfill (
    token varchar(128) not null,
    definition text not null,
    -- The last case key queued; null before the first page.
    after_key text,
    completed_at timestamptz,
    primary key (token, definition)
);
