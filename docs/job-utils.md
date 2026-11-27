# Job utils

`sdk/job-utils` (`com.github.hmcts:job-utils`) runs one-off work over a service's cases, such as a
backfill, inside the application: no extra release, and each environment opts in through its own
config.

```groovy
implementation 'com.github.hmcts:job-utils'
```

```java
@Component
class BackfillCaseBundles implements CaseJob {
  public String name() { return "backfill-case-bundles"; }
  public Set<String> caseTypes() { return Set.of("PCS"); }
  public CaseJobOutcome apply(CaseMetadata metadata) {
    // metadata.reference(), caseTypeId(), state(), revision()
    return CaseJobOutcome.applied();   // or skipped() if the case needed nothing
  }
}
```

The job runs in an environment once `ccd.jobs.run.<name>` is set there to a run id, for example in
its flux values:

```yaml
CCD_JOBS_RUN_BACKFILL_CASE_BUNDLES: "2026-11-27"
```

- A run id runs once. Set a new one to run the job again; remove it once it is no longer needed.
- One instance runs a run at a time, on a background thread. Every minute each instance tries to
  pick up a run that is not finished, so one whose instance went away is carried on by another.
- Cases of the job's case types are taken in reference order. Cases created during a run are
  included only if their reference is after the case the run has reached.
- `apply` runs in its own transaction with the case locked, as an event locks it, so an event cannot
  change the case between what the job reads and what it writes. That transaction also records the
  case as done, so each case is done once. `applyIn()` changes this:
  - `TRANSACTION`: no lock, for a job that only reads.
  - `NO_TRANSACTION`: for a job that changes the case through `SystemEventExecutor`. Audited tables
    can only be written in an event, and a system event takes the lock and its own transaction. The
    case is recorded as done after `apply` returns, so pass `metadata.idempotencyKey()` to the
    event: it is the same each time the run applies the job to that case, so the event runs once.
- The run counts the cases `apply` reports as applied and skipped, and logs them when it finishes.
- If `apply` throws, the run stops at that case and is not retried; the failure is logged and kept
  in `job_utils.case_job_run`. Fix the job and set a new run id.
- The `job_utils` schema is migrated with the SDK's other library schemas.
