CREATE TABLE bundling.case_bundle (
    definition TEXT NOT NULL,
    case_key TEXT NOT NULL,
    requested_revision BIGINT NOT NULL DEFAULT 0,
    published_revision BIGINT NOT NULL DEFAULT 0,
    job_id UUID,
    document JSONB,
    report JSONB,
    published_at TIMESTAMPTZ,
    PRIMARY KEY (definition, case_key)
);

CREATE TABLE bundling.bundle_selection (
    job_id UUID NOT NULL REFERENCES bundling.bundle_job(external_id),
    attempt INTEGER NOT NULL,
    definition TEXT NOT NULL,
    case_key TEXT NOT NULL,
    revision BIGINT NOT NULL,
    selection JSONB NOT NULL,
    PRIMARY KEY (job_id, attempt)
);

-- Every returned upload is journalled before attachment, even when its worker lost the lease.
CREATE TABLE bundling.bundle_artifact (
    document_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES bundling.bundle_job(external_id),
    attempt INTEGER NOT NULL,
    document JSONB NOT NULL,
    report JSONB NOT NULL,
    attached BOOLEAN NOT NULL DEFAULT FALSE,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (job_id, attempt) REFERENCES bundling.bundle_selection(job_id, attempt)
);
CREATE INDEX ix_bundle_artifact_job ON bundling.bundle_artifact(job_id);

CREATE TABLE bundling.bundle_cleanup (
    document_id UUID PRIMARY KEY,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
