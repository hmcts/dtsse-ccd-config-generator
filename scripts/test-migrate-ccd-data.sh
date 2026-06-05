#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/migration-test/lib.sh
source "${SCRIPT_DIR}/migration-test/lib.sh"

MIGRATION_SCRIPT="${SCRIPT_DIR}/migrate-ccd-data.sh"
CLEAN_SCRIPT="${SCRIPT_DIR}/clean-target-case-data.sh"

init_migration_test_env "classic"
trap cleanup_temp_dbs EXIT

create_temp_dbs
seed_source_data
clear_target_data

assert_case_event_constraints_present

echo "Running migration script (validation mode only)"
SRC_DSN="$SRC_DSN" DST_DSN="$DST_DSN" CASE_TYPE="$CASE_TYPE" "$MIGRATION_SCRIPT"

echo "Running migration script (apply mode)"
SRC_DSN="$SRC_DSN" DST_DSN="$DST_DSN" CASE_TYPE="$CASE_TYPE" "$MIGRATION_SCRIPT" --apply

assert_common_migration_state

echo "Running cleanup script on target"
DST_DSN="$DST_DSN" CASE_TYPE="$CASE_TYPE" "$CLEAN_SCRIPT"
assert_target_empty

echo "Test migration (validate + apply + cleanup) completed successfully."
