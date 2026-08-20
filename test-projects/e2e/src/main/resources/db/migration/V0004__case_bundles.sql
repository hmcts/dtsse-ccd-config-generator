-- Service-owned storage for generated hearing bundles (document-bundling SDK e2e verification).
-- Following the decentralised idiom (see case_notes): the submit handler persists here in the
-- event transaction and NFDCaseView projects the rows into the external caseBundles case field.
create table case_bundles (
    id serial primary key,
    reference bigint not null,
    bundle_id varchar(64) not null,
    bundle jsonb not null,
    created_at timestamp not null default now()
);

create index case_bundles_reference_idx on case_bundles (reference);
