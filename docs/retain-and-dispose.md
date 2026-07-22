# Retain and dispose

Services remain responsible for defining retention policies, implemented in code rather than configuration.

A policy change is therefore a code change; it does not require a data migration that triggers an event across every existing case merely to rewrite retention TTLs.

The central retain and dispose component remains responsible for retention overrides, retention via linked cases and disposal of centrally held resources such as documents and the central case pointer.

Decentralised services are responsible for the deletion of locally held case data within their own databases.

## CCD definition configuration

A decentralised service must provide:

1. The terminal state `PendingDisposal`.
2. The event `MarkForDisposal`, which transitions an eligible case into `PendingDisposal` from eligible prior states, for example `Draft`.
3. The event `ConfirmDisposal`, which transitions `PendingDisposal` to itself and has a TTL increment of `0`. This is the only event that sets the disposal TTL.
4. A system user that can trigger both events and read cases in the `PendingDisposal` state.

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
      maximum-candidate-percentage: 5
      minimum-candidate-count: 10
      system-user:
        username: ${SYSTEM_USER}
        password: ${SYSTEM_USER_PASSWORD}
```

`mode` accepts:

| Mode | Behaviour |
| --- | --- |
| `off` | Default. The retain and dispose auto-configuration is not loaded. |
| `dry-run` | Reports what would be marked, retained or deleted without changing CCD or local data. |
| `live` | Marks eligible cases, confirms they are readable and reconciles expired local cases against CCD. |

## Candidate circuit breaker

Before marking any cases, the task groups the resolved candidates by case type and current state and compares each
group with all local cases in the same case type and state whose resolved TTL is null. It aborts the whole run when a
group contains at least `minimum-candidate-count` candidates and exceeds `maximum-candidate-percentage` of that
population. `maximum-candidate-percentage` defaults to `5` and `minimum-candidate-count` defaults to `10`. Set
`maximum-candidate-percentage` to `100` to disable the percentage circuit breaker. The same check runs in `dry-run`,
allowing an unsafe policy change to be detected before enabling live disposal.

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
2. Resolves references returned by `findCandidatesForDisposal()` against the configured case types, ignoring cases that are already pending disposal or have a resolved TTL.
3. Applies the candidate percentage circuit breaker across the complete candidate set.
4. Triggers `MarkForDisposal` for each candidate and verifies it enters `PendingDisposal`.
5. Reads each unconfirmed local `PendingDisposal` case from CCD using the configured system user. If the read succeeds, it triggers `ConfirmDisposal` to set the resolved TTL. If the read fails, including with a `404`, the task reports missing system-user read permission and aborts before reconciliation. The TTL remains null and the case cannot be deleted.
6. If CCD returns `404` for an expired confirmed case, invokes `dispose()` and deletes the local `ccd.case_data` row in one transaction.

In `dry-run`, the task acquires the same lock and performs the same candidate resolution and CCD reads, but only logs the action that would be taken. It never triggers either event, invokes `dispose()` or deletes local data. Because candidate cases are not moved into `PendingDisposal`, dry run can only assess confirmation and deletion for cases that are already in that state.

Failures while marking, confirming or reconciling an individual case are logged and do not prevent other cases being
attempted. A system-user visibility failure during the confirmation safety check aborts the current run before
reconciliation.
