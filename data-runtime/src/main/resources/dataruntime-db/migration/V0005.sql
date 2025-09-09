-- We are going to need CCD's internal case IDs for ElasticSearch indexing.
-- The internal ID serves as CCD's unique ID in ES so decentralised services must have it.
alter table ccd.case_data
add column id bigint;

-- Backfill any existing cases using the case reference which has hitherto served this purpose.
-- (This will have no effect on any prod env being prior to any onboarding but helps out dev setups)
update ccd.case_data
set id = reference;

-- Make the column not null and unique.
alter table ccd.case_data
alter column id set not null;

create unique index idx_case_data_id on ccd.case_data (id);

-- Update case_event table to use the new id column instead of reference
-- First drop the existing foreign key constraint
alter table ccd.case_event
drop constraint case_event_case_reference_fkey;

-- Rename the column from case_reference to case_data_id
alter table ccd.case_event
rename column case_reference to case_data_id;

-- Add the new foreign key constraint referencing case_data.id
alter table ccd.case_event
add constraint case_event_case_data_id_fkey foreign key (case_data_id) references ccd.case_data(id);

-- Update es_queue table to use case_data_id for consistency
-- First drop the existing foreign key constraint
alter table ccd.es_queue
drop constraint es_queue_reference_fkey;

delete from ccd.es_queue;

-- Rename the column from reference to case_data_id
alter table ccd.es_queue
rename column reference to case_data_id;

-- Add the new foreign key constraint
alter table ccd.es_queue
add constraint es_queue_case_data_id_fkey foreign key (case_data_id) references ccd.case_data(id);

-- Update the trigger function to use the new column name
create or replace function add_to_es_queue() returns trigger
  language plpgsql
    as $$
begin
insert into ccd.es_queue (case_data_id, id)
values (new.case_data_id, new.id)
  on conflict (case_data_id)
                do update set id = excluded.id
                   where es_queue.id < excluded.id;
return new;
end $$;
