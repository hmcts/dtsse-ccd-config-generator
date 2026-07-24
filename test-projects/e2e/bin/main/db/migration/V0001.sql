create table case_notes(
   reference bigint references ccd.case_data(reference) ,
   id bigserial,
   timestamp timestamp not null default now(),
   note varchar(10000) not null,
   author varchar(200) not null,
   primary key(reference, id)
);

insert into case_notes(reference, id, timestamp, note, author)
select
  reference,
  (note->>'id')::bigint,
  (note->'value'->>'date')::timestamp,
  note->'value'->>'note',
  note->'value'->>'author'
from
  ccd.case_data,
  jsonb_array_elements(data->'notes') note;


create view notes_by_case as
select
reference,
jsonb_agg(
  json_build_object(
    'value', jsonb_build_object(
      'timestamp', timestamp,
      'note', note,
      'author', author
    )
  -- Ensure most recent case notes are first
  ) order by id desc
) notes from case_notes
group by reference;
