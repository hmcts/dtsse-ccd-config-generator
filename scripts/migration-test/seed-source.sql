set client_min_messages to warning;

delete from case_event_significant_items where case_event_id in (9101, 9102, 9103, 9104, 9199);
delete from case_event where case_data_id in (5601, 5602, 5603, 5604);
delete from case_data where id in (5601, 5602, 5603, 5604);

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
    5601,
    7700000000000001,
    timestamp '2025-12-31 10:00:00',
    timestamp '2025-12-31 10:00:00',
    'ST_CIC',
    :'case_type',
    'Submitted',
    '{"subject":"first","TTL":{"SystemTTL":"2030-01-02","OverrideTTL":"2031-03-04","Suspended":"Yes"}}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb,
    'PUBLIC',
    10
), (
    5602,
    7700000000000002,
    timestamp '2025-12-31 10:00:00',
    timestamp '2025-12-31 10:00:00',
    'ST_CIC',
    :'case_type',
    'Submitted',
    '{"subject":"second","TTL":{"SystemTTL":"2032-05-06","OverrideTTL":null,"Suspended":"No"}}'::jsonb,
    '{}'::jsonb,
    '{}'::jsonb,
    'PUBLIC',
    1
), (
    5603,
    7700000000000003,
    timestamp '2025-12-31 10:00:00',
    timestamp '2025-12-31 10:00:00',
    'ST_CIC',
    :'other_case_type',
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
    9101,
    timestamp '2025-12-31 10:01:00',
    'submit-case',
    'user-1',
    5601,
    :'case_type',
    1,
    'Submitted',
    '{"TTL":{"SystemTTL":"2029-01-01","Suspended":"No"}}'::jsonb,
    'Case',
    'Worker',
    'Submit case',
    'Submitted',
    'PUBLIC',
    'summary',
    'description'
), (
    9102,
    timestamp '2025-12-31 10:02:00',
    'caseworker-add-note',
    'user-2',
    5601,
    :'case_type',
    1,
    'Submitted',
    '{"note":"test"}'::jsonb,
    'Case',
    'Worker',
    'Add note',
    'Submitted',
    'PUBLIC',
    'summary',
    'description'
), (
    9103,
    timestamp '2025-12-31 10:03:00',
    'submit-case',
    'user-3',
    5602,
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
), (
    9104,
    timestamp '2025-12-31 10:04:00',
    'caseworker-add-note',
    'user-4',
    5602,
    :'case_type',
    1,
    'Submitted',
    '{"note":"extra"}'::jsonb,
    'Case',
    'Worker',
    'Add note',
    'Submitted',
    'PUBLIC',
    'summary',
    'description'
), (
    9199,
    timestamp '2025-12-31 10:05:00',
    'out-of-scope-parent',
    'user-5',
    5603,
    :'case_type',
    1,
    'Submitted',
    '{}'::jsonb,
    'Case',
    'Worker',
    'Misleading event case type',
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
    8101,
    'Main document',
    'DOCUMENT',
    'http://dm-store/documents/8101',
    9102
), (
    8102,
    'Extra document',
    'DOCUMENT',
    'http://dm-store/documents/8102',
    9104
), (
    8199,
    'Out of scope document',
    'DOCUMENT',
    'http://dm-store/documents/8199',
    9199
);
