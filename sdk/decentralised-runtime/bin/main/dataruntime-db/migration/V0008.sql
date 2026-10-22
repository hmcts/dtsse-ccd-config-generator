-- Drop existing trigger and function prior to renaming the column
drop trigger if exists trigger_increment_case_version on ccd.case_data;
drop function if exists ccd.increment_case_version();

alter table ccd.case_data
    rename column case_version to case_revision;

create or replace function ccd.increment_case_revision()
returns trigger as $$
begin
    -- Increment case_revision on any update
    new.case_revision := old.case_revision + 1;
    return new;
end;
$$ language plpgsql;

create trigger trigger_increment_case_revision
    before update on ccd.case_data
    for each row
    execute function ccd.increment_case_revision();
