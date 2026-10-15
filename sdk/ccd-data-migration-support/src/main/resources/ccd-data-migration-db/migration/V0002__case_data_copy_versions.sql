-- The row versions (xmin) of each migrated case when it was last copied: the source row it was
-- copied from, and the target row as written. Postgres gives a row a new xmin on every update,
-- however it is made, so a case is copied again when either side no longer matches.
create table ccd_data_migration.case_data_copy_versions (
    case_data_id bigint primary key,
    source_xmin bigint not null,
    target_xmin bigint not null
);
