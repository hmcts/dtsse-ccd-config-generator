create type ccd.significant_item_type as enum ('DOCUMENT');

create table ccd.case_event_significant_items (
    id serial primary key,
    description varchar(64) not null,
    "type" ccd.significant_item_type not null,
    url text,
    case_event_id bigint not null
);

alter table ccd.case_event_significant_items
    add constraint fk_case_event_items_case_event_id
    foreign key (case_event_id)
    references ccd.case_event(id)
    on delete cascade;

create index idx_case_event_significant_items_case_event_id
    on ccd.case_event_significant_items(case_event_id);
