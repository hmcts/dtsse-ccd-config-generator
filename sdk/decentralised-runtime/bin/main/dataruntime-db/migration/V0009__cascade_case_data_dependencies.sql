-- Ensure CCD case data deletions cascade to dependent records in decentralised runtimes.

alter table if exists ccd.case_event
    drop constraint if exists case_event_case_data_id_fkey;

alter table if exists ccd.case_event
    add constraint case_event_case_data_id_fkey
        foreign key (case_data_id) references ccd.case_data(id) on delete cascade;
