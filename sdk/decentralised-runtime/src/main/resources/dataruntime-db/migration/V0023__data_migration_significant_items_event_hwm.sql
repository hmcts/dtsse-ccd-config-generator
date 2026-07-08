alter table ccd.ccd_data_migration_progress
add column significant_items_event_hwm bigint not null default 0;
