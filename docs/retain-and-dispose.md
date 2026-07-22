# Retain and dispose

Decentralised Services remain responsible for defining retention policies, implemented in code rather than configuration.

An advantage is that retention policy changes are code changes; migrations of existing cases are not required.

The central retain and dispose component remains responsible for retention via linked cases and disposal of centrally held resources such as documents and CCD case pointers.

Decentralised services are responsible for the deletion of locally held case data within their own databases.

## CCD definition configuration

A decentralised case type must provide:

1. The terminal state `PendingDisposal`.
2. The event `MarkForDisposal`, which transitions an eligible case into `PendingDisposal` from eligible prior states, for example `Draft` if you wish to dispose of inactive drafts.
3. The event `ConfirmDisposal`, which transitions `PendingDisposal` to itself and has a TTL increment of `0`. This is the only event that sets the disposal TTL.
4. A system user that can trigger `MarkForDisposal` and `ConfirmDisposal` events and read cases in the `PendingDisposal` state.

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

`findCandidatesForDisposal()` returns case references eligible for disposal.

`dispose(long caseReference)` is a hook for performing cleanup in the service database, invoked in the same transaction as the sdk deletes from its `ccd` tables, allowing service tables to be cleaned up before the SDK's cascading deletion.


Configure the execution mode, schedule and system user with:

```yaml
ccd:
  decentralised-runtime:
    retain-and-dispose:
      mode: dry-run
      cron: "0 0 2 * * *"
      system-user:
        username: ${SYSTEM_USER}
        password: ${SYSTEM_USER_PASSWORD}
```

`mode` accepts:

| Mode | Behaviour |
| --- | --- |
| `off` | Default. The retain and dispose auto-configuration is not loaded. |
| `dry-run` | Reports what would be marked, retained or deleted without changing CCD or local data. |
| `live` | Marks eligible cases, confirms they are readable and deletes expired local cases that are missing from CCD. |

## Circuit breakers

The SDK performs a statistical check of the references returned by `findCandidatesForDisposal()`.

A run is aborted if more than `maximum-candidate-percentage` of cases in a given state are returned as eligible - default 5%.

Eg. in the preceding Java policy the run would log an error and abort if more than 5% of all cases in the `DRAFT` state were returned as eligible for disposal.

The same check runs in `dry-run`, allowing policy changes to be tested before enablement.

To handle low population case types the circuit breaker only applies once `minimum-candidate-count` is reached: default 10

## Scheduling

The consuming application must enable Spring scheduling. For example:

```java
@Configuration
@EnableScheduling
class SchedulingConfiguration {
}
```

On each run, `retainAndDisposeTask`:

1. Acquires a database lock to prevent parallel invocations.
2. Resolves references returned by `findCandidatesForDisposal()`.
3. Applies circuit breaker checks
4. Triggers `MarkForDisposal` for each candidate and verifies it enters `PendingDisposal`.
5. Reads each unconfirmed local `PendingDisposal` case from CCD using the configured system user. If the read succeeds, it triggers `ConfirmDisposal` to set the resolved TTL. If the read fails, including with a `404`, the task reports missing system-user read permission and aborts. The TTL remains null and the case cannot be deleted.
6. If CCD returns `404` for an expired confirmed case, invokes `dispose()` and deletes the local `ccd.case_data` row in one transaction.

In `dry-run`, the task acquires the same lock and performs the same candidate resolution and CCD reads, but only logs the action that would be taken. It never triggers either event, invokes `dispose()` or deletes local data. Because candidate cases are not moved into `PendingDisposal`, dry run can only assess confirmation and deletion for cases that are already in that state.

Failures while marking, confirming or deleting an individual local case are logged and do not prevent other cases being
attempted. A system-user visibility failure during the confirmation safety check aborts the current run.
