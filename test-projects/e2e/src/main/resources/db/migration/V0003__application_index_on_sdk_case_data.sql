create unique index uidx_case_data_sdk_migration_order_reference
    on ccd.case_data (
        case_type_id,
        btrim(upper(data #>> '{sdkMigrationOrderReference}'))
    )
    where data ? 'sdkMigrationOrderReference';
