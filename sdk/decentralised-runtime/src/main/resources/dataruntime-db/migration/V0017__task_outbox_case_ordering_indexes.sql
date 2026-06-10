create index idx_task_outbox_case_id_id on ccd.task_outbox(case_id, id);
create index idx_task_outbox_status_next_attempt_attempt_id
    on ccd.task_outbox(status, next_attempt_at, attempt_count, id);
