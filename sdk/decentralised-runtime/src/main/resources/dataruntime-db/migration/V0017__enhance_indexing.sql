
create table ccd.es_dead_letter_queue (
  event_id bigint references ccd.case_event(id) on delete cascade,
  index_id text check (length(trim(index_id)) > 0),
  timestamp timestamp without time zone,
  failure_message text,
  primary key (event_id, index_id)
);
