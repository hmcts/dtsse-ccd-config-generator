-- Copy this file into the application's Flyway migration directory and replace
-- VXXXX with the application's next migration version.
--
-- This removes only the temporary FDW objects used by CCD data migration. It
-- deliberately retains migrated data, migration progress, and the postgres_fdw
-- and pgcrypto extensions.

DO $cleanup$
DECLARE
    fdw_schema CONSTANT text := 'fdw_stage';
    fdw_server CONSTANT text := 'src_ccd_server';
    unexpected_relations text;
    external_server_relations text;
    wrong_server_relations text;
    incomplete_tasks text;
    progress_table text;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_foreign_server s
        WHERE s.srvname = fdw_server
          AND NOT pg_has_role(current_user, s.srvowner, 'USAGE')
    ) THEN
        RAISE EXCEPTION 'Current user % does not own FDW server %', current_user, fdw_server;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_namespace n
        WHERE n.nspname = fdw_schema
          AND NOT pg_has_role(current_user, n.nspowner, 'USAGE')
    ) THEN
        RAISE EXCEPTION 'Current user % does not own FDW schema %', current_user, fdw_schema;
    END IF;

    SELECT string_agg(format('%I.%I', n.nspname, c.relname), ', ' ORDER BY c.relname)
    INTO unexpected_relations
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = fdw_schema
      AND NOT (
          c.relkind = 'f'
          AND c.relname IN ('case_data', 'case_event', 'case_event_significant_items')
      );

    IF unexpected_relations IS NOT NULL THEN
        RAISE EXCEPTION 'FDW schema contains unexpected relations: %', unexpected_relations;
    END IF;

    SELECT string_agg(format('%I.%I', n.nspname, c.relname), ', ' ORDER BY n.nspname, c.relname)
    INTO external_server_relations
    FROM pg_foreign_table ft
    JOIN pg_class c ON c.oid = ft.ftrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_foreign_server s ON s.oid = ft.ftserver
    WHERE s.srvname = fdw_server
      AND n.nspname <> fdw_schema;

    IF external_server_relations IS NOT NULL THEN
        RAISE EXCEPTION 'FDW server is shared by tables outside the migration schema: %',
            external_server_relations;
    END IF;

    SELECT string_agg(
        format('%I.%I uses %I', n.nspname, c.relname, s.srvname),
        ', ' ORDER BY c.relname
    )
    INTO wrong_server_relations
    FROM pg_foreign_table ft
    JOIN pg_class c ON c.oid = ft.ftrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_foreign_server s ON s.oid = ft.ftserver
    WHERE n.nspname = fdw_schema
      AND s.srvname <> fdw_server;

    IF wrong_server_relations IS NOT NULL THEN
        RAISE EXCEPTION 'Migration schema contains tables on a different FDW server: %',
            wrong_server_relations;
    END IF;

    FOREACH progress_table IN ARRAY ARRAY[
        'ccd_data_migration.ccd_data_migration_progress',
        'ccd.ccd_data_migration_progress'
    ] LOOP
        IF to_regclass(progress_table) IS NOT NULL THEN
            EXECUTE format(
                'SELECT string_agg(task_name || ''='' || status, '', '' ORDER BY task_name) '
                'FROM %s WHERE status <> ''COMPLETE''',
                progress_table
            ) INTO incomplete_tasks;

            IF incomplete_tasks IS NOT NULL THEN
                RAISE EXCEPTION 'CCD data migration has incomplete progress rows in %: %',
                    progress_table, incomplete_tasks;
            END IF;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM pg_foreign_server
        WHERE srvname = fdw_server
    ) THEN
        EXECUTE format('DROP SERVER %I CASCADE', fdw_server);
    END IF;

    EXECUTE format('DROP SCHEMA IF EXISTS %I', fdw_schema);
END
$cleanup$;
