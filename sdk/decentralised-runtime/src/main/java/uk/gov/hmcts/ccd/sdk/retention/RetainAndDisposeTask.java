package uk.gov.hmcts.ccd.sdk.retention;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;
import uk.gov.hmcts.ccd.sdk.impl.PostgresAdvisoryLock;
import uk.gov.hmcts.ccd.sdk.retention.RetainAndDisposeProperties.Mode;

@Slf4j
@RequiredArgsConstructor
final class RetainAndDisposeTask implements Runnable {

  private static final String LOCK_NAMESPACE = "ccd-retain-and-dispose";
  private static final String LOCK_NAME = "task";

  private final RetainAndDisposeProperties properties;
  private final RetainAndDisposePolicy policy;
  private final RetainAndDisposeRepository repository;
  private final CoreCaseDataRetainAndDisposeClient ccdClient;
  private final TransactionOperations transaction;
  private final PostgresAdvisoryLock advisoryLock;

  @Scheduled(
      cron = "${ccd.decentralised-runtime.retain-and-dispose.cron:0 0 2 * * *}",
      zone = "${ccd.decentralised-runtime.retain-and-dispose.zone:UTC}"
  )
  @Override
  public void run() {
    Mode mode = properties.getMode();
    properties.validateCredentials();

    Set<String> caseTypeIds = policy.caseTypes();
    log.info("Starting retain and dispose task mode={} caseTypeIds={}", mode, caseTypeIds);

    boolean acquired = advisoryLock.runIfAcquired(
        LOCK_NAMESPACE,
        LOCK_NAME,
        () -> {
          markEligibleCasesForDisposal(caseTypeIds, mode);
          reconcilePendingDisposalCases(caseTypeIds, mode);
        }
    );
    if (!acquired) {
      log.info("Retain and dispose task is already running mode={} caseTypeIds={}; skipping this invocation",
          mode, caseTypeIds);
    } else {
      log.info("Completed retain and dispose task mode={} caseTypeIds={}", mode, caseTypeIds);
    }
  }

  private void markEligibleCasesForDisposal(Set<String> caseTypeIds, Mode mode) {
    List<RetainAndDisposeCase> candidates = repository.resolveCandidates(
        policy.findCandidatesForDisposal(),
        caseTypeIds
    );
    log.info("Found retain and dispose candidates count={} caseTypeIds={}", candidates.size(), caseTypeIds);
    for (RetainAndDisposeCase candidate : candidates) {
      if (mode == Mode.DRY_RUN) {
        log.info("Dry run would mark case for disposal caseReference={} caseTypeId={}",
            candidate.reference(), candidate.caseTypeId());
      } else {
        attempt(candidate.reference(), "markForDisposal", () -> ccdClient.markForDisposal(candidate));
      }
    }
  }

  private void reconcilePendingDisposalCases(Set<String> caseTypeIds, Mode mode) {
    List<RetainAndDisposeCase> terminalCases = repository.findPendingDisposalCases(caseTypeIds);
    log.info("Reconciling pending disposal cases count={} caseTypeIds={}", terminalCases.size(), caseTypeIds);
    for (RetainAndDisposeCase terminalCase : terminalCases) {
      attempt(
          terminalCase.reference(),
          "reconcilePendingDisposal",
          () -> reconcile(terminalCase, mode)
      );
    }
  }

  private void attempt(
      long caseReference,
      String operation,
      Runnable action
  ) {
    try {
      action.run();
    } catch (RuntimeException exception) {
      log.error("Retain and dispose operation failed operation={} caseReference={}",
          operation, caseReference, exception);
    }
  }

  private void reconcile(RetainAndDisposeCase disposalCase, Mode mode) {
    if (ccdClient.exists(disposalCase)) {
      if (mode == Mode.DRY_RUN) {
        log.info("Dry run would retain local case still present in CCD caseReference={} caseTypeId={}",
            disposalCase.reference(), disposalCase.caseTypeId());
      } else {
        log.info("Retaining local case still present in CCD caseReference={} caseTypeId={}",
            disposalCase.reference(), disposalCase.caseTypeId());
      }
      return;
    }
    if (mode == Mode.DRY_RUN) {
      log.info("Dry run would delete local case after CCD returned not found caseReference={} caseTypeId={}",
          disposalCase.reference(), disposalCase.caseTypeId());
      return;
    }
    transaction.executeWithoutResult(ignored -> {
      policy.dispose(disposalCase.reference());
      repository.deletePendingDisposalCase(disposalCase);
    });
    log.info("Deleted local case after CCD returned not found caseReference={} caseTypeId={}",
        disposalCase.reference(), disposalCase.caseTypeId());
  }
}
