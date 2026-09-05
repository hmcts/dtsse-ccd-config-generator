drop view ccd.failed_jobs;
drop table ccd.submitted_callback_queue;

-- We want cascading deletes on case related data.
alter table ccd.case_event drop constraint case_event_case_reference_fkey;
alter table ccd.case_event add constraint case_event_case_reference_fkey foreign key (case_reference) references case_data(reference) on delete cascade;

alter table ccd.case_event_audit drop constraint case_event_audit_case_event_id_fkey;
alter table ccd.case_event_audit add constraint case_event_audit_case_event_id_fkey foreign key (case_event_id) references ccd.case_event(id) on delete cascade;

alter table ccd.es_queue drop constraint es_queue_reference_fkey;
alter table ccd.es_queue add constraint es_queue_reference_fkey foreign key (reference) references ccd.case_data(reference) on delete cascade;

alter table ccd.es_queue drop constraint es_queue_id_fkey;
alter table ccd.es_queue add constraint es_queue_id_fkey foreign key (id) references ccd.case_event(id) on delete cascade;
