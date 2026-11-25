-- The doc toolkit's jobs. A kept-up-to-date document (a case bundle) has one row per case and
-- key, never deleted while the case has it; a newer case revision replaces its queued request,
-- and an unchanged request (same fingerprint) writes nothing. A one-off job has no key and no
-- revision, and its row is deleted once the service's handler has it.
create table bundling.doc_job (
    id                  uuid primary key,
    case_reference      bigint not null,
    -- The definition's key; null for a one-off job.
    job_key             text,
    -- The case revision the request was built at; null for a one-off job.
    revision            bigint,
    -- sha256 of the request as jsonb text; null for a removal.
    fingerprint         bytea,
    request_version     integer not null,
    -- The request to render; null for a removal (the case no longer has the document).
    request             jsonb,
    state               varchar(16) not null,
    attempts            integer not null default 0,
    next_attempt_at     timestamptz,
    -- Renders that used placeholders for documents only temporarily unavailable, re-run later.
    placeholder_retries integer not null default 0,
    lease_owner         text,
    lease_expires_at    timestamptz,
    -- The stored document, while only the handler is left to run.
    result              jsonb,
    failure_reason      varchar(64),
    failure_message     text,
    failure_documents   jsonb,
    submitted_at        timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    -- Nulls are distinct, so any number of one-off jobs share a case.
    unique (case_reference, job_key)
);

create index idx_doc_job_runnable on bundling.doc_job (next_attempt_at, submitted_at)
    where state = 'QUEUED';
create index idx_doc_job_leased on bundling.doc_job (lease_expires_at)
    where state = 'IN_PROGRESS';
