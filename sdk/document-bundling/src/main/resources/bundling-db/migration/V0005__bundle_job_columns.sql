-- One queue row per bundle: a definition's bundle of a case in ccd.case_data. A change to the
-- bundle's documents sets its row to QUEUED, even while a worker is rendering it, and a worker
-- that finishes leaves a row that was queued again to be rendered again. A case therefore has one
-- render of a bundle at a time, as it has one write to its search index at a time. This replaces
-- a row per submission keyed by a string, with its request and context held as JSON.
set local lock_timeout = '5s';

-- Earlier rows were keyed by strings that do not name a case.
drop table bundling.bundle_job, bundling.bundle;

create table bundling.bundle_job (
    -- Checked at commit: an event that creates a case runs its handler before the case row is
    -- written.
    case_reference bigint not null
        references ccd.case_data (reference) on delete cascade deferrable initially deferred,
    definition text not null,
    -- QUEUED, IN_PROGRESS, COMPLETED or FAILED.
    state varchar(32) not null default 'QUEUED',
    -- Live changes are 0; a bulk refresh queues at -1, so live changes are claimed first.
    priority smallint not null default 0,
    -- When the bundle was last queued; the oldest is claimed first.
    queued_at timestamptz not null default now(),
    attempts integer not null default 0,
    -- When a retry, or a completed bundle's re-run for unavailable documents, becomes claimable.
    next_attempt_at timestamptz,
    -- How many times in a row the bundle has been re-run for temporarily unavailable documents.
    unavailable_requeues integer not null default 0,
    lease_owner varchar(255),
    lease_expires_at timestamptz,
    failure_code varchar(64),
    failure_message text,
    failure_documents jsonb,
    primary key (case_reference, definition)
);

create index idx_bundle_job_priority
    on bundling.bundle_job (priority desc, queued_at)
    where state in ('QUEUED', 'IN_PROGRESS') or next_attempt_at is not null;

-- What a render produced: everything about a bundle except its PDF, which is stored as an
-- ordinary document. The row recorded last for a case is its current bundle; a replaced row is
-- deleted with its PDF. Deleting a case deletes its rows; its documents, the bundle PDFs among
-- them, are disposed of with the case.
create table bundling.bundle (
    id uuid primary key default gen_random_uuid(),
    case_reference bigint not null references ccd.case_data (reference) on delete cascade,
    definition text not null,
    outcome varchar(32) not null,
    file_name text not null,
    media_type varchar(255) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    page_count integer not null,
    request jsonb not null,
    documents jsonb not null,
    missing_documents jsonb not null,
    warnings jsonb not null,
    document_id uuid not null,
    document_url text not null,
    binary_url text not null,
    created_at timestamptz not null default now()
);

create index idx_bundle_case
    on bundling.bundle (case_reference, definition, created_at desc);

-- The ccd.bundling.refresh values that have already queued a refresh; each runs once.
create table bundling.refresh_key (
    key text primary key,
    created_at timestamptz not null default now()
);
