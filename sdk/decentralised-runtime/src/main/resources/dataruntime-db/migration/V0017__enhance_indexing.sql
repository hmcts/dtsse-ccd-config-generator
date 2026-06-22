
create table ccd.es_dead_letter_queue (
  event_id bigint primary key references ccd.case_event(id) on delete cascade,
  timestamp timestamp without time zone,
  failure_message text
);
