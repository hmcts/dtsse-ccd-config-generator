create type ccd.audit_operation as enum ('INSERT', 'UPDATE', 'DELETE');

create table ccd.audit_log (
    id bigserial primary key,
    case_event_id bigint not null references ccd.case_event(id)
        on delete cascade deferrable initially deferred,
    table_schema text not null,
    table_name text not null,
    operation ccd.audit_operation not null,
    old_values jsonb,
    new_values jsonb,
    constraint audit_log_row_values_check check (
        (operation = 'INSERT' and old_values is null and new_values is not null)
        or (operation = 'UPDATE' and old_values is not null and new_values is not null)
        or (operation = 'DELETE' and old_values is not null and new_values is null)
    )
);

create index idx_audit_log_case_event_id on ccd.audit_log(case_event_id);

create function ccd.audit_row_change()
returns trigger
language plpgsql
as $$
declare
    audit_case_event_id bigint;
    audit_disabled text;
begin
    audit_disabled := nullif(current_setting('ccd.audit_disabled', true), '');
    if lower(coalesce(audit_disabled, 'false')) in ('true', 'on', '1') then
        if tg_op = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    begin
        audit_case_event_id := nullif(current_setting('ccd.case_event_id', true), '')::bigint;
    exception
        when invalid_text_representation then
            raise exception using
                errcode = '23514',
                message = 'Invalid ccd.case_event_id audit context';
    end;

    if audit_case_event_id is null then
        raise exception using
            errcode = '23514',
            message = format(
                'Audited %s on %I.%I requires a CCD case event context',
                tg_op,
                tg_table_schema,
                tg_table_name
            ),
            hint = 'Perform this write inside a CCD case event transaction';
    end if;

    insert into ccd.audit_log (
        case_event_id,
        table_schema,
        table_name,
        operation,
        old_values,
        new_values
    ) values (
        audit_case_event_id,
        tg_table_schema,
        tg_table_name,
        tg_op::ccd.audit_operation,
        case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) end,
        case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) end
    );

    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;
