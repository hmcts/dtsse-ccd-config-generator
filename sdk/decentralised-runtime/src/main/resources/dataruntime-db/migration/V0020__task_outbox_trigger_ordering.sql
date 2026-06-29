alter table ccd.task_outbox
    add column event_id varchar(70) not null,
    add constraint task_outbox_event_id_not_blank check (btrim(event_id) <> '');

create unique index idx_task_outbox_unique_trigger_action
    on ccd.task_outbox(case_id, event_id, created, requested_action);

create index idx_task_outbox_case_trigger_order
    on ccd.task_outbox(case_id, created, event_id, id);

create index idx_task_outbox_trigger_action_order
    on ccd.task_outbox(
        case_id,
        event_id,
        created,
        (
            case requested_action
                when 'complete'::ccd.task_action then 0
                when 'cancel'::ccd.task_action then 10
                when 'reconfigure'::ccd.task_action then 20
                when 'initiate'::ccd.task_action then 30
            end
        ),
        id
    );
