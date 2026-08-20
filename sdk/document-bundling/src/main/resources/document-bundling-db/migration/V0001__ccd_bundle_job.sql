-- The durable bundle job outbox: one row per submitted bundle job, keyed by the
-- consumer-minted external id (the idempotency key). See
-- docs/bundling-stitching/document-bundling-module-design.md, "Durable Job Runner: a
-- Transactional Outbox".
--
-- The row is the secondary status record for one job. It must NEVER contain bearer tokens,
-- service tokens, source document bytes, or signed URLs; the request, selector parameters,
-- execution context, failure, and result columns hold only non-secret, log-safe data.
--
-- Applied automatically on startup by BundleJobAutoConfiguration through a module-owned Flyway
-- instance with its own history table (never by adding this location to the consumer's Flyway,
-- whose version numbers would collide); consumers who manage the schema themselves set
-- ccd.bundling.job.auto-migrate=false and copy this file into their own migrations.
create table ccd_bundle_job (
    external_id uuid primary key,
    state varchar(32) not null default 'QUEUED',
    attempts integer not null default 0,
    -- The version of the persisted request JSON; a worker that cannot read it fails the job
    -- clearly with JOB_REQUEST_UNREADABLE rather than guessing.
    request_version integer not null,
    -- The version of the adapter/worker code that wrote the row.
    adapter_version varchar(64) not null,
    -- The full submitted bundle request; null when the consumer submitted only selector
    -- parameters and the request is compiled at execution time.
    request jsonb,
    selector_parameters jsonb not null default '{}'::jsonb,
    -- The non-secret consumer execution context.
    execution_context jsonb not null,
    lease_owner varchar(255),
    lease_expires_at timestamptz,
    -- When a retryable job becomes claimable again; null means immediately.
    next_attempt_at timestamptz,
    -- Every transient failure recorded across attempts, carried into the final failure when
    -- retries exhaust.
    transient_history jsonb not null default '[]'::jsonb,
    -- The sanitised failure, set only in the FAILED state.
    failure_code varchar(64),
    failure_message text,
    failure_documents jsonb,
    -- The published CcdBundle, set only in the completed states.
    result jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_ccd_bundle_job_queued
    on ccd_bundle_job (next_attempt_at, created_at) where state = 'QUEUED';
create index idx_ccd_bundle_job_leased
    on ccd_bundle_job (lease_expires_at) where lease_owner is not null;
