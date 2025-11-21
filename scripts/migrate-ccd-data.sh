#!/usr/bin/env bash
set -euo pipefail

# Configuration (can be overridden via environment variables)
SRC_DSN="${SRC_DSN:-postgresql://postgres:postgres@localhost:6432/datastore}"
DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:6432/sptribs}"
CASE_TYPE="${CASE_TYPE:-CriminalInjuriesCompensation}"
DO_APPLY=false

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"
}

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql is required on PATH"
    exit 1
  fi
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [--apply]

Default mode validates connectivity and checks the target is empty for CASE_TYPE.
Use --apply to perform the migration after validation passes.
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --apply)
        DO_APPLY=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        log "ERROR: Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done
}

validate_connections() {
  log "Validating connectivity to source and target databases..."
  psql "$SRC_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -c "SELECT 1;" >/dev/null
  psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -c "SELECT 1;" >/dev/null
}

validate_can_disable_triggers() {
  log "Validating ability to disable triggers on target (session_replication_role)..."
  psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet <<'SQL' >/dev/null
BEGIN;
SET LOCAL session_replication_role = replica;
RESET session_replication_role;
ROLLBACK;
SQL
}

validate_target_empty() {
  log "Checking target is empty for case_type_id='${CASE_TYPE}'..."
  local dst_cases dst_events
  dst_cases=$(psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM ccd.case_data WHERE case_type_id = '${CASE_TYPE}'")
  dst_events=$(psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM ccd.case_event ce JOIN ccd.case_data cd ON cd.id = ce.case_data_id WHERE cd.case_type_id = '${CASE_TYPE}'")

  if [[ "$dst_cases" -ne 0 || "$dst_events" -ne 0 ]]; then
    log "ERROR: Target contains ${dst_cases} case_data rows and ${dst_events} case_event rows for ${CASE_TYPE}."
    log "Run ./scripts/clean-target-case-data.sh before migrating."
    exit 1
  fi
}

copy_case_data() {
  log "Copying case_data rows for case_type_id='${CASE_TYPE}'..."
  local select_sql
  local insert_sql
  select_sql=$(cat <<EOF
COPY (
  SELECT
      reference,
      version,
      created_date,
      security_classification,
      last_state_modified_date,
      resolved_ttl,
      last_modified,
      jurisdiction,
      case_type_id,
      state,
      data,
      COALESCE(supplementary_data, '{}'::jsonb) AS supplementary_data,
      id,
      version AS case_revision
  FROM public.case_data
  WHERE case_type_id = '${CASE_TYPE}'
) TO STDOUT WITH (FORMAT CSV);
EOF
)
  insert_sql=$(cat <<'EOF'
-- Disable triggers during COPY to avoid firing revision/indexing triggers on bulk load.
BEGIN;
SET LOCAL session_replication_role = replica;
COPY ccd.case_data (
    reference,
    version,
    created_date,
    security_classification,
    last_state_modified_date,
    resolved_ttl,
    last_modified,
    jurisdiction,
    case_type_id,
    state,
    data,
    supplementary_data,
    id,
    case_revision
) FROM STDIN WITH (FORMAT CSV);
COMMIT;
EOF
)

  psql "$SRC_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --tuples-only --quiet -c "$select_sql" | \
    psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -c "$insert_sql"
}

copy_case_event() {
  log "Copying case_event rows linked to migrated cases..."
  local select_sql
  local insert_sql
  select_sql=$(cat <<EOF
COPY (
  SELECT
      ce.id,
      ce.created_date,
      ce.security_classification,
      cd.id AS case_data_id,
      ce.case_type_version,
      ce.event_id,
      row_number() over (partition by cd.id order by ce.id) AS version,
      row_number() over (partition by cd.id order by ce.id) AS case_revision,
      ce.summary,
      ce.description,
      ce.user_id,
      ce.case_type_id,
      ce.state_id,
      ce.data,
      ce.user_first_name,
      ce.user_last_name,
      ce.event_name,
      ce.state_name,
      ce.proxied_by,
      ce.proxied_by_first_name,
      ce.proxied_by_last_name,
      gen_random_uuid() as idempotency_key
  FROM public.case_event ce
       JOIN public.case_data cd ON cd.id = ce.case_data_id
  WHERE cd.case_type_id = '${CASE_TYPE}'
  ORDER BY ce.id ASC
) TO STDOUT WITH (FORMAT CSV);
EOF
)
  insert_sql=$(cat <<'EOF'
COPY ccd.case_event (
    id,
    created_date,
    security_classification,
    case_data_id,
    case_type_version,
    event_id,
    version,
    case_revision,
    summary,
    description,
    user_id,
    case_type_id,
    state_id,
    data,
    user_first_name,
    user_last_name,
    event_name,
    state_name,
    proxied_by,
    proxied_by_first_name,
    proxied_by_last_name,
    idempotency_key
) FROM STDIN WITH (FORMAT CSV);
EOF
)

  psql "$SRC_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --tuples-only --quiet -c "$select_sql" | \
    psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -c "$insert_sql"
}

align_case_revisions() {
  log "Aligning case_data.case_revision with migrated event counts..."
  # We have seen CCD cases where case versions and event counts drift; ensure revisions match event history
  # before enforcing any uniqueness or writing new events.
  psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet <<EOF
BEGIN;
SET LOCAL session_replication_role = replica;
WITH ev AS (
  SELECT cd.id AS case_data_id, COUNT(*) AS event_count
  FROM ccd.case_data cd
  JOIN ccd.case_event ce ON ce.case_data_id = cd.id
  WHERE cd.case_type_id = '${CASE_TYPE}'
  GROUP BY cd.id
)
UPDATE ccd.case_data cd
SET case_revision = ev.event_count
FROM ev
WHERE cd.id = ev.case_data_id
  AND cd.case_type_id = '${CASE_TYPE}'
  AND cd.case_revision IS DISTINCT FROM ev.event_count;
COMMIT;
EOF
}

reset_sequences() {
  log "Synchronising target sequences..."
  # During COPY the explicit IDs from the source are loaded as-is, so the sequence
  # that backs ccd.case_event does not advance automatically. Resetting it
  # prevents the next insert from reusing an existing id and triggering a
  # duplicate-key error once decentralised writes resume.
  psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --tuples-only --quiet <<'SQL' >/dev/null
SELECT setval('ccd.case_event_id_seq', COALESCE((SELECT MAX(id) FROM ccd.case_event), 1), true);
SQL
}

report_counts() {
  local src_cases src_events dst_cases dst_events
  src_cases=$(psql "$SRC_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM public.case_data WHERE case_type_id = '${CASE_TYPE}'")
  src_events=$(psql "$SRC_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM public.case_event ce JOIN public.case_data cd ON cd.id = ce.case_data_id WHERE cd.case_type_id = '${CASE_TYPE}'")
  dst_cases=$(psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM ccd.case_data WHERE case_type_id = '${CASE_TYPE}'")
  dst_events=$(psql "$DST_DSN" --set=ON_ERROR_STOP=on --no-psqlrc --quiet -tA \
    -c "SELECT COUNT(*) FROM ccd.case_event ce JOIN ccd.case_data cd ON cd.id = ce.case_data_id WHERE cd.case_type_id = '${CASE_TYPE}'")

  log "Source counts: case_data=${src_cases}, case_event=${src_events}"
  log "Target counts: case_data=${dst_cases}, case_event=${dst_events}"
}

main() {
  parse_args "$@"
  require_psql
  validate_connections
  validate_can_disable_triggers
  validate_target_empty

  if [[ "$DO_APPLY" == false ]]; then
    log "Validation succeeded. Re-run with --apply to perform the migration."
    exit 0
  fi

  log "Validation succeeded. Proceeding with migration..."
  copy_case_data
  copy_case_event
  align_case_revisions
  reset_sequences
  report_counts
  log "Migration complete."
}

main "$@"
