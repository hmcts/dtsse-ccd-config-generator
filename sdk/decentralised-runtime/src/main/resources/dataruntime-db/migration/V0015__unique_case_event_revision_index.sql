-- Enforce uniqueness on case revisions now that data has been backfilled.
create unique index concurrently if not exists idx_case_event_case_data_revision_unique
    on ccd.case_event (case_data_id, case_revision);
