#!/usr/bin/env bash

PSQL_BIN="${PSQL_BIN:-psql}"
PG_DUMP_BIN="${PG_DUMP_BIN:-pg_dump}"
CREATEDB_BIN="${CREATEDB_BIN:-createdb}"
DROPDB_BIN="${DROPDB_BIN:-dropdb}"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-6432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
CASE_TYPE="${CASE_TYPE:-CriminalInjuriesCompensation}"
OTHER_CASE_TYPE="${OTHER_CASE_TYPE:-OtherCaseType}"
FDW_SCHEMA="${FDW_SCHEMA:-fdw_stage}"
CASE_REVISION_OFFSET="${CASE_REVISION_OFFSET:-1000000000}"

export PGPASSWORD="$PG_PASSWORD"

MIGRATION_TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_CONN_ARGS=(-h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER")
BASE_DSN="postgresql://${PG_USER}:${PG_PASSWORD}@${PG_HOST}:${PG_PORT}"

init_migration_test_env() {
  local prefix="$1"
  local suffix
  suffix="$(date +%s%N)"

  SRC_DB="tmp_${prefix}_datastore_${suffix}"
  DST_DB="tmp_${prefix}_target_${suffix}"
  SRC_DSN="${BASE_DSN}/${SRC_DB}"
  DST_DSN="${BASE_DSN}/${DST_DB}"
}

cleanup_temp_dbs() {
  local exit_code=$?

  for db in "$SRC_DB" "$DST_DB"; do
    "$PSQL_BIN" -d "${BASE_DSN}/postgres" "${BASE_CONN_ARGS[@]}" --set=ON_ERROR_STOP=on --command \
      "select pg_terminate_backend(pid) from pg_stat_activity where datname = '${db}' and pid <> pg_backend_pid();" \
      >/dev/null 2>&1 || true
  done

  "$DROPDB_BIN" "${BASE_CONN_ARGS[@]}" --if-exists "$SRC_DB" >/dev/null 2>&1 || true
  "$DROPDB_BIN" "${BASE_CONN_ARGS[@]}" --if-exists "$DST_DB" >/dev/null 2>&1 || true

  return "$exit_code"
}

psql_src() {
  "$PSQL_BIN" "$SRC_DSN" \
    --set=ON_ERROR_STOP=on \
    --no-psqlrc \
    --set=case_type="$CASE_TYPE" \
    --set=other_case_type="$OTHER_CASE_TYPE" \
    --set=case_revision_offset="$CASE_REVISION_OFFSET" \
    "$@"
}

psql_dst() {
  "$PSQL_BIN" "$DST_DSN" \
    --set=ON_ERROR_STOP=on \
    --no-psqlrc \
    --set=case_type="$CASE_TYPE" \
    --set=other_case_type="$OTHER_CASE_TYPE" \
    --set=case_revision_offset="$CASE_REVISION_OFFSET" \
    "$@"
}

create_temp_dbs() {
  echo "Creating temporary databases: $SRC_DB and $DST_DB"
  "$CREATEDB_BIN" "${BASE_CONN_ARGS[@]}" "$SRC_DB"
  "$CREATEDB_BIN" "${BASE_CONN_ARGS[@]}" "$DST_DB"

  echo "Cloning datastore schema into $SRC_DB"
  "$PG_DUMP_BIN" --schema-only "${BASE_DSN}/datastore" | "$PSQL_BIN" "$SRC_DSN" >/dev/null

  echo "Cloning nfd schema into $DST_DB"
  "$PG_DUMP_BIN" --schema-only --schema=ccd "${BASE_DSN}/nfd" | "$PSQL_BIN" "$DST_DSN" >/dev/null
}

seed_source_data() {
  echo "Seeding source database with shared migration fixture"
  psql_src --file "${MIGRATION_TEST_DIR}/seed-source.sql"
}

seed_delta_source_data() {
  echo "Seeding source database with delta migration fixture"
  psql_src --file "${MIGRATION_TEST_DIR}/seed-delta-source.sql"
}

clear_target_data() {
  echo "Ensuring target database is empty"
  psql_dst <<'SQL'
set client_min_messages to warning;
delete from ccd.case_event_significant_items;
delete from ccd.case_event;
delete from ccd.case_data;
SQL
}

install_trigger_guards() {
  echo "Installing target trigger guards"
  psql_dst <<'SQL'
create or replace function ccd.fail_if_migration_trigger_fires()
returns trigger as $$
begin
    raise exception 'Migration write trigger fired on %.% during %',
        tg_table_schema, tg_table_name, tg_op;
end;
$$ language plpgsql;

drop trigger if exists migration_test_case_data_guard on ccd.case_data;
create trigger migration_test_case_data_guard
before insert or update on ccd.case_data
for each row execute function ccd.fail_if_migration_trigger_fires();

drop trigger if exists migration_test_case_event_guard on ccd.case_event;
create trigger migration_test_case_event_guard
before insert or update on ccd.case_event
for each row execute function ccd.fail_if_migration_trigger_fires();
SQL
}

assert_target_empty() {
  local dst_cases dst_events

  dst_cases="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_data
where case_type_id = :'case_type';
SQL
)"
  dst_events="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event ce
join ccd.case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id = :'case_type';
SQL
)"

  if [[ "$dst_cases" != "0" || "$dst_events" != "0" ]]; then
    echo "Target still has rows after cleanup: ${dst_cases}/${dst_events}" >&2
    exit 1
  fi
}

