create or replace function ccd.notify_task_outbox_complete_finished()
returns trigger as $$
begin
    if new.requested_action = 'complete'::ccd.task_action
       and old.status = 'PROCESSING'::ccd.task_outbox_status
       and new.status in (
           'PROCESSED'::ccd.task_outbox_status,
           'FAILED'::ccd.task_outbox_status
       )
    then
        perform pg_notify(
            'task_outbox_complete_finished',
            json_build_object(
                'id', new.id,
                'case_id', new.case_id,
                'requested_action', new.requested_action,
                'old_status', old.status,
                'new_status', new.status,
                'attempt_count', new.attempt_count,
                'updated', new.updated
            )::text
        );
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_task_outbox_complete_finished
after update of status on ccd.task_outbox
for each row
execute function ccd.notify_task_outbox_complete_finished();
