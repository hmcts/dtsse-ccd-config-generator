create extension if not exists pg_trgm with schema public;

create table docweave.docweave_template (
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
    on docweave.docweave_template (owner_id);

do $$
declare
    pg_trgm_schema name;
begin
    select namespace.nspname
    into pg_trgm_schema
    from pg_extension extension
    join pg_namespace namespace on namespace.oid = extension.extnamespace
    where extension.extname = 'pg_trgm';

    execute format(
        'create index docweave_template_searchable_text_idx '
        'on docweave.docweave_template using gin (searchable_text %I.gin_trgm_ops)',
        pg_trgm_schema
    );
end
$$;

create index docweave_template_tags_idx
    on docweave.docweave_template using gin (tags);
