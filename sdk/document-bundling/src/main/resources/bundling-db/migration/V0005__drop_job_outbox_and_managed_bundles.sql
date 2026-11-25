-- The job outbox and managed (always up to date) bundles are removed. Neither was enabled in any
-- environment, so both tables are empty wherever these migrations ran.
drop table bundling.bundle;
drop table bundling.bundle_job;
