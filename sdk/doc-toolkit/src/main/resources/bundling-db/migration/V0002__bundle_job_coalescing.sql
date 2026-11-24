-- Coalescing for bundles that are regenerated whenever their inputs change. Submissions sharing a
-- coalesce key collapse onto the one job that is still waiting for its first claim, so a burst of
-- changes queues one render. A job that has been claimed (or is waiting to retry) no longer
-- absorbs submissions: a change arriving after its documents were selected needs a fresh job.
alter table bundling.bundle_job add column coalesce_key varchar(255);

-- How many later submissions this job absorbed, and when the last one arrived.
alter table bundling.bundle_job add column coalesced_submissions integer not null default 0;
alter table bundling.bundle_job add column last_coalesced_at timestamptz;

-- When the current attempt was claimed. A selector-driven job compiles its document list after
-- the claim, so ordering completed jobs by this column orders them by the case state they
-- reflect, whatever order they finished in.
alter table bundling.bundle_job add column claimed_at timestamptz;

-- The arbiter for coalescing upserts. Plain (non-concurrent) index builds are fine here: the
-- column is new, so every existing row is outside the predicate.
create unique index uq_bundle_job_waiting_coalesce_key
    on bundling.bundle_job (coalesce_key)
    where coalesce_key is not null and state = 'QUEUED' and attempts = 0;

-- Status lookups for a key (findLatest) across every job it has ever had.
create index idx_bundle_job_coalesce_key
    on bundling.bundle_job (coalesce_key, created_at desc)
    where coalesce_key is not null;
