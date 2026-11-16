-- A row means queue population finished for this trigger and definition, not PDF rendering.
-- No running state or durable cursor: unfinished scans restart safely through queue deduplication.
create table bundling.bundle_backfill (
    token varchar(128) not null,
    definition text not null,
    completed_at timestamptz not null default now(),
    primary key (token, definition)
);
