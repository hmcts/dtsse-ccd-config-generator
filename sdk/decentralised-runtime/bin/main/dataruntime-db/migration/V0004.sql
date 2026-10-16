create table ccd.message_queue_candidates (
      id bigserial primary key,
      reference bigint not null references case_data(reference) on delete cascade,
      message_type varchar(70) not null,
      time_stamp timestamp without time zone not null default now(),
      published timestamp without time zone,
      message_information jsonb not null
);

create index on ccd.message_queue_candidates (time_stamp);
create index on ccd.message_queue_candidates (reference);