assert_migrated_counts_match_source() {
  local src_cases src_events src_significant_items dst_cases dst_events dst_significant_items

  echo "Validating migrated record counts"
  src_cases="$(psql_src --quiet -tA <<'SQL'
select count(*)
from case_data
where case_type_id = :'case_type';
SQL
)"
  src_events="$(psql_src --quiet -tA <<'SQL'
select count(*)
from case_event ce
join case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id = :'case_type';
SQL
)"
  src_significant_items="$(psql_src --quiet -tA <<'SQL'
select count(*)
from case_event_significant_items item
join case_event ce
  on ce.id = item.case_event_id
join case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id = :'case_type';
SQL
)"
  dst_cases="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_data
where case_type_id = :'case_type';
SQL
)"
  dst_events="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event ce
join ccd.case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id = :'case_type';
SQL
)"
  dst_significant_items="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event_significant_items item
join ccd.case_event ce
  on ce.id = item.case_event_id
join ccd.case_data cd
  on cd.id = ce.case_data_id
where cd.case_type_id = :'case_type';
SQL
)"

  if [[ "$src_cases" != "$dst_cases"
      || "$src_events" != "$dst_events"
      || "$src_significant_items" != "$dst_significant_items" ]]; then
    echo "Count mismatch after migration: source=${src_cases}/${src_events}/${src_significant_items}" \
      "target=${dst_cases}/${dst_events}/${dst_significant_items}" >&2
    exit 1
  fi
}

assert_out_of_scope_parent_event_not_migrated() {
  local copied_count

  echo "Validating events are filtered through parent case type"
  copied_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event
where id = 9199;
SQL
)"

  if [[ "$copied_count" != "0" ]]; then
    echo "Out-of-scope parent event was migrated despite misleading event case_type_id" >&2
    exit 1
  fi

  copied_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event_significant_items
where id = 8199;
SQL
)"

  if [[ "$copied_count" != "0" ]]; then
    echo "Out-of-scope parent significant item was migrated despite misleading event case_type_id" >&2
    exit 1
  fi
}

