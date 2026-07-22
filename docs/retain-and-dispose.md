# Retain and dispose

Services remain responsible for defining retention policies, implemented in code rather than configuration.

A policy change is therefore a code change; it does not require a data migration that triggers an event across every existing case merely to rewrite retention TTLs.

The central retain and dispose component remains responsible for retention overrides, retention via linked cases and disposal of centrally held resources such as documents and the central case pointer.

Decentralised services are responsible for the deletion of locally held case data within their own databases.

## CCD definition configuration

A decentralised service must provide:

1. The terminal state `PendingDisposal`.
2. The event `MarkForDisposal`, which transitions an eligible case into `PendingDisposal` from eligible prior states, for example `Draft`.
3. A TTL increment of `0` on that event, and not on other events.
4. A system user that can trigger the event and read cases in the terminal state.

Services should configure only the permitted CCD states to transition into the terminal state.

## Service policy

Implement `RetainAndDisposePolicy` as a Spring bean.

For example, this policy identifies draft cases that have been inactive for more than 365 days:

```java
@Component
@RequiredArgsConstructor
public class ExampleRetainAndDisposePolicy implements RetainAndDisposePolicy {

  private static final int DRAFT_INACTIVITY_DAYS = 365;
  private static final Set<String> CASE_TYPES = Set.of(
      "EXAMPLE_CASE_TYPE"
  );

  private final NamedParameterJdbcTemplate jdbc;

  @Override
  public Set<String> caseTypes() {
    return CASE_TYPES;
  }

  @Override
  public List<Long> findCandidatesForDisposal() {
    return jdbc.queryForList(
        """
            select reference
            from ccd.case_data
            where case_type_id in (:caseTypeIds)
              and state = 'DRAFT'
              and last_modified::date + :inactivityDays < current_date
            order by reference asc
            """,
        Map.of(
            "caseTypeIds", CASE_TYPES,
            "inactivityDays", DRAFT_INACTIVITY_DAYS
        ),
        Long.class
    );
  }
}
```

`findCandidatesForDisposal()` returns the case references currently eligible for disposal. Policy selection is service-owned and may query the local database directly. References outside `caseTypes()` or no longer present locally are ignored.


`dispose()` is optional and must only perform cleanup in the service database. The SDK invokes it in the same transaction as the deletion from `ccd.case_data`, allowing service tables to be cleaned up before the SDK's cascading deletion. Services whose tables use cascading foreign keys can use the default implementation.

Configure the execution mode, schedule and system user with:

```yaml
ccd:
  decentralised-runtime:
    retain-and-dispose:
      mode: dry-run
      cron: "0 0 2 * * *"
      zone: UTC
      system-user:
        username: ${SYSTEM_USER}
        password: ${SYSTEM_USER_PASSWORD}
```

`mode` accepts:

| Mode | Behaviour |
| --- | --- |
| `off` | Default. The retain and dispose auto-configuration is not loaded. |
| `dry-run` | Reports what would be marked, retained or deleted without changing CCD or local data. |
| `live` | Marks eligible cases and reconciles local terminal cases against CCD. |

## SDK task

When one policy bean exists and the mode is `dry-run` or `live`, the SDK schedules the task using the configured cron expression and time zone. The cron defaults to `0 0 2 * * *` and the time zone defaults to `UTC`, meaning the task runs every day at 02:00 UTC.

The consuming application must enable Spring scheduling. For example:

```java
@Configuration
@EnableScheduling
class SchedulingConfiguration {
}
```

On each run, `retainAndDisposeTask`:

1. Acquires a non-blocking database lock. A concurrent invocation exits without processing.
2. Resolves the references returned by `findCandidatesForDisposal()` against the configured case types.
3. Triggers `MarkForDisposal` for each candidate and verifies it enters `PendingDisposal`.
4. Reads each local `PendingDisposal` case from CCD using the configured system user.
5. Retains the local case when CCD returns it. Only a CCD `404` permits deletion.
6. On a `404`, invokes `dispose()` and conditionally deletes the local `ccd.case_data` row by case type and state in one transaction.

In `dry-run`, the task acquires the same lock and performs the same candidate resolution and CCD existence reads, but only logs the action that would be taken. It never triggers `MarkForDisposal`, invokes `dispose()` or deletes local data. Because candidate cases are not moved into `PendingDisposal`, dry run can only assess deletion for cases that are already in that state.

Failures for an individual case do not prevent other cases being attempted. Each failure is logged and the case remains eligible for a later run.
