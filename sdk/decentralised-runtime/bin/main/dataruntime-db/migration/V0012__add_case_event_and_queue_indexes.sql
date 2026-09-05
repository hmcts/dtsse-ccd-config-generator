-- Efficient lookups of the newest event per case (history views and ES feed)
create index concurrently idx_case_event_case_data_id_id_desc
    on ccd.case_event (case_data_id, id desc);

-- Support fast polling and cleanup of the service bus outbox
create index concurrently idx_message_queue_candidates_type_pub_time
    on ccd.message_queue_candidates (message_type, published, time_stamp);

-- Keep ES queue drains ordered without scanning the whole table
create index concurrently idx_es_queue_enqueued_at
    on ccd.es_queue (enqueued_at);

-- Ensure cascades from case_event remain cheap as audit rows grow
create index concurrently idx_case_event_audit_case_event_id
    on ccd.case_event_audit (case_event_id);
