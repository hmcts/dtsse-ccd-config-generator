alter table ccd.ccd_data_migration_progress
add column source_event_hwm bigint not null default 0;
