package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.impl.PostgresAdvisoryLock;

@Slf4j
@RequiredArgsConstructor
public final class RetainAndDisposeTask implements Runnable {

  private static final String LOCK_NAMESPACE = "ccd-retain-and-dispose";
  private static final String LOCK_NAME = "task";

  private final RetainAndDisposePolicy policy;
  private final RetainAndDisposeRepository repository;
  private final CoreCaseDataRetainAndDisposeClient ccdClient;
  private final RetainAndDisposeCaseReconciler caseReconciler;
  private final PostgresAdvisoryLock advisoryLock;

  @Override
  public void run() {
    Set<String> caseTypeIds = policy.caseTypes();
    log.info("Starting retain and dispose task caseTypeIds={}", caseTypeIds);

    boolean acquired = advisoryLock.runIfAcquired(
        LOCK_NAMESPACE,
        LOCK_NAME,
        () -> {
          RetainAndDisposeFailures failures = new RetainAndDisposeFailures();
          markEligibleCasesForDisposal(caseTypeIds, failures);
          reconcilePendingDisposalCases(caseTypeIds, failures);
          failures.throwIfAny();
        }
    );
    if (!acquired) {
      log.info("Retain and dispose task is already running for caseTypeIds={}; skipping this invocation",
          caseTypeIds);
    } else {
      log.info("Completed retain and dispose task caseTypeIds={}", caseTypeIds);
    }
  }

  private void markEligibleCasesForDisposal(
      Set<String> caseTypeIds,
      RetainAndDisposeFailures failures
  ) {
    List<RetainAndDisposeCase> candidates = repository.findCases(
        policy.findCandidatesForDisposal(),
        caseTypeIds
    );
    log.info("Found retain and dispose candidates count={} caseTypeIds={}", candidates.size(), caseTypeIds);
    for (RetainAndDisposeCase candidate : candidates) {
      failures.attempt(candidate.reference(), "markForDisposal", () -> ccdClient.markForDisposal(candidate));
    }
  }

  private void reconcilePendingDisposalCases(
      Set<String> caseTypeIds,
      RetainAndDisposeFailures failures
  ) {
    List<RetainAndDisposeCase> terminalCases = repository.findCasesInState(caseTypeIds, DISPOSAL_STATE_ID);
    log.info("Reconciling pending disposal cases count={} caseTypeIds={}", terminalCases.size(), caseTypeIds);
    for (RetainAndDisposeCase terminalCase : terminalCases) {
      failures.attempt(
          terminalCase.reference(),
          "reconcilePendingDisposal",
          () -> caseReconciler.reconcile(terminalCase)
      );
    }
  }
}
