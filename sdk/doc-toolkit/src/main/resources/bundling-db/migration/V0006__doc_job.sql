-- The doc toolkit's jobs. doc_job holds work not yet done, one row per case and document key; a
-- row is deleted when its document is handed to the service or given up on. doc_rendered holds the
-- fingerprint of the request last handed to the service, so an unchanged request is not rendered
-- again. Both go with their case.
create table bundling.doc_job (
    case_reference      bigint not null references ccd.case_data (reference) on delete cascade,
    -- The key the service submitted it under.
    job_key             text not null,
    -- The case revision the request was submitted at.
    case_revision       bigint not null,
    -- md5 of the request as jsonb text: change detection, not security.
    fingerprint         uuid not null,
    request             jsonb not null,
    attempts            integer not null default 0,
    next_attempt_at     timestamptz,
    lease_token         uuid,
    lease_expires_at    timestamptz,
    submitted_at        timestamptz not null default now(),
    primary key (case_reference, job_key)
);

create index idx_doc_job_due on bundling.doc_job (coalesce(next_attempt_at, submitted_at));

create table bundling.doc_rendered (
    case_reference      bigint not null references ccd.case_data (reference) on delete cascade,
    job_key             text not null,
    fingerprint         uuid not null,
    primary key (case_reference, job_key)
);
