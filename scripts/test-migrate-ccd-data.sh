#!/usr/bin/env bash
set -euo pipefail

PSQL_BIN="${PSQL_BIN:-psql}"
PG_DUMP_BIN="${PG_DUMP_BIN:-pg_dump}"
CREATEDB_BIN="${CREATEDB_BIN:-createdb}"
DROPDB_BIN="${DROPDB_BIN:-dropdb}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_SCRIPT="${SCRIPT_DIR}/migrate-ccd-data.sh"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-6432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
CASE_TYPE="${CASE_TYPE:-CriminalInjuriesCompensation}"
export PGPASSWORD="$PG_PASSWORD"

BASE_CONN_ARGS=(-h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER")
BASE_DSN="postgresql://${PG_USER}:${PG_PASSWORD}@${PG_HOST}:${PG_PORT}"

SUFFIX="$(date +%s%N)"
SRC_DB="tmp_datastore_${SUFFIX}"
DST_DB="tmp_nfd_${SUFFIX}"
SRC_DSN="${BASE_DSN}/${SRC_DB}"
DST_DSN="${BASE_DSN}/${DST_DB}"

cleanup() {
  # Terminate any remaining connections, then drop the temporary databases.
  for db in "$SRC_DB" "$DST_DB"; do
    $PSQL_BIN -d "${BASE_DSN}/postgres" "${BASE_CONN_ARGS[@]}" --set=ON_ERROR_STOP=on --command \
      "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${db}' AND pid <> pg_backend_pid();" >/dev/null 2>&1 || true
  done

  $DROPDB_BIN "${BASE_CONN_ARGS[@]}" --if-exists "$SRC_DB" >/dev/null 2>&1 || true
  $DROPDB_BIN "${BASE_CONN_ARGS[@]}" --if-exists "$DST_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Creating temporary databases: $SRC_DB and $DST_DB"
$CREATEDB_BIN "${BASE_CONN_ARGS[@]}" "$SRC_DB"
$CREATEDB_BIN "${BASE_CONN_ARGS[@]}" "$DST_DB"

echo "Cloning datastore schema into $SRC_DB"
$PG_DUMP_BIN --schema-only "${BASE_DSN}/datastore" | $PSQL_BIN "$SRC_DSN" >/dev/null

echo "Cloning nfd schema into $DST_DB"
$PG_DUMP_BIN --schema-only --schema=ccd "${BASE_DSN}/nfd" | $PSQL_BIN "$DST_DSN" >/dev/null

echo "Seeding source database with sample CIC data"
$PSQL_BIN "$SRC_DSN" --set=ON_ERROR_STOP=on <<'SQL'
SET client_min_messages TO WARNING;
DELETE FROM case_event WHERE case_data_id = 5601;
DELETE FROM case_data WHERE id = 5601;

INSERT INTO case_data (
    id, reference, created_date, last_modified, jurisdiction, case_type_id, state,
    data, data_classification, supplementary_data, security_classification, version
) VALUES (
    5601, 7700000000000001, now(), now(), 'ST_CIC', 'CriminalInjuriesCompensation', 'Submitted',
    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'PUBLIC', 3
);

INSERT INTO case_event (
    id, created_date, event_id, user_id, case_data_id, case_type_id, case_type_version,
    state_id, data, user_first_name, user_last_name, event_name, state_name,
    security_classification, summary, description
) VALUES (
    9101, now(), 'submit-case', 'user-1', 5601, 'CriminalInjuriesCompensation', 1,
    'Submitted', '{}'::jsonb, 'Case', 'Worker', 'Submit case', 'Submitted',
    'PUBLIC', 'summary', 'description'
), (
    9102, now(), 'caseworker-add-note', 'user-2', 5601, 'CriminalInjuriesCompensation', 1,
    'Submitted', '{"note":"test"}'::jsonb, 'Case', 'Worker', 'Add note', 'Submitted',
    'PUBLIC', 'summary', 'description'
);
SQL

echo "Ensuring target database is empty"
$PSQL_BIN "$DST_DSN" --set=ON_ERROR_STOP=on <<'SQL'
SET client_min_messages TO WARNING;
DELETE FROM ccd.case_event;
DELETE FROM ccd.case_data;
SQL

echo "Running migration script against temporary databases"
SRC_DSN="$SRC_DSN" DST_DSN="$DST_DSN" CASE_TYPE="$CASE_TYPE" "$MIGRATION_SCRIPT"

echo "Validating migrated record counts"
SRC_CASES=$($PSQL_BIN "$SRC_DSN" -At -c "SELECT COUNT(*) FROM case_data WHERE case_type_id='${CASE_TYPE}'")
SRC_EVENTS=$($PSQL_BIN "$SRC_DSN" -At -c "SELECT COUNT(*) FROM case_event ce JOIN case_data cd ON cd.id = ce.case_data_id WHERE cd.case_type_id='${CASE_TYPE}'")
DST_CASES=$($PSQL_BIN "$DST_DSN" -At -c "SELECT COUNT(*) FROM ccd.case_data WHERE case_type_id='${CASE_TYPE}'")
DST_EVENTS=$($PSQL_BIN "$DST_DSN" -At -c "SELECT COUNT(*) FROM ccd.case_event")

if [[ "$SRC_CASES" != "$DST_CASES" || "$SRC_EVENTS" != "$DST_EVENTS" ]]; then
  echo "Count mismatch after migration: source=${SRC_CASES}/${SRC_EVENTS} target=${DST_CASES}/${DST_EVENTS}" >&2
  exit 1
fi

echo "Test migration completed successfully."
