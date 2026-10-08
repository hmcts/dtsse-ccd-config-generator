-- Find each case's earliest unpublished message for in-order publishing
create index concurrently idx_message_queue_candidates_unpublished_type_ref_id
    on ccd.message_queue_candidates (message_type, reference, id)
    where published is null;
