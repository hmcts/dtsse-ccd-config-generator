create or replace function ccd.notify_es_queue_changed()
returns trigger as $$
begin
  perform pg_notify('ccd_es_queue_changed', '');
  return null;
end;
$$ language plpgsql;

create trigger trigger_notify_es_queue_changed
after insert or update of case_revision, enqueued_at on ccd.es_queue
for each statement
execute function ccd.notify_es_queue_changed();
