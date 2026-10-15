#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/migration-test/lib.sh
source "${SCRIPT_DIR}/migration-test/lib.sh"

SETUP_SCRIPT="${SCRIPT_DIR}/setup-ccd-data-fdw.sh"
MIGRATION_SCRIPT="${SCRIPT_DIR}/migrate-ccd-data-fdw.sh"
DELTA_SINCE="${DELTA_SINCE:-2026-01-01 00:00:00}"
FAILURE_LOG="/tmp/test-migrate-ccd-data-fdw-failure-$$.log"

# FDW connections are made by the Postgres server, not by the test runner.
# The runner uses PG_PORT (usually 6432), while the server normally sees itself on 5432.
FDW_SRC_HOST="${FDW_SRC_HOST:-localhost}"
FDW_SRC_PORT="${FDW_SRC_PORT:-5432}"
FDW_SRC_SSLMODE="${FDW_SRC_SSLMODE:-disable}"
FDW_SERVER="${FDW_SERVER:-source-server-with-dashes.example.test}"
FDW_ADDITIONAL_GRANTEE="${FDW_ADDITIONAL_GRANTEE:-FDW Additional Reader SC\"; select 1; --}"

init_migration_test_env "fdw"
trap cleanup_temp_dbs EXIT

run_fdw_setup() {
  echo "Creating additional FDW reader role"
  psql_dst --set=fdw_additional_grantee="$FDW_ADDITIONAL_GRANTEE" <<'SQL'
select format('create role %I', :'fdw_additional_grantee')
where not exists (
  select 1
  from pg_roles
  where rolname = :'fdw_additional_grantee'
)
\gexec
grant :"fdw_additional_grantee" to current_user;
SQL

  echo "Running FDW setup script (validation mode only)"
  DST_DSN="$DST_DSN" \
    SRC_HOST="$FDW_SRC_HOST" \
    SRC_PORT="$FDW_SRC_PORT" \
    SRC_DB="$SRC_DB" \
    SRC_SCHEMA="public" \
    SRC_USER="$PG_USER" \
    SRC_PASSWORD="$PG_PASSWORD" \
    SRC_PASSWORD_REQUIRED="false" \
    SRC_SSLMODE="$FDW_SRC_SSLMODE" \
    FDW_SCHEMA="$FDW_SCHEMA" \
    FDW_SERVER="$FDW_SERVER" \
    LOCAL_USER_SQL="current_user" \
    FDW_ADDITIONAL_GRANTEE="$FDW_ADDITIONAL_GRANTEE" \
    "$SETUP_SCRIPT"

  echo "Running FDW setup script (apply mode)"
  DST_DSN="$DST_DSN" \
    SRC_HOST="$FDW_SRC_HOST" \
    SRC_PORT="$FDW_SRC_PORT" \
    SRC_DB="$SRC_DB" \
    SRC_SCHEMA="public" \
    SRC_USER="$PG_USER" \
    SRC_PASSWORD="$PG_PASSWORD" \
    SRC_PASSWORD_REQUIRED="false" \
    SRC_SSLMODE="$FDW_SRC_SSLMODE" \
    FDW_SCHEMA="$FDW_SCHEMA" \
    FDW_SERVER="$FDW_SERVER" \
    LOCAL_USER_SQL="current_user" \
    FDW_ADDITIONAL_GRANTEE="$FDW_ADDITIONAL_GRANTEE" \
    "$SETUP_SCRIPT" --apply
}

run_fdw_migration() {
  local env_args=(
    "DST_DSN=$DST_DSN"
    "FDW_SCHEMA=$FDW_SCHEMA"
    "CASE_TYPE_IDS_SQL='${CASE_TYPE}'"
    "CASE_REVISION_OFFSET=$CASE_REVISION_OFFSET"
  )

  if (($# > 0)); then
    env_args+=("$@")
  fi

  env \
    "${env_args[@]}" \
    "$MIGRATION_SCRIPT" --apply
}

assert_fdw_setup() {
  local extension_count foreign_server_count foreign_table_count source_case_count additional_role_case_count
  local additional_mapping_count

  echo "Validating FDW setup"
  extension_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from pg_extension
where extname in ('postgres_fdw', 'pgcrypto');
SQL
)"
  foreign_server_count="$(psql_dst --quiet -tA --set=fdw_server="$FDW_SERVER" <<'SQL'
select count(*)
from pg_foreign_server
where srvname = :'fdw_server';
SQL
)"
  foreign_table_count="$(psql_dst --quiet -tA <<SQL
select count(*)
from pg_foreign_table ft
join pg_class c
  on c.oid = ft.ftrelid
join pg_namespace n
  on n.oid = c.relnamespace
where n.nspname = '${FDW_SCHEMA}'
  and c.relname in ('case_data', 'case_event', 'case_event_significant_items');
SQL
)"
  source_case_count="$(psql_dst --quiet -tA <<SQL
