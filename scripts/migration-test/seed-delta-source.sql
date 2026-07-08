set client_min_messages to warning;

update case_data
set
    version = 11,
    last_modified = timestamp '2026-01-02 10:00:00'
where id = 5601;

insert into case_data (
    id,
    reference,
    created_date,
    last_modified,
    jurisdiction,
    case_type_id,
    state,
    data,
    data_classification,
    supplementary_data,
    security_classification,
    version
) values (
    5604,
    7700000000000004,
    timestamp '2026-01-02 10:10:00',
    timestamp '2026-01-02 10:10:00',
    'ST_CIC',
    :'case_type',
    'Submitted',
    '{}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb,
    'PUBLIC',
    1
);

insert into case_event (
    id,
    created_date,
    event_id,
    user_id,
    case_data_id,
    case_type_id,
    case_type_version,
    state_id,
    data,
    user_first_name,
    user_last_name,
    event_name,
    state_name,
    security_classification,
    summary,
    description
) values (
    9105,
    timestamp '2026-01-02 10:20:00',
    'caseworker-delta-note',
    'user-6',
    5601,
    :'case_type',
    1,
    'Submitted',
    '{"note":"delta"}'::jsonb,
    'Case',
    'Worker',
    'Delta note',
    'Submitted',
    'PUBLIC',
    'summary',
    'description'
), (
    9106,
    timestamp '2026-01-02 10:30:00',
    'submit-case',
    'user-7',
    5604,
    :'case_type',
    1,
    'Submitted',
    '{}'::jsonb,
    'Case',
    'Worker',
    'Submit case',
    'Submitted',
    'PUBLIC',
    'summary',
    'description'
);

insert into case_event_significant_items (
    id,
    description,
    "type",
    url,
    case_event_id
) values (
    8105,
    'Delta document',
    'DOCUMENT',
    'http://dm-store/documents/8105',
    9105
), (
    8106,
    'New case document',
    'DOCUMENT',
    'http://dm-store/documents/8106',
    9106
);
