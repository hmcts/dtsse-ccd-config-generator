create table ccd.ccd_data_migration_progress (
    task_name varchar(255) primary key,
    phase varchar(20) not null,
    window_start timestamp without time zone,
    window_end timestamp without time zone not null,
    last_case_data_modified timestamp without time zone,
    last_case_data_id bigint not null default 0,
    initial_complete boolean not null default false,
    total_batches bigint not null default 0,
    total_cases bigint not null default 0,
    total_events bigint not null default 0,
    created_at timestamp without time zone not null default (now() at time zone 'UTC'),
    updated_at timestamp without time zone not null default (now() at time zone 'UTC')
);
