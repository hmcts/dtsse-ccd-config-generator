-- One queue row per bundle: a definition's bundle of a case in ccd.case_data. A change to the
-- bundle's documents sets its row to QUEUED, even while a worker is rendering it, and a worker
-- that finishes leaves a row that was queued again to be rendered again. A case therefore has one
-- render of a bundle at a time, as it has one write to its search index at a time. This replaces
-- a row per submission, the coalesce key string, the versioned request and the selector parameter
-- and execution context JSON.
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
    -- When the bundle was last queued; the oldest is claimed first.
    add column queued_at timestamptz not null default now(),
    -- How many times this bundle has been re-run for temporarily unavailable documents.
    add column unavailable_requeues integer not null default 0,
    -- Live changes are 0; a bulk refresh queues at -1, so live changes are claimed first.
    add column priority smallint not null default 0,
    -- Dropping coalesce_key drops its indexes.
    drop column coalesce_key,
    drop column coalesced_submissions,
    drop column last_coalesced_at,
    drop column request,
    drop column request_version,
    drop column selector_parameters,
    drop column execution_context,
    -- The bundle row records where the PDF is stored.
    drop column result,
    -- A case's renders run one at a time, so the bundle recorded last is its current one.
    drop column claimed_at,
    add unique (case_reference, definition);

-- The priority index below serves the claim.
drop index bundling.idx_bundle_job_queued;

-- A completed bundle with next_attempt_at set is due a re-run for unavailable documents.
create index idx_bundle_job_priority
    on bundling.bundle_job (priority desc, queued_at, external_id)
    where state in ('QUEUED', 'IN_PROGRESS') or next_attempt_at is not null;

-- Dropping bundle_key and job_id drops their indexes. Deleting a case deletes its bundle rows; its
-- documents, the bundle PDFs among them, are disposed of with the case.
alter table bundling.bundle
    add column definition text not null,
    alter column case_reference type bigint using case_reference::bigint,
    alter column case_reference set not null,
    add foreign key (case_reference) references ccd.case_data (reference)
        on delete cascade deferrable initially deferred,
    drop column bundle_key,
    drop column job_id,
    drop column selected_at;
create index idx_bundle_case
    on bundling.bundle (case_reference, definition, created_at desc);

-- The ccd.bundling.refresh values that have already queued a refresh; each runs once.
create table bundling.refresh_key (
    key text primary key,
    created_at timestamptz not null default now()
);
