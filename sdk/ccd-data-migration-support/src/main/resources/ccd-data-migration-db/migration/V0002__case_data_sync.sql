-- The row versions (xmin) of each migrated case when it was last copied: the source row it was
-- copied from, and the target row as written. Postgres gives a row a new xmin on every update,
-- however it is made, so a case is copied again when either side no longer matches.
create table ccd_data_migration.case_data_copy_versions (
    case_data_id bigint primary key,
    source_xmin bigint not null,
    target_xmin bigint not null
);

-- Cases the last scan found changed and the case_data sync has not copied yet. Kept in a table
-- rather than in memory so a large first sync is not limited by heap size, and a run stopped by
-- its time limit can carry on from here without scanning the source first. Each task has its own
-- queue, so tasks for other case types migrating into the same database cannot clear it.
create table ccd_data_migration.case_data_sync_queue (
    task_name varchar(255) not null,
    case_data_id bigint not null,
    primary key (task_name, case_data_id)
);
