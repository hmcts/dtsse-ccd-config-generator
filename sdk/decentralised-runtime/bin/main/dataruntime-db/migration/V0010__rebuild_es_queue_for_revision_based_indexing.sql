-- Restructure the elasticsearch queue so that it is driven by case revisions

-- Remove the existing event-driven trigger & helper.
drop trigger after_case_event_insert on ccd.case_event;
drop function ccd.add_to_es_queue();

-- Replace the queue structure to track (reference, case_revision).
drop table ccd.es_queue;

create table ccd.es_queue (
    reference bigint not null references ccd.case_data(reference) on delete cascade,
    case_revision  bigint not null,
    enqueued_at    timestamp with time zone not null default now(),
    primary key(reference, case_revision)
);

-- Ensure case updates enqueue their new revision for indexing.
create function ccd.enqueue_case_revision()
returns trigger as $$
begin
    insert into ccd.es_queue(reference, case_revision)
    values (NEW.reference, NEW.case_revision)
    on conflict (reference, case_revision) do nothing;

    return NEW;
end;
$$ language plpgsql;

create trigger trigger_enqueue_case_revision
    after insert or update on ccd.case_data
    for each row
    execute function ccd.enqueue_case_revision();
