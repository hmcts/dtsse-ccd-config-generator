-- Add an always-incrementing version column to the case data table
alter table ccd.case_data
add column case_version bigint not null default 1;

-- Backfill the new column with historical values
update ccd.case_data
set case_version = max_events.max_event_id
    from (
    select case_data_id, max(id) as max_event_id
    from ccd.case_event
    group by case_data_id
) as max_events
where ccd.case_data.id = max_events.case_data_id;

create or replace function ccd.increment_case_version()
returns trigger as $$
begin
    -- Increment case_version on any update
    new.case_version := old.case_version + 1;
    return new;
end;
$$ language plpgsql;

-- Create trigger to automatically increment case_version on updates
create trigger trigger_increment_case_version
    before update on ccd.case_data
    for each row
    execute function ccd.increment_case_version();
