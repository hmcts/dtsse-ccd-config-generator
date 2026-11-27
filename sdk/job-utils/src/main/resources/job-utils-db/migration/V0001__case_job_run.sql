-- One row per run of a case job: how far it has got, which instance holds it, and how it ended.
create table job_utils.case_job_run (
  job_name text not null,
  run_id text not null,
  last_reference bigint not null default 0,
  applied bigint not null default 0,
  skipped bigint not null default 0,
  lease_token uuid,
  lease_until timestamptz,
  finished_at timestamptz,
  failed_at timestamptz,
  failed_reference bigint,
  failure text,
  primary key (job_name, run_id)
);
