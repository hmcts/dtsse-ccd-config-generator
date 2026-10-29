-- Fails unless the SDK's ccd schema has been migrated first.
create view case_references as select reference from ccd.case_data;
