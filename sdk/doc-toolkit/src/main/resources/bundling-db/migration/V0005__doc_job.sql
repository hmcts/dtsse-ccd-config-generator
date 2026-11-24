-- The doc toolkit's job outbox: the full request is queued, the worker renders it, stores the PDF
-- in CDAM and hands the stored document to the service. Managed bundles are gone, so their table
-- and the coalescing columns go too.

-- Jobs queued in the old format cannot be read by the new worker.
update bundling.bundle_job
set state = 'FAILED', failure_code = 'UNREADABLE_REQUEST',
    failure_message = 'Queued before the doc toolkit replaced document-bundling; submit it again.',
    lease_owner = null, lease_expires_at = null, next_attempt_at = null, updated_at = now()
where state in ('QUEUED', 'IN_PROGRESS');

drop table bundling.bundle;

alter table bundling.bundle_job
    drop column coalesce_key,
    drop column coalesced_submissions,
    drop column last_coalesced_at,
    drop column selector_parameters,
    drop column execution_context,
    add column case_id bigint,
    add column event_id varchar(255);
