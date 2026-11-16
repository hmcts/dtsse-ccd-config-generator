-- Normal changes take precedence over background backfill. Existing jobs keep normal priority.
alter table bundling.bundle_job add column priority smallint not null default 0;
create index idx_bundle_job_priority
    on bundling.bundle_job (priority desc, created_at, external_id)
    where state in ('QUEUED', 'IN_PROGRESS');