assert_case_revision_alignment() {
  local mismatch_count

  echo "Validating migrated case revisions match event revisions plus offset"
  mismatch_count="$(psql_dst --quiet -tA <<'SQL'
with revision_check as (
    select
        cd.id,
        cd.case_revision,
        count(ce.id)::bigint as event_count,
        coalesce(max(ce.case_revision), 0)::bigint as max_event_revision,
        coalesce(max(ce.case_revision), 0)::bigint + :'case_revision_offset'::bigint as expected_case_revision
    from ccd.case_data cd
    left join ccd.case_event ce
      on ce.case_data_id = cd.id
    where cd.case_type_id = :'case_type'
    group by cd.id, cd.case_revision
)
select count(*)
from revision_check
where case_revision is distinct from expected_case_revision
   or event_count is distinct from max_event_revision;
SQL
)"

  if [[ "$mismatch_count" != "0" ]]; then
    echo "Revision mismatch after migration: ${mismatch_count} case(s) out of sync" >&2
    exit 1
  fi
}

assert_event_revisions_are_sequential() {
  local mismatch_count

  echo "Validating migrated event revisions are sequential per case"
  mismatch_count="$(psql_dst --quiet -tA <<'SQL'
with expected as (
    select
        ce.id,
        row_number() over (partition by ce.case_data_id order by ce.id) as expected_revision
    from ccd.case_event ce
    join ccd.case_data cd
      on cd.id = ce.case_data_id
    where cd.case_type_id = :'case_type'
)
select count(*)
from ccd.case_event ce
join expected e
  on e.id = ce.id
where ce.version is distinct from e.expected_revision
   or ce.case_revision is distinct from e.expected_revision;
SQL
)"

  if [[ "$mismatch_count" != "0" ]]; then
    echo "Event revision mismatch after migration: ${mismatch_count} event(s) out of sync" >&2
    exit 1
  fi
}

assert_no_orphans_or_duplicate_revisions() {
  local orphan_count significant_item_orphan_count duplicate_count

  echo "Validating no orphan events or duplicate event revisions"
  orphan_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event ce
left join ccd.case_data cd
  on cd.id = ce.case_data_id
where cd.id is null;
SQL
)"
  significant_item_orphan_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_event_significant_items item
left join ccd.case_event ce
  on ce.id = item.case_event_id
where ce.id is null;
SQL
)"
  duplicate_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from (
    select ce.case_data_id, ce.case_revision
    from ccd.case_event ce
    join ccd.case_data cd
      on cd.id = ce.case_data_id
    where cd.case_type_id = :'case_type'
    group by ce.case_data_id, ce.case_revision
    having count(*) > 1
) duplicates;
SQL
)"

  if [[ "$orphan_count" != "0" || "$significant_item_orphan_count" != "0" || "$duplicate_count" != "0" ]]; then
    echo "Invalid event state: orphans=${orphan_count}, significant_item_orphans=${significant_item_orphan_count}," \
      "duplicate revisions=${duplicate_count}" >&2
    exit 1
  fi
}

assert_case_event_sequence_is_safe() {
  local sequence_is_safe significant_item_sequence_is_safe

  echo "Validating case_event_id_seq is above migrated event ids"
  sequence_is_safe="$(psql_dst --quiet -tA <<'SQL'
select last_value >= (select coalesce(max(id), 1) from ccd.case_event)
from ccd.case_event_id_seq;
SQL
)"

  if [[ "$sequence_is_safe" != "t" ]]; then
    echo "case_event_id_seq was not reset above migrated event ids" >&2
    exit 1
  fi

  significant_item_sequence_is_safe="$(psql_dst --quiet -tA <<'SQL'
select last_value >= (select coalesce(max(id), 1) from ccd.case_event_significant_items)
from ccd.case_event_significant_items_id_seq;
SQL
)"

  if [[ "$significant_item_sequence_is_safe" != "t" ]]; then
    echo "case_event_significant_items_id_seq was not reset above migrated significant item ids" >&2
    exit 1
  fi
}

assert_es_queue_empty_if_present() {
  local queue_table queue_count

  echo "Validating bulk case_data load did not enqueue indexing rows"
  queue_table="$(psql_dst --quiet -tA -c "select to_regclass('ccd.es_queue') is not null;")"
  if [[ "$queue_table" != "t" ]]; then
    return
  fi

  queue_count="$(psql_dst --quiet -tA -c "select count(*) from ccd.es_queue;")"
  if [[ "$queue_count" != "0" ]]; then
    echo "Expected ccd.es_queue to be empty after trigger-suppressed migration, found ${queue_count}" >&2
    exit 1
  fi
}

