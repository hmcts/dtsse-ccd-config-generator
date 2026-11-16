-- Separate non-transactional migration: build without blocking queue inserts or updates.
create index concurrently idx_bundle_job_priority
    on bundling.bundle_job (priority desc, created_at, external_id)
    where state in ('QUEUED', 'IN_PROGRESS');
