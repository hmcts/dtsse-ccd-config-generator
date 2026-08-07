# Database auditing

The decentralised runtime can audit row-level changes made by a service during a CCD case event. Auditing is opt-in: a
service attaches the runtime trigger to each service-owned table it wants to audit.

Audit entries are written to `ccd.audit_log` in the same transaction as the service data and the corresponding
`ccd.case_event`.

## Data model

Each audit entry contains:

| Column | Meaning |
| --- | --- |
| `id` | Monotonically increasing audit entry identifier and ordering within an event |
| `case_event_id` | Deferred, cascading foreign key to `ccd.case_event.id` |
| `table_schema` / `table_name` | Origin of the changed row |
| `operation` | `INSERT`, `UPDATE` or `DELETE` from the `ccd.audit_operation` enum |
| `old_values` | Complete row before an update or delete, represented as JSONB |
| `new_values` | Complete row after an insert or update, represented as JSONB |

Every inserted, updated or deleted row produces one audit entry, including an update that leaves the stored values
unchanged. A statement affecting several rows produces one audit entry per row.

## Enabling a table

Enable auditing for a table by calling the runtime helper in an application Flyway migration:

```sql
call ccd.attach_case_event_auditing_v1('public.case_notes'::regclass);
```

Calling the helper more than once for the same table fails because the audit trigger already exists.

Audited writes must use the same database transaction as the decentralised event; attempted writes to audited tables
outside an event context are rejected.

Note that `TRUNCATE` does not run row-level triggers and is not audited.

## Zero downtime rollout

The SDK upgrade and trigger migration must be deployed in separate releases.

1. Deploy the latest decentralised runtime, without any audit triggers, to every application instance.
2. In a later release deploy the Flyway migration that adds the audit triggers.

## Querying changes

Join to `ccd.case_event` for event time and actor information:

```sql
select
    event.created_date,
    event.event_id,
    event.user_id,
    audit.table_schema,
    audit.table_name,
    audit.operation,
    audit.old_values,
    audit.new_values
from ccd.audit_log audit
join ccd.case_event event on event.id = audit.case_event_id
where audit.case_event_id = :case_event_id
order by audit.id;
```

For an update, changed columns can be derived:

```sql
select old_value.key,
       old_value.value as old_value,
       new_value.value as new_value
from ccd.audit_log audit
cross join lateral jsonb_each(audit.old_values) old_value
join lateral jsonb_each(audit.new_values) new_value using (key)
where audit.id = :audit_id
  and audit.operation = 'UPDATE'
  and old_value.value is distinct from new_value.value;
```

## Retention and maintenance

Audit entries are deleted with their `ccd.case_event` during case disposal. The disposal transaction sets
`ccd.audit_disabled` while invoking the service disposal policy and deleting `ccd.case_data`. This lets foreign-key
cascades remove audited service rows without creating audit entries that would immediately be deleted.
