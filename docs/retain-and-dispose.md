# Retain and dispose

Services remain responsible for defining retention policies for their case types and deleting the case data held in their own databases. CCD's retain and dispose component remains responsible for retention overrides, linked cases and disposal of centrally held resources such as documents and the central case pointer.

## CCD definition

A decentralised service must provide:

1. The terminal state `PendingDisposal`.
2. The event `MarkForDisposal`, which transitions an eligible case into that state.
3. A TTL increment of `0` on that event, and not on other events.
4. A system user that can trigger the event and read cases in the terminal state.

Services should configure only the permitted CCD states to transition into the terminal state.

## Service policy

Implement `RetainAndDisposePolicy` as a Spring bean.

For example, this policy identifies draft cases older than 365 days:

```java
@Component
@RequiredArgsConstructor
public class ExampleRetainAndDisposePolicy implements RetainAndDisposePolicy {

  private static final int DRAFT_RETENTION_DAYS = 365;
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
              and created_date::date + :retentionDays < current_date
            order by reference asc
            """,
        Map.of(
            "caseTypeIds", CASE_TYPES,
            "retentionDays", DRAFT_RETENTION_DAYS
        ),
        Long.class
    );
  }
}
```

`findCandidatesForDisposal()` returns the case references currently eligible for disposal. Policy selection is service-owned and may query the local database directly. References outside `caseTypes()` or no longer present locally are ignored.

Keeping eligibility policy in application code means a policy change is an ordinary reviewed and versioned code change. The new rule is evaluated against existing cases on the next run; changing it does not require a data migration that triggers an event across every existing case merely to rewrite retention data.

`dispose()` is optional and must only perform cleanup in the service database. The SDK invokes it in the same transaction as the deletion from `ccd.case_data`, allowing service tables to be cleaned up before the SDK's cascading deletion. Services whose tables use cascading foreign keys can use the default implementation.

Configure the system user with:

```yaml
ccd:
  decentralised-runtime:
    retain-and-dispose:
      system-user:
        username: ${SYSTEM_USER}
        password: ${SYSTEM_USER_PASSWORD}
```

## SDK task

When one policy bean exists, the SDK provides the `retainAndDisposeTask` `Runnable`. The service is responsible for invoking this task, normally from a nightly scheduled job.

On each run, `retainAndDisposeTask`:

1. Acquires a non-blocking database lock. A concurrent invocation exits without processing.
2. Resolves the references returned by `findCandidatesForDisposal()` against the configured case types.
3. Triggers `MarkForDisposal` for each candidate and verifies it enters `PendingDisposal`.
4. Reads each local `PendingDisposal` case from CCD using the configured system user.
5. Retains the local case when CCD returns it. Only a CCD `404` permits deletion.
6. On a `404`, invokes `dispose()` and conditionally deletes the local `ccd.case_data` row by case type and state in one transaction.

Failures for an individual case do not prevent other cases being attempted. Each failure is logged, then the task fails after processing so the scheduler can report and retry the run.
