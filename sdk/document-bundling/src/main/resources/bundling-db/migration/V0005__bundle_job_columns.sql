-- A job and a bundle belong to a case in ccd.case_data and are identified by their definition and
-- case reference in columns, replacing the coalesce key string, the versioned request and the
-- selector parameter and execution context JSON.
set local lock_timeout = '5s';

-- Earlier rows were keyed by strings that do not name a case.
truncate bundling.bundle_job, bundling.bundle;

alter table bundling.bundle_job
    alter column external_id set default gen_random_uuid(),
    add column definition text not null,
    -- Checked at commit: an event that creates a case runs its handler before the case row is
    -- written.
    add column case_reference bigint not null
        references ccd.case_data (reference) on delete cascade deferrable initially deferred,
    -- How many times this case's bundle has been re-run for temporarily unavailable documents.
    add column unavailable_requeues integer not null default 0,
    -- Live changes are 0; a bulk refresh queues at -1, so live changes are claimed first.
    add column priority smallint not null default 0,
    -- Dropping coalesce_key drops its indexes.
    drop column coalesce_key,
    drop column request,
    drop column request_version,
    drop column selector_parameters,
    drop column execution_context,
    -- The bundle row records where the PDF is stored.
    drop column result;

-- The priority index below serves the claim.
drop index bundling.idx_bundle_job_queued;

-- At most one job per case is waiting for its first claim; a change joins it.
create unique index uq_bundle_job_waiting
    on bundling.bundle_job (definition, case_reference) where state = 'QUEUED' and attempts = 0;
create index idx_bundle_job_case
    on bundling.bundle_job (case_reference, definition, created_at desc);
create index idx_bundle_job_priority
    on bundling.bundle_job (priority desc, created_at, external_id)
    where state in ('QUEUED', 'IN_PROGRESS');

-- Dropping bundle_key drops its index. Deleting a case deletes its bundle rows; its documents,
-- the bundle PDFs among them, are disposed of with the case.
alter table bundling.bundle
    add column definition text not null,
    alter column case_reference type bigint using case_reference::bigint,
    alter column case_reference set not null,
    add foreign key (case_reference) references ccd.case_data (reference)
        on delete cascade deferrable initially deferred,
    drop column bundle_key;
create index idx_bundle_case_selected
    on bundling.bundle (case_reference, definition, selected_at desc);

-- The ccd.bundling.refresh values that have already queued a refresh; each runs once.
create table bundling.refresh_key (
    key text primary key,
    created_at timestamptz not null default now()
);
