alter table case_data
    alter column created_date set default (now() at time zone 'UTC');

alter table case_event
    alter column created_date set default (now() at time zone 'UTC');

-- This is now handled in the application layer
drop trigger trigger_update_last_state_modified_date on case_data;
drop function update_last_state_modified_date;
