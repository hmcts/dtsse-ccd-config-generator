alter table ccd.ccd_data_migration_progress
add column validated_event_hwm bigint not null default 0,
add column validated_significant_items_hwm bigint not null default 0;

update ccd.ccd_data_migration_progress
set validated_event_hwm = greatest(validated_event_hwm, source_event_hwm),
    validated_significant_items_hwm = greatest(validated_significant_items_hwm, significant_items_hwm),
    updated_at = now() at time zone 'UTC';
