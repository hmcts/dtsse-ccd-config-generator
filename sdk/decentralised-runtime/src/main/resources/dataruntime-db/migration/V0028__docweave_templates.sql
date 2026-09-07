create extension if not exists pg_trgm;

create table ccd.docweave_template (
    id uuid primary key,
    owner_id uuid not null,
    title varchar(200) not null check (length(trim(title)) > 0),
    content jsonb not null check (jsonb_typeof(content) = 'object'),
    tags text[] not null default '{}',
    searchable_text text not null,
    revision bigint not null default 1 check (revision > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index docweave_template_owner_idx
    on ccd.docweave_template (owner_id);

create index docweave_template_title_idx
    on ccd.docweave_template using gin (title gin_trgm_ops);

create index docweave_template_searchable_text_idx
    on ccd.docweave_template using gin (searchable_text gin_trgm_ops);

create index docweave_template_tags_idx
    on ccd.docweave_template using gin (tags);
