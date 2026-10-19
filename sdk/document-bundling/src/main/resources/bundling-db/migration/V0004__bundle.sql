-- The bundles the job worker has rendered, one row per completed job: what each bundle contains,
-- where each document landed, which were replaced by placeholder pages and why, the warnings, and
-- the finished PDF's details. The PDF itself is stored by the consuming service wherever it keeps
-- documents (its completion handler); this table holds everything else, so the service's document
-- record stays an ordinary document. The worker writes the row in the same statement that marks
-- the job completed, so a row exists exactly when its job completed.
--
-- There is deliberately no foreign key to bundle_job: a bundle outlives the job that rendered it,
-- so job rows can be cleared down without losing the description of a bundle still in use.
create table bundling.bundle (
    id uuid primary key default gen_random_uuid(),
    -- The job that rendered the bundle. A consuming service links its stored document to this.
    job_id uuid not null unique,
    -- What the bundle is of: the job's coalesce key (for example a case and bundle kind), and the
    -- case reference from its execution context. Either can be null.
    bundle_key varchar(255),
    case_reference varchar(255),
    -- When the bundle's documents were selected: the claim of the attempt that rendered it. Of two
    -- bundles with the same key, the one selected later reflects the newer state.
    selected_at timestamptz not null,
    outcome varchar(32) not null,
    file_name text not null,
    media_type varchar(255) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    page_count integer not null,
    -- The request that was rendered: for a selector-driven job, the one the selector compiled.
    request jsonb not null,
    documents jsonb not null,
    missing_documents jsonb not null,
    warnings jsonb not null,
    created_at timestamptz not null default now()
);

create index idx_bundle_key_selected
    on bundling.bundle (bundle_key, selected_at desc)
    where bundle_key is not null;

-- Bundles already described by a job report.
insert into bundling.bundle (job_id, bundle_key, case_reference, selected_at, outcome, file_name,
    media_type, size_bytes, sha256, page_count, request, documents, missing_documents, warnings,
    created_at)
select external_id, coalesce_key, execution_context ->> 'caseReference',
    coalesce(claimed_at, created_at), report ->> 'outcome', report ->> 'fileName',
    'application/pdf', (report ->> 'size')::bigint, report ->> 'sha256',
    (report ->> 'pageCount')::integer, report -> 'request',
    coalesce(report -> 'documents', '[]'::jsonb),
    coalesce(report -> 'missingDocuments', '[]'::jsonb),
    coalesce(report -> 'warnings', '[]'::jsonb), updated_at
from bundling.bundle_job
where report is not null;

-- Kept so that an older worker still running during a rolling deploy can complete its jobs, but
-- no longer written: bundling.bundle replaces it.
comment on column bundling.bundle_job.report is
    'Superseded by bundling.bundle; no longer written.';
