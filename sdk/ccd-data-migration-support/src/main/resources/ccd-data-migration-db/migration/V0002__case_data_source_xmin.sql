-- The source row version (xmin) each migrated case_data row was last copied from. Postgres gives
-- a row a new xmin on every update, however it is made, so a case whose source xmin no longer
-- matches has changed since it was copied.
create table ccd_data_migration.case_data_source_xmin (
    case_data_id bigint primary key,
    source_xmin bigint not null
);
