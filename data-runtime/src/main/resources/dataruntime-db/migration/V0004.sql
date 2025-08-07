-- Nobody is yet using this table.
truncate table ccd.message_queue_candidates;
alter table ccd.message_queue_candidates
    add column reference bigint not null,
add constraint fk_message_queue_candidates_reference
foreign key (reference) references case_data(reference) on delete cascade;

create index on ccd.message_queue_candidates (reference);
