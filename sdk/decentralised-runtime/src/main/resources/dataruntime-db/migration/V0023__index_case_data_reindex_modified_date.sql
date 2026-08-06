create index concurrently idx_case_data_reindex_modified_date
    on ccd.case_data ((coalesce(last_modified, created_date)))
    include (reference, case_revision);
