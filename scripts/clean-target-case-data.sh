#!/usr/bin/env bash
set -euo pipefail

# Deletes migrated rows from the target ccd.case_data / ccd.case_event tables.
# Use sparingly (e.g. to clean up after a failed run) and only after confirming
# no decentralised writes are hitting the target service.

DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:6432/postgres}"
CASE_TYPE="${CASE_TYPE:-TEST}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql is required on PATH"
    exit 1
  fi
}

main() {
  require_psql
  log "Deleting ccd.case_data rows for case_type_id='${CASE_TYPE}' (cascades to case_event)..."
  psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet <<SQL
DELETE FROM ccd.case_data WHERE case_type_id = '${CASE_TYPE}';
SQL

  log "Cleanup complete."
}

main "$@"
