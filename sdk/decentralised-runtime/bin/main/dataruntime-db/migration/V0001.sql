create schema if not exists ccd;
SET search_path TO ccd;
CREATE TYPE securityclassification AS ENUM (
    'PUBLIC',
    'PRIVATE',
    'RESTRICTED'
);

CREATE TABLE case_data (
                                reference bigint primary key,
                                version integer not null DEFAULT 1,
                                created_date timestamp without time zone DEFAULT now() NOT NULL,
                                security_classification ccd.securityclassification NOT NULL,
                                last_state_modified_date timestamp without time zone,
                                resolved_ttl date,
                                last_modified timestamp without time zone,
                                jurisdiction character varying(255) NOT NULL,
                                case_type_id character varying(255) NOT NULL,
                                state character varying(255) NOT NULL,
                                data jsonb NOT NULL,
                                supplementary_data jsonb
);


CREATE TABLE case_event (
                                 id bigserial primary key,
                                 created_date timestamp without time zone DEFAULT now() NOT NULL,
                                 security_classification securityclassification NOT NULL,
                                 case_reference bigint NOT NULL references ccd.case_data(reference),
                                 case_type_version integer NOT NULL,
                                 event_id character varying(70) NOT NULL,
                                 summary character varying(1024),
                                 description character varying(65536),
                                 user_id character varying(64) NOT NULL,
                                 case_type_id character varying(255) NOT NULL,
                                 state_id character varying(255) NOT NULL,
                                 data jsonb NOT NULL,
                                 user_first_name character varying(255) DEFAULT NULL::character varying NOT NULL,
                                 user_last_name character varying(255) DEFAULT NULL::character varying NOT NULL,
                                 event_name character varying(30) DEFAULT NULL::character varying NOT NULL,
                                 state_name character varying(255) DEFAULT ''::character varying NOT NULL,
                                 proxied_by character varying(64),
                                 proxied_by_first_name character varying(255),
                                 proxied_by_last_name character varying(255)
);

create table submitted_callback_queue (
                                            id bigserial primary key,
                                            case_event_id bigint not null references case_event(id),
                                            event_id text not null,
                                            operation_id uuid,
                                            payload jsonb not null,
                                            headers jsonb not null,
                                            attempted_at timestamp,
                                            exception bytea,
                                            exception_message text
);

create view failed_jobs as
select
  case_reference as reference,
  q.id as job_id,
  operation_id,
  q.attempted_at,
  ce.id,
  ce.event_id,
  ce.state_id,
  exception,
  exception_message
  from
  submitted_callback_queue q
  join case_event ce on ce.id = q.case_event_id
  where q.attempted_at is not null;

create table case_event_audit (
                                id bigserial primary key,
                                case_event_id bigint not null references case_event(id),
                                user_id uuid not null,
                                data jsonb not null
);

create function audit_case_event_changes()
  returns trigger as $$
begin
insert into case_event_audit(case_event_id, user_id, data)
select new.id, current_setting('ccd.user_idam_id')::uuid, new.data;
return new;
end;
$$ language plpgsql;

create trigger audit_case_event_changes
  after update of data on case_event
  for each row
  execute function audit_case_event_changes();


create table es_queue (
                        reference bigint references case_data(reference) primary key,
                        id bigint references case_event(id)
);

create function add_to_es_queue() returns trigger
  language plpgsql
    as $$
begin
insert into ccd.es_queue (reference, id)
values (new.case_reference, new.id)
  on conflict (reference)
                do update set id = excluded.id
                   where es_queue.id < excluded.id;
return new;
end $$;

create trigger after_case_event_insert
  after insert on case_event
  for each row
  execute function add_to_es_queue();

create or replace function update_last_state_modified_date()
returns trigger as $$
begin
    -- check if the state field has changed
    if new.state is distinct from old.state then
        -- update the last_state_modified_date to the current timestamp
        new.last_state_modified_date := now();
end if;
return new;
end;
$$ language plpgsql;

create trigger trigger_update_last_state_modified_date
  before insert or update on case_data
                     for each row
                     execute function update_last_state_modified_date();
