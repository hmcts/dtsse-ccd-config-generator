-- The Strategic Data Platform extracts CCD event history by case type and
-- created-date window, ordered by creation date and event ID for paging.
create index concurrently idx_case_event_case_type_created_date_id
    on ccd.case_event (case_type_id, created_date, id);
