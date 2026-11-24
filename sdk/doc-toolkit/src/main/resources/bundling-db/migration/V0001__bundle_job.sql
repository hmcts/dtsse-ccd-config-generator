-- The durable bundle job outbox: one row per submitted bundle job, keyed by the consumer-minted
-- external id (the idempotency key). The row must never hold tokens, source bytes or signed URLs.
create table bundling.bundle_job (
    external_id uuid primary key,
    state varchar(32) not null default 'QUEUED',
    attempts integer not null default 0,
    -- The version of the persisted request JSON; an unreadable row fails JOB_REQUEST_UNREADABLE.
    request_version integer not null,
    -- The submitted request; null when only selector parameters were submitted.
    request jsonb,
    selector_parameters jsonb not null default '{}'::jsonb,
    execution_context jsonb not null,
    lease_owner varchar(255),
    lease_expires_at timestamptz,
    -- When a retryable job becomes claimable again; null means immediately.
    next_attempt_at timestamptz,
    transient_history jsonb not null default '[]'::jsonb,
    failure_code varchar(64),
    failure_message text,
    failure_documents jsonb,
    -- The completion handler's summary, set only in the completed states.
    result jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_bundle_job_queued
    on bundling.bundle_job (next_attempt_at, created_at) where state = 'QUEUED';
create index idx_bundle_job_leased
    on bundling.bundle_job (lease_expires_at) where lease_owner is not null;
