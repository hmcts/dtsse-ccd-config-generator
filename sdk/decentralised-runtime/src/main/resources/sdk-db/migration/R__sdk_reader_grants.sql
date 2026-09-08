do $$
declare
  reader_role constant text := nullif('${sdkReaderRole}', '');
  schema_name constant text := '${flyway:defaultSchema}';
begin
  if reader_role is not null and exists (
    select 1
    from pg_roles
    where rolname = reader_role
  ) then
    execute format('grant usage on schema %I to %I', schema_name, reader_role);
    execute format(
      'grant select on all tables in schema %I to %I',
      schema_name,
      reader_role
    );

    execute format(
      'alter default privileges in schema %I grant select on tables to %I',
      schema_name,
      reader_role
    );
  end if;
end
$$;
