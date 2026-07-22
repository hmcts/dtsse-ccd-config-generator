package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

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
    if (mode == Mode.OFF) {
      log.info("Retain and dispose task is disabled");
      return;
    }

    Set<String> caseTypeIds = policy.caseTypes();
    log.info("Starting retain and dispose task mode={} caseTypeIds={}", mode, caseTypeIds);

    boolean acquired = advisoryLock.runIfAcquired(
        LOCK_NAMESPACE,
        LOCK_NAME,
        () -> {
          markEligibleCasesForDisposal(caseTypeIds, mode);
          confirmPendingDisposalCases(caseTypeIds, mode);
          deleteExpiredLocalCasesMissingFromCcd(caseTypeIds, mode);
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
    checkCandidatePercentage(candidates);
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

  private void checkCandidatePercentage(List<RetainAndDisposeCase> candidates) {
    if (properties.getMaximumCandidatePercentage() == 100) {
      return;
    }
    List<Long> candidateReferences = candidates.stream()
        .map(RetainAndDisposeCase::reference)
        .toList();
    for (RetainAndDisposeRepository.CandidatePopulation population
        : repository.findCandidatePopulations(candidateReferences)) {
      if (population.candidateCount() < properties.getMinimumCandidateCount()) {
        continue;
      }
      if (population.candidateCount() * 100
          > population.totalCount() * properties.getMaximumCandidatePercentage()) {
        log.error(
            "Retain and dispose candidate circuit breaker tripped caseTypeId={} state={} candidateCount={} "
                + "totalCount={} maximumCandidatePercentage={}. Aborting before marking any cases",
            population.caseTypeId(), population.state(), population.candidateCount(), population.totalCount(),
            properties.getMaximumCandidatePercentage()
        );
        throw new IllegalStateException(
            "Retain and dispose candidates exceed the configured maximum percentage for case type "
                + population.caseTypeId() + " in state " + population.state()
        );
      }
    }
  }

  private void confirmPendingDisposalCases(Set<String> caseTypeIds, Mode mode) {
    List<RetainAndDisposeCase> unconfirmedCases = repository.findUnconfirmedPendingDisposalCases(caseTypeIds);
    log.info("Confirming pending disposal cases count={} caseTypeIds={}", unconfirmedCases.size(), caseTypeIds);
    for (RetainAndDisposeCase unconfirmedCase : unconfirmedCases) {
      requireReadable(unconfirmedCase);
      attempt(
          unconfirmedCase.reference(),
          "confirmDisposal",
          () -> confirm(unconfirmedCase, mode)
      );
    }
  }

  private void requireReadable(RetainAndDisposeCase disposalCase) {
    if (!ccdClient.isReadable(disposalCase)) {
      log.error(
          "Retain and dispose visibility check failed for caseReference={}. Check that the configured system user "
              + "has R permission on caseTypeId={} state={}. Aborting before local deletion",
          disposalCase.reference(), disposalCase.caseTypeId(), DISPOSAL_STATE_ID
      );
      throw new IllegalStateException(
          "Retain and dispose system user cannot read case type " + disposalCase.caseTypeId()
              + " in state " + DISPOSAL_STATE_ID
      );
    }
  }

  private void confirm(RetainAndDisposeCase disposalCase, Mode mode) {
    if (mode == Mode.DRY_RUN) {
      log.info("Dry run would confirm case for disposal caseReference={} caseTypeId={}",
          disposalCase.reference(), disposalCase.caseTypeId());
    } else {
      ccdClient.confirmDisposal(disposalCase);
    }
  }

  private void deleteExpiredLocalCasesMissingFromCcd(Set<String> caseTypeIds, Mode mode) {
    List<RetainAndDisposeCase> terminalCases = repository.findExpiredPendingDisposalCases(caseTypeIds);
    log.info("Checking expired pending disposal cases for local deletion count={} caseTypeIds={}",
        terminalCases.size(), caseTypeIds);
    for (RetainAndDisposeCase terminalCase : terminalCases) {
      attempt(
          terminalCase.reference(),
          "deleteLocalCaseIfMissingFromCcd",
          () -> deleteLocalCaseIfMissingFromCcd(terminalCase, mode)
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

  private void deleteLocalCaseIfMissingFromCcd(RetainAndDisposeCase disposalCase, Mode mode) {
    if (ccdClient.isReadable(disposalCase)) {
      log.info("Retaining local case still present in CCD caseReference={} caseTypeId={}",
          disposalCase.reference(), disposalCase.caseTypeId());
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
