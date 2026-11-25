-- What a completed job rendered: the compiled request (for a selector-driven job, the documents
-- it selected), where each document landed, which were replaced by placeholder pages and why,
-- the warnings, and the finished PDF's file name, size, checksum and page count. Written with
-- the completed state, from the same render the completion handler stored, so a service can
-- describe a bundle it holds without keeping its own copy of this metadata.
alter table bundling.bundle_job add column report jsonb;
