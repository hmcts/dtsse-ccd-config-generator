-- Each version of a case's notes document, kept up to date by the doc toolkit.
create table case_notes_documents (
    id          bigserial primary key,
    reference   bigint not null references ccd.case_data(reference),
    revision    bigint not null,
    url         text not null unique,
    page_count  integer not null,
    created_at  timestamptz not null default now()
);

create index case_notes_documents_reference_idx on case_notes_documents (reference, revision desc);
