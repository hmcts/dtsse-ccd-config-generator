-- The doc toolkit's jobs: one row per case and document key (a case bundle). A changed request
-- replaces the last one and moves the revision on; an unchanged request (same fingerprint) writes
-- nothing. Rows go with their case.
create table bundling.doc_job (
    id                  uuid primary key,
    case_reference      bigint not null references ccd.case_data (reference) on delete cascade,
    -- The key the service submitted it under.
    job_key             text not null,
    -- Moves on with each changed request, so a worker can tell its render was superseded.
    revision            bigint not null,
    -- The case's revision when the request was submitted, handed to the service with the result.
    case_revision       bigint not null,
    -- sha256 of the request as jsonb text.
    fingerprint         bytea not null,
    request_version     integer not null,
    request             jsonb not null,
    state               varchar(16) not null,
    attempts            integer not null default 0,
    next_attempt_at     timestamptz,
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
