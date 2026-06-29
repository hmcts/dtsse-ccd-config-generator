#!/usr/bin/env bash

set -euo pipefail

# ------------------------------------------------------------------------------
# CCD data migration FDW setup
#
# Creates the FDW objects used by migrate-ccd-data-fdw.sh:
#   - postgres_fdw and pgcrypto extensions
#   - FDW staging schema
#   - FDW server and user mapping
#   - foreign tables for source case_data and case_event
#
# This script is intended to be run once by the team with database privileges.
# ------------------------------------------------------------------------------

DST_DSN="${DST_DSN:-postgresql://postgres:postgres@localhost:5432/postgres}"

SRC_HOST="${SRC_HOST:-localhost}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_DB="${SRC_DB:-datastore}"
SRC_SCHEMA="${SRC_SCHEMA:-public}"
SRC_USER="${SRC_USER:-postgres}"
SRC_PASSWORD="${SRC_PASSWORD:-postgres}"
SRC_SSLMODE="${SRC_SSLMODE:-require}"

DST_SCHEMA="${DST_SCHEMA:-ccd}"
FDW_SCHEMA="${FDW_SCHEMA:-fdw_stage}"
FDW_SERVER="${FDW_SERVER:-src_ccd_server}"
LOCAL_USER_SQL="${LOCAL_USER_SQL:-current_user}"

DO_APPLY=false

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$*"
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [--apply]

Default mode validates the destination connection and prints the FDW setup that would be used.
Use --apply to create or replace the FDW server, user mapping and foreign tables.

Environment variables:
  DST_DSN
  SRC_HOST
  SRC_PORT
  SRC_DB
  SRC_SCHEMA
  SRC_USER
  SRC_PASSWORD
  SRC_SSLMODE
  DST_SCHEMA
  FDW_SCHEMA
  FDW_SERVER
  LOCAL_USER_SQL local role that will run the migration; defaults to current_user

Example:
  export DST_DSN='postgresql://user:pass@dest.postgres.database.azure.com:5432/appdb?sslmode=require'
  export SRC_HOST='source.postgres.database.azure.com'
  export SRC_PORT='5432'
  export SRC_DB='ccd_data_store'
  export SRC_SCHEMA='public'
  export SRC_USER='readonly_user'
  export SRC_PASSWORD='...'
  export SRC_SSLMODE='require'
  export LOCAL_USER_SQL='current_user'

  ./scripts/setup-ccd-data-fdw.sh
  ./scripts/setup-ccd-data-fdw.sh --apply
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

require_psql() {
  if ! command -v psql >/dev/null 2>&1; then
    log "ERROR: psql is required on PATH"
    exit 1
  fi
}

psql_dst() {
  psql "$DST_DSN" \
    --set=ON_ERROR_STOP=on \
    --no-psqlrc \
    --set=src_host="$SRC_HOST" \
    --set=src_port="$SRC_PORT" \
    --set=src_db="$SRC_DB" \
    --set=src_schema="$SRC_SCHEMA" \
    --set=src_user="$SRC_USER" \
    --set=src_password="$SRC_PASSWORD" \
    --set=src_sslmode="$SRC_SSLMODE" \
    --set=dst_schema="$DST_SCHEMA" \
    --set=fdw_schema="$FDW_SCHEMA" \
    --set=fdw_server="$FDW_SERVER" \
    --set=local_user_sql="$LOCAL_USER_SQL" \
    "$@"
}

validate_connection() {
  log "Validating destination connection..."
  psql_dst --quiet -c "select 1;" >/dev/null
}

print_configuration() {
  cat <<EOF
FDW setup configuration:
  Destination DSN: ${DST_DSN}
  Target schema:   ${DST_SCHEMA}
  FDW schema:      ${FDW_SCHEMA}
  FDW server:      ${FDW_SERVER}
  Local user SQL:  ${LOCAL_USER_SQL}
  Source host:     ${SRC_HOST}
  Source port:     ${SRC_PORT}
  Source database: ${SRC_DB}
  Source schema:   ${SRC_SCHEMA}
  Source user:     ${SRC_USER}
  Source sslmode:  ${SRC_SSLMODE}
EOF
}

setup_extensions() {
  log "Creating required extensions..."
  psql_dst --quiet <<'SQL'
create extension if not exists postgres_fdw;
create extension if not exists pgcrypto;
SQL
}

setup_fdw() {
  log "Creating FDW server, user mapping and foreign tables..."

  psql_dst <<'SQL'
create schema if not exists :"fdw_schema";

drop foreign table if exists :"fdw_schema".case_event;
drop foreign table if exists :"fdw_schema".case_data;

drop server if exists :"fdw_server" cascade;

create server :"fdw_server"
foreign data wrapper postgres_fdw
options (
  host :'src_host',
  port :'src_port',
  dbname :'src_db',
  sslmode :'src_sslmode'
);

create user mapping for :local_user_sql
server :"fdw_server"
options (
  user :'src_user',
  password :'src_password'
);

create foreign table :"fdw_schema".case_data (
  reference bigint,
  version integer,
  created_date timestamp without time zone,
  security_classification :"dst_schema".securityclassification,
  last_state_modified_date timestamp without time zone,
  resolved_ttl date,
  last_modified timestamp without time zone,
  jurisdiction varchar(255),
  case_type_id varchar(255),
  state varchar(255),
  data jsonb,
  supplementary_data jsonb,
  id bigint
)
server :"fdw_server"
options (
  schema_name :'src_schema',
  table_name 'case_data'
);

create foreign table :"fdw_schema".case_event (
  id bigint,
  created_date timestamp without time zone,
  security_classification :"dst_schema".securityclassification,
  case_data_id bigint,
  case_type_version integer,
  event_id varchar(70),
  summary varchar(1024),
  description varchar(65536),
  user_id varchar(64),
  case_type_id varchar(255),
  state_id varchar(255),
  data jsonb,
  user_first_name varchar(255),
  user_last_name varchar(255),
  event_name varchar(30),
  state_name varchar(255),
  proxied_by varchar(64),
  proxied_by_first_name varchar(255),
  proxied_by_last_name varchar(255)
)
server :"fdw_server"
options (
  schema_name :'src_schema',
  table_name 'case_event'
);
SQL
}

grant_fdw_access() {
  log "Granting FDW access to ${LOCAL_USER_SQL}..."

  psql_dst <<'SQL'
grant usage on foreign server :"fdw_server" to :local_user_sql;
grant usage on schema :"fdw_schema" to :local_user_sql;
grant select on :"fdw_schema".case_data, :"fdw_schema".case_event to :local_user_sql;
SQL
}

validate_fdw_tables() {
  log "Validating FDW foreign tables..."

  psql_dst <<'SQL'
select 'fdw case_data table' as check_name, to_regclass(:'fdw_schema' || '.case_data') as relation;
select 'fdw case_event table' as check_name, to_regclass(:'fdw_schema' || '.case_event') as relation;

select 'source case_data reachable' as check_name, exists(
  select 1
  from :"fdw_schema".case_data
  limit 1
) as reachable;

select 'source case_event reachable' as check_name, exists(
  select 1
  from :"fdw_schema".case_event
  limit 1
) as reachable;
SQL
}

main() {
  parse_args "$@"
  require_psql

  validate_connection
  print_configuration

  if [[ "$DO_APPLY" != "true" ]]; then
    log "Dry run complete. Re-run with --apply to create FDW objects."
    exit 0
  fi

  setup_extensions
  setup_fdw
  grant_fdw_access
  validate_fdw_tables

  log "FDW setup completed successfully."
}

main "$@"
