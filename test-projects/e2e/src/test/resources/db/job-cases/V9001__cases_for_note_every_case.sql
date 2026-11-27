-- Cases that exist before the application starts, as a one-off job finds them on a deploy.
insert into ccd.case_data (id, reference, security_classification, jurisdiction, case_type_id, state, data)
values (9001, 1700000000000001, 'PUBLIC', 'DIVORCE', 'E2E', 'Submitted', '{}'),
       (9002, 1700000000000002, 'PUBLIC', 'DIVORCE', 'E2E', 'Submitted', '{}'),
       (9003, 1700000000000003, 'PUBLIC', 'DIVORCE', 'E2E_SIMPLE', 'Submitted', '{}');
