alter table ccd.task_outbox
    add column available_at timestamp,
    add column claim_token uuid,
    add column lease_until timestamp;

alter table ccd.task_outbox
    alter column status drop default;

update ccd.task_outbox
set available_at = case
        when status in (
            'WAITING'::ccd.task_outbox_status,
            'FAILED'::ccd.task_outbox_status
        )
            then coalesce(next_attempt_at, current_timestamp at time zone 'UTC')
        when status in (
            'NEW'::ccd.task_outbox_status,
            'PROCESSING'::ccd.task_outbox_status
        )
            then current_timestamp at time zone 'UTC'
        else null
    end,
    attempt_count = attempt_count + case
        when status in (
            'PROCESSING'::ccd.task_outbox_status,
            'PROCESSED'::ccd.task_outbox_status
        )
            then 1
        else 0
    end,
    status = case
        when status in (
            'NEW'::ccd.task_outbox_status,
            'WAITING'::ccd.task_outbox_status,
            'PROCESSING'::ccd.task_outbox_status,
            'FAILED'::ccd.task_outbox_status
        )
            then 'PENDING'::ccd.task_outbox_status
        else status
    end;

update ccd.task_outbox_history
set status = 'PENDING'::ccd.task_outbox_status
where status = 'FAILED'::ccd.task_outbox_status;

drop index if exists ccd.idx_task_outbox_status_next_attempt;
drop index if exists ccd.idx_task_outbox_status_next_attempt_attempt_id;

alter table ccd.task_outbox
    drop column next_attempt_at,
    alter column status set default 'PENDING'::ccd.task_outbox_status,
    alter column available_at set default (current_timestamp at time zone 'UTC'),
    add constraint task_outbox_attempt_count_non_negative check (attempt_count >= 0),
    add constraint task_outbox_status_shape check (
        (
            status = 'PENDING'::ccd.task_outbox_status
            and available_at is not null
            and claim_token is null
            and lease_until is null
        )
        or (
            status = 'PROCESSING'::ccd.task_outbox_status
            and available_at is null
            and claim_token is not null
            and lease_until is not null
        )
        or (
            status in (
                'PROCESSED'::ccd.task_outbox_status,
                'UNPROCESSABLE'::ccd.task_outbox_status
            )
            and available_at is null
            and claim_token is null
            and lease_until is null
        )
    );

create index idx_task_outbox_pending_available_attempt_id
    on ccd.task_outbox(available_at, attempt_count, id)
    where status = 'PENDING'::ccd.task_outbox_status;

create index idx_task_outbox_processing_lease_attempt_id
    on ccd.task_outbox(lease_until, attempt_count, id)
    where status = 'PROCESSING'::ccd.task_outbox_status;