select count(*)
from ${FDW_SCHEMA}.case_data
where case_type_id = :'case_type';
SQL
)"
  additional_mapping_count="$(psql_dst --quiet -tA \
    --set=fdw_server="$FDW_SERVER" \
    --set=fdw_additional_grantee="$FDW_ADDITIONAL_GRANTEE" <<'SQL'
select count(*)
from pg_user_mapping um
join pg_foreign_server s
  on s.oid = um.umserver
join pg_roles r
  on r.oid = um.umuser
where s.srvname = :'fdw_server'
  and r.rolname = :'fdw_additional_grantee';
SQL
)"
  additional_role_case_count="$(psql_dst --quiet -tA \
    --set=fdw_additional_grantee="$FDW_ADDITIONAL_GRANTEE" <<SQL
set role :"fdw_additional_grantee";
select count(*)
from ${FDW_SCHEMA}.case_data
where case_type_id = :'case_type';
reset role;
SQL
)"

  if [[ "$extension_count" != "2" || "$foreign_server_count" != "1" || "$foreign_table_count" != "3" \
      || "$source_case_count" != "2" || "$additional_mapping_count" != "1" || "$additional_role_case_count" != "2" ]]; then
    echo "FDW setup validation failed:" \
      "extensions=${extension_count}, server=${foreign_server_count}," \
      "tables=${foreign_table_count}, source_cases=${source_case_count}," \
      "additional_mapping=${additional_mapping_count}, additional_role_cases=${additional_role_case_count}" >&2
    exit 1
  fi
}

assert_delta_rows_migrated() {
  local delta_event_count delta_case_count delta_significant_item_count

  echo "Validating delta rows were migrated"
  delta_event_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event
where id in (9105, 9106);
SQL
)"
  delta_case_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_data
where id = 5604;
SQL
)"
  delta_significant_item_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event_significant_items
where id in (8105, 8106);
SQL
)"

  if [[ "$delta_event_count" != "2" || "$delta_case_count" != "1" || "$delta_significant_item_count" != "2" ]]; then
    echo "Delta migration failed: delta_cases=${delta_case_count}, delta_events=${delta_event_count}," \
      "delta_significant_items=${delta_significant_item_count}" >&2
    exit 1
  fi
}

assert_constraints_restored_after_failure() {
  local status

  echo "Validating FDW migration restores constraints after failure"
  psql_dst --quiet <<'SQL'
alter table ccd.case_data
add constraint tmp_force_fdw_migration_failure
check (reference <> 7700000000000001);
SQL

  set +e
  env \
    DST_DSN="$DST_DSN" \
    FDW_SCHEMA="$FDW_SCHEMA" \
    CASE_TYPE_IDS_SQL="'${CASE_TYPE}'" \
    "$MIGRATION_SCRIPT" --apply >"$FAILURE_LOG" 2>&1
  status=$?
  set -e

  psql_dst --quiet <<'SQL'
alter table ccd.case_data
drop constraint if exists tmp_force_fdw_migration_failure;
SQL

  if [[ "$status" == "0" ]]; then
    echo "Expected forced FDW migration failure, but migration succeeded" >&2
    exit 1
  fi

  assert_case_event_constraints_present
  clear_target_data
}

create_temp_dbs
seed_source_data
clear_target_data
install_trigger_guards
run_fdw_setup
assert_fdw_setup
assert_case_event_constraints_present
assert_constraints_restored_after_failure

echo "Running FDW migration script (validation mode only)"
DST_DSN="$DST_DSN" \
  FDW_SCHEMA="$FDW_SCHEMA" \
  CASE_TYPE_IDS_SQL="'${CASE_TYPE}'" \
  CASE_REVISION_OFFSET="$CASE_REVISION_OFFSET" \
  "$MIGRATION_SCRIPT"

echo "Running FDW migration script (apply mode)"
run_fdw_migration
assert_common_migration_state

seed_delta_source_data
echo "Running FDW migration script (delta apply mode)"
run_fdw_migration "DELTA_SINCE=${DELTA_SINCE}"
assert_delta_rows_migrated
assert_common_migration_state

echo "FDW migration test (setup + validate + apply + delta) completed successfully."
