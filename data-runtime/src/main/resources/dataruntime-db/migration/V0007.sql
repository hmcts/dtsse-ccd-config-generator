-- Add an always-incrementing version column to the case data table
alter table ccd.case_data
add column case_version bigint not null default 1;

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