assert_ttl_normalisation() {
  local source_ttl_rows target_ttl_rows target_blob_ttl_count target_resolved_ttl_column_count
  local source_event_ttl target_event_ttl

  echo "Validating current TTL is normalised and history TTL is preserved"
  source_ttl_rows="$(psql_src --quiet -tA <<'SQL'
select concat_ws(
    '|',
    id::text,
    coalesce(nullif(jsonb_extract_path_text(data, 'TTL', 'SystemTTL'), '')::date::text, ''),
    coalesce(nullif(jsonb_extract_path_text(data, 'TTL', 'OverrideTTL'), '')::date::text, ''),
    coalesce((case lower(jsonb_extract_path_text(data, 'TTL', 'Suspended'))
      when 'yes' then true
      when 'no' then false
      else null
    end)::text, '')
)
from case_data
where case_type_id = :'case_type'
order by id;
SQL
)"
  target_ttl_rows="$(psql_dst --quiet -tA <<'SQL'
select concat_ws(
    '|',
    id::text,
    coalesce(system_ttl::text, ''),
    coalesce(override_ttl::text, ''),
    coalesce(ttl_suspended::text, '')
)
from ccd.case_data
where case_type_id = :'case_type'
order by id;
SQL
)"
  target_blob_ttl_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from ccd.case_data
where case_type_id = :'case_type'
  and data ? 'TTL';
SQL
)"
  target_resolved_ttl_column_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from information_schema.columns
where table_schema = 'ccd'
  and table_name = 'case_data'
  and column_name = 'resolved_ttl';
SQL
)"
  source_event_ttl="$(psql_src --quiet -tA <<'SQL'
select data -> 'TTL'
from case_event
where id = 9101;
SQL
)"
  target_event_ttl="$(psql_dst --quiet -tA <<'SQL'
select data -> 'TTL'
from ccd.case_event
where id = 9101;
SQL
)"

  if [[ "$source_ttl_rows" != "$target_ttl_rows"
      || "$target_blob_ttl_count" != "0"
      || "$target_resolved_ttl_column_count" != "0"
      || "$source_event_ttl" != "$target_event_ttl" ]]; then
    echo "TTL migration mismatch:" \
      "source=${source_ttl_rows}, target=${target_ttl_rows}," \
      "target_blob_ttl=${target_blob_ttl_count}, resolved_ttl_columns=${target_resolved_ttl_column_count}," \
      "source_event_ttl=${source_event_ttl}, target_event_ttl=${target_event_ttl}" >&2
    exit 1
  fi
}

assert_case_event_constraints_present() {
  local fk_count index_count

  echo "Validating case_event FK and unique revision index are present"
  fk_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from pg_constraint
where conrelid = 'ccd.case_event'::regclass
  and conname = 'case_event_case_data_id_fkey';
SQL
)"
  index_count="$(psql_dst --quiet -tA <<'SQL'
select count(*)
from pg_indexes
where schemaname = 'ccd'
  and tablename = 'case_event'
  and indexname = 'idx_case_event_case_data_revision_unique';
SQL
)"

  if [[ "$fk_count" != "1" || "$index_count" != "1" ]]; then
    echo "Expected case_event FK/index to be present, found fk=${fk_count}, index=${index_count}" >&2
    exit 1
  fi
}

assert_common_migration_state() {
  assert_migrated_counts_match_source
  assert_out_of_scope_parent_event_not_migrated
  assert_case_revision_alignment
  assert_event_revisions_are_sequential
  assert_no_orphans_or_duplicate_revisions
  assert_case_event_sequence_is_safe
  assert_es_queue_empty_if_present
  assert_ttl_normalisation
  assert_case_event_constraints_present
}
