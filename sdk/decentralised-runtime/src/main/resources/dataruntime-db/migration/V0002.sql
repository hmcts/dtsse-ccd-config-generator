update ccd.case_data set supplementary_data = '{}'::jsonb where supplementary_data is null;
alter table ccd.case_data
    alter column supplementary_data set not null,
    alter column supplementary_data set default '{}',
    add constraint supplementary_data_must_be_object check (jsonb_typeof(supplementary_data) = 'object');

alter table ccd.case_event
    add constraint data_must_be_object check (jsonb_typeof(data) = 'object');

create table ccd.completed_events (
    id uuid primary key,
    created_at timestamp not null default now()
);

CREATE INDEX completed_event_created_at ON ccd.completed_events (created_at);
