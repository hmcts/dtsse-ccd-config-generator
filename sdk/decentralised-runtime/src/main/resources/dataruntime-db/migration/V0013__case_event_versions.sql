alter table ccd.case_event
    add column if not exists version int,
    add column if not exists case_revision bigint;

update ccd.case_event ce
set version = cd.version,
    case_revision = cd.case_revision
from ccd.case_data cd
where ce.case_data_id = cd.id
  and (ce.version is null or ce.case_revision is null);

alter table ccd.case_event
    alter column version set not null,
    alter column case_revision set not null;
