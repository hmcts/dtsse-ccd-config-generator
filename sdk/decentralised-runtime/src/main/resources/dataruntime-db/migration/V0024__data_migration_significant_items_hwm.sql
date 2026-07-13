alter table ccd.ccd_data_migration_progress
add column significant_items_event_hwm bigint not null default 0;

update ccd.ccd_data_migration_progress
set source_event_hwm = greatest(source_event_hwm, cutover_event_hwm),
    significant_items_event_hwm = cutover_event_hwm,
    updated_at = now() at time zone 'UTC'
where status = 'COMPLETE'
  and cutover_event_hwm is not null
  and (
    source_event_hwm = 0
    or significant_items_event_hwm = 0
  );
