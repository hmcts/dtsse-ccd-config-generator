update ccd.case_data set supplementary_data = '{}'::jsonb where supplementary_data is null;
alter table ccd.case_data
    alter column supplementary_data set not null,
    alter column supplementary_data set default '{}',
    add constraint supplementary_data_must_be_object check (jsonb_typeof(supplementary_data) = 'object');
