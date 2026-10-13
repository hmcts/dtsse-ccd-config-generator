create table public.sdk_migration_order_check as
select
    to_regclass('ccd.case_data') is not null as runtime_migrated,
    to_regclass('test_library.existing_table') is not null as library_migrated;
