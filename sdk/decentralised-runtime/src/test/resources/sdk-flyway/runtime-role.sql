-- A runtime login with no DDL: PostgreSQL 15+ grants PUBLIC neither CREATE on the database nor on schema public.
create role sdk_runtime login password 'not-a-secret';
