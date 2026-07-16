alter table ccd.case_data
    add column system_ttl date,
    add column override_ttl date,
    add column ttl_suspended boolean,
    drop column resolved_ttl;
