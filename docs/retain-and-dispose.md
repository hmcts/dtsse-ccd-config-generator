# Retain and dispose

Services remain responsible for defining retention policies for their case types and deleting the case data held in their own databases. CCD's retain and dispose component remains responsible for retention overrides, linked cases and disposal of centrally held resources such as documents and the central case pointer.

## CCD definition

A decentralised service must provide:

1. The terminal state `PendingDisposal`.
2. The event `MarkForDisposal`, which transitions an eligible case into that state.
3. A TTL increment of `0` on that event, and not on other events.
4. A system user that can trigger the event and read cases in the terminal state.

Only states permitted by the service's policy should be able to transition into the terminal state.

## Service policy

Implement `RetainAndDisposePolicy` as a Spring bean:

```java
public interface RetainAndDisposePolicy {
  Set<String> caseTypes();
  List<Long> findCandidatesForDisposal();
  default void dispose(long caseReference) { }
}
```

`findCandidatesForDisposal()` returns the case references currently eligible for disposal. Policy selection is service-owned and may query the local database directly. Cases outside `caseTypes()` are rejected; references no longer present locally are ignored.

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

For each run, the SDK:

1. Acquires a non-blocking PostgreSQL advisory lock for the service database. A concurrent invocation exits without processing.
2. Resolves the policy's candidate references against the local database.
3. Triggers `MarkForDisposal` for each candidate and verifies the resulting `PendingDisposal` state.
4. Reads every local case in the terminal state through CCD as the configured system user.
5. Retains the local case after a successful CCD read. Only a CCD `404` permits local deletion.
6. Calls `dispose()` and conditionally deletes `ccd.case_data` by case type and terminal state in one transaction.

Failures for an individual case do not prevent other cases being attempted, but the task fails after processing so the scheduler can report and retry the run.
