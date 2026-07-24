alter table case_notes
    drop constraint if exists case_notes_reference_fkey;

alter table case_notes
    add constraint case_notes_reference_fkey
        foreign key (reference) references ccd.case_data(reference) on delete cascade;
