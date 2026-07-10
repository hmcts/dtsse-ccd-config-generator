create type ccd.significant_item_type as enum ('DOCUMENT');

create table ccd.case_event_significant_items (
    id bigserial primary key,
    case_event_id bigint not null references ccd.case_event(id) on delete cascade,
    "type" ccd.significant_item_type not null,
    description varchar(64) not null,
    url text
);

create index idx_case_event_significant_items_case_event_id
    on ccd.case_event_significant_items(case_event_id);
