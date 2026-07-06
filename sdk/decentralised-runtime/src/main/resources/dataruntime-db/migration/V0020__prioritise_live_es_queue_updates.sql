create or replace function ccd.enqueue_case_revision()
returns trigger as $$
begin
  insert into ccd.es_queue(reference, case_revision)
  values (NEW.reference, NEW.case_revision)
  on conflict (reference) do update
  set
    case_revision = greatest(ccd.es_queue.case_revision, excluded.case_revision),
    enqueued_at = least(ccd.es_queue.enqueued_at, excluded.enqueued_at);

  return NEW;
end;
$$ language plpgsql;
