-- The queue serves managed case bundles only. A job is identified by its definition and case key
-- and records the case it rendered for in columns, replacing the coalesce key string, the
-- versioned request and the selector parameter and execution context JSON.
set local lock_timeout = '5s';

-- Anything that was not a managed case bundle has no definition or case key.
delete from bundling.bundle where bundle_key is null or bundle_key not like 'managed:%:%';
delete from bundling.bundle_job where coalesce_key is null or coalesce_key not like 'managed:%:%';

alter table bundling.bundle_job
    add column definition text,
    add column case_key text,
    -- The case the selection was for, recorded when the job's documents are selected.
    add column case_reference bigint,
    add column case_type text,
    add column jurisdiction text,
    -- How many times this case's bundle has been re-run for temporarily unavailable documents.
    add column unavailable_requeues integer not null default 0,
    -- Live changes are 0; a bulk refresh queues at -1, so live changes are claimed first.
    add column priority smallint not null default 0;

-- Keys were 'managed:<definition>:<case key>'; a definition name never contains a colon.
update bundling.bundle_job set
    definition = split_part(coalesce_key, ':', 2),
    case_key = substr(coalesce_key, length(split_part(coalesce_key, ':', 2)) + 10),
    case_reference = (execution_context ->> 'caseReference')::bigint,
    case_type = execution_context ->> 'bundling.caseType',
    jurisdiction = execution_context ->> 'bundling.jurisdiction',
    unavailable_requeues =
        coalesce((execution_context ->> 'bundling.unavailableRequeues')::integer, 0);

-- Dropping coalesce_key drops its indexes.
alter table bundling.bundle_job
    alter column definition set not null,
    alter column case_key set not null,
    drop column coalesce_key,
    drop column request,
    drop column request_version,
    drop column selector_parameters,
    drop column execution_context;

-- At most one job per case is waiting for its first claim; a change joins it.
create unique index uq_bundle_job_waiting
    on bundling.bundle_job (definition, case_key) where state = 'QUEUED' and attempts = 0;
create index idx_bundle_job_case on bundling.bundle_job (definition, case_key, created_at desc);
create index idx_bundle_job_priority
    on bundling.bundle_job (priority desc, created_at, external_id)
    where state in ('QUEUED', 'IN_PROGRESS');

alter table bundling.bundle add column definition text, add column case_key text;
update bundling.bundle set
    definition = split_part(bundle_key, ':', 2),
    case_key = substr(bundle_key, length(split_part(bundle_key, ':', 2)) + 10);
alter table bundling.bundle
    alter column definition set not null,
    alter column case_key set not null,
    drop column bundle_key;
create index idx_bundle_case_selected on bundling.bundle (definition, case_key, selected_at desc);

-- Public SQL API, for an application migration: queues a background re-render of a definition's
-- bundle for each case key, in the caller's transaction, and returns the jobs queued. A case with
-- a job already waiting is left to that job.
create function bundling.refresh(p_definition text, p_case_keys text[]) returns integer
language sql as $$
  with queued as (
    insert into bundling.bundle_job (external_id, definition, case_key, priority)
    select gen_random_uuid(), p_definition, case_key, -1
    from (select distinct unnest(p_case_keys) as case_key) keys
    where case_key is not null
    on conflict (definition, case_key) where state = 'QUEUED' and attempts = 0
    do nothing
    returning 1)
  select count(*)::integer from queued
$$;
