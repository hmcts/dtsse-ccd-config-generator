-- Ensure each case revision is recorded only once in the audit history.
create unique index concurrently idx_case_event_case_data_revision_unique
    on ccd.case_event (case_data_id, case_revision);
