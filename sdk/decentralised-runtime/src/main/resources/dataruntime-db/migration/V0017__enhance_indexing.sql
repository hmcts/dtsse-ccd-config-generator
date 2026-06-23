create temporary table es_queue_rollup on commit drop as
select
  reference,
  max(case_revision) as case_revision,
  max(enqueued_at) as enqueued_at
from ccd.es_queue
group by reference;

truncate table ccd.es_queue;

insert into ccd.es_queue(reference, case_revision, enqueued_at)
select reference, case_revision, enqueued_at
from es_queue_rollup;

alter table ccd.es_queue
  drop constraint es_queue_pkey,
  add primary key (reference),
  add column locked_until timestamp with time zone,
  add column lock_token uuid;

create or replace function ccd.enqueue_case_revision()
returns trigger as $$
begin
  insert into ccd.es_queue(reference, case_revision)
  values (NEW.reference, NEW.case_revision)
  on conflict (reference) do update
  set
    case_revision = greatest(ccd.es_queue.case_revision, excluded.case_revision),
    enqueued_at = greatest(ccd.es_queue.enqueued_at, excluded.enqueued_at);

  return NEW;
end;
$$ language plpgsql;

create table ccd.es_dead_letter_queue (
  case_event_id bigint references ccd.case_event(id) on delete cascade,
  timestamp timestamp with time zone not null default now(),
  case_revision bigint not null,
  index_id text check (length(trim(index_id)) > 0),
  failure_message text,
  primary key (case_event_id, case_revision, index_id)
);
