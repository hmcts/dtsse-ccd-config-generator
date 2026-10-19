-- IDAM user ids are opaque strings; legacy accounts have short numeric ids rather than UUIDs.
-- Match case_event.user_id (varchar(64)) instead of requiring a UUID.
alter table ccd.case_event_audit alter column user_id type character varying(64) using user_id::text;

create or replace function ccd.audit_case_event_changes()
  returns trigger as $$
begin
insert into ccd.case_event_audit(case_event_id, user_id, data)
select new.id, current_setting('ccd.user_idam_id'), new.data;
return new;
end;
$$ language plpgsql;
