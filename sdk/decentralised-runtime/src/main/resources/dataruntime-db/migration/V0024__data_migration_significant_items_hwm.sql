alter table ccd.ccd_data_migration_progress
add column significant_items_hwm bigint not null default 0;

update ccd.ccd_data_migration_progress
set source_event_hwm = greatest(source_event_hwm, cutover_event_hwm),
    significant_items_hwm = coalesce((
        select max(item.id)
        from ccd.case_event_significant_items item
        join ccd.case_event ce on ce.id = item.case_event_id
        where ce.id <= ccd.ccd_data_migration_progress.cutover_event_hwm
    ), 0),
    updated_at = now() at time zone 'UTC'
where status = 'COMPLETE'
  and cutover_event_hwm is not null
  and (
    source_event_hwm = 0
    or significant_items_hwm = 0
  );
