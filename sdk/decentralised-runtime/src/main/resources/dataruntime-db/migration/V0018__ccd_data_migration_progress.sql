create table ccd.ccd_data_migration_progress (
    task_name varchar(255) primary key,
    config_hash varchar(64) not null,
    status varchar(32) not null default 'PRELOAD',
    cutover_event_hwm bigint,
    created_at timestamp without time zone not null default (now() at time zone 'UTC'),
    updated_at timestamp without time zone not null default (now() at time zone 'UTC'),
    constraint ccd_data_migration_progress_status_chk
        check (status in ('PRELOAD', 'CUTOVER', 'COMPLETE'))
);
