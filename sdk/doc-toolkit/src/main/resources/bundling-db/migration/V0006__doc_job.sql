-- The doc toolkit's jobs: one row per case and kept-up-to-date document (a case bundle), deleted
-- when the case no longer has the document. A newer case revision replaces its queued request,
-- and an unchanged request (same fingerprint) writes nothing. Rows go with their case; the check
-- waits for commit, so a job can be queued in the event creating the case.
create table bundling.doc_job (
    id                  uuid primary key,
    case_reference      bigint not null references ccd.case_data (reference) on delete cascade
                            deferrable initially deferred,
    -- The definition's key.
    job_key             text not null,
    -- The case revision the request was built at.
    revision            bigint not null,
    -- sha256 of the request as jsonb text.
    fingerprint         bytea not null,
    request_version     integer not null,
    request             jsonb not null,
    state               varchar(16) not null,
    attempts            integer not null default 0,
    next_attempt_at     timestamptz,
    -- Renders that used placeholders for documents only temporarily unavailable, re-run later.
    placeholder_retries integer not null default 0,
    lease_owner         text,
    lease_expires_at    timestamptz,
    failure_reason      varchar(64),
    failure_message     text,
    failure_documents   jsonb,
    submitted_at        timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (case_reference, job_key)
);

create index idx_doc_job_runnable on bundling.doc_job (next_attempt_at, submitted_at)
    where state = 'QUEUED';
create index idx_doc_job_leased on bundling.doc_job (lease_expires_at)
    where state = 'IN_PROGRESS';
