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
    // metadata.reference(), caseTypeId(), state(); the case is locked
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
- Each case is done in a system event of its own, with the job's name as its id and name and "Run
  <run id>" as its summary. The event locks the case, so `apply` reads and writes as any event
  does, and audited tables can be written. Read the case in `apply`, not before. Unregistered in the
  CCD definition, the event is kept in the case's history and audit but CCD does not show it.
- `skipped()` rolls the event back, with anything the job wrote, so a case needing nothing gets no
  event and keeps its revision.
- The application needs a system user (`ccd.decentralised-runtime.system-user.id`) to run a job.
- One instance runs a run at a time, on a background thread. Every minute each instance tries to
  pick up a run that is not finished, so one whose instance went away is carried on by another.
  Each case's event has an idempotency key for the run and case, so a case done again after an
  instance stopped is not changed twice.
- Cases of the job's case types are taken in reference order. Cases created during a run are
  included only if their reference is after the case the run has reached.
- The run counts the cases applied and skipped, and logs them when it finishes.
- If `apply` throws, the run stops at that case and is not retried; the failure is logged and kept
  in `job_utils.case_job_run`. Fix the job and set a new run id.
- The `job_utils` schema is migrated with the SDK's other library schemas.
