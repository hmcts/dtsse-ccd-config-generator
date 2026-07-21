package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
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
  private final DataSource dataSource;
  private final TransactionTemplate deletionTransaction;

  @Override
  public void run() {
    Set<String> caseTypeIds = policy.caseTypes();
    if (caseTypeIds.isEmpty()) {
      throw new IllegalStateException("Retain and dispose policy case types must not be empty");
    }
    log.info("Starting retain and dispose task caseTypeIds={}", caseTypeIds);

    boolean acquired = new PostgresAdvisoryLock(dataSource).runIfAcquired(
        LOCK_NAMESPACE,
        LOCK_NAME,
        () -> runWithLock(caseTypeIds)
    );
    if (!acquired) {
      log.warn("Retain and dispose task is already running for caseTypeIds={}; skipping this invocation",
          caseTypeIds);
    }
  }

  private void runWithLock(Set<String> caseTypeIds) {
    List<RetainAndDisposeCase> candidates = selectCandidates(caseTypeIds);
    List<CaseFailure> failures = new ArrayList<>();
    int transitioned = 0;
    for (RetainAndDisposeCase candidate : candidates) {
      try {
        ccdClient.markForDisposal(candidate);
        transitioned++;
      } catch (RuntimeException exception) {
        log.error("Failed to move retain and dispose candidate {} to terminal state", candidate.reference(), exception);
        failures.add(new CaseFailure(candidate.reference(), "terminal event", exception));
      }
    }

    List<RetainAndDisposeCase> terminalCases = repository.findCasesInState(caseTypeIds, DISPOSAL_STATE_ID);
    int deleted = 0;
    for (RetainAndDisposeCase terminalCase : terminalCases) {
      try {
        if (!ccdClient.exists(terminalCase)) {
          deleteLocalCase(terminalCase);
          deleted++;
        }
      } catch (RuntimeException exception) {
        log.error("Failed to dispose local data for case {}", terminalCase.reference(), exception);
        failures.add(new CaseFailure(terminalCase.reference(), "local disposal", exception));
      }
    }

    if (!failures.isEmpty()) {
      throw aggregateFailures(failures);
    }
    log.info(
        "Completed retain and dispose task candidates={} transitioned={} terminalCases={} deleted={}",
        candidates.size(),
        transitioned,
        terminalCases.size(),
        deleted
    );
  }

  private List<RetainAndDisposeCase> selectCandidates(Set<String> caseTypeIds) {
    List<Long> candidateReferences = List.copyOf(Objects.requireNonNull(
        policy.findCandidatesForDisposal(),
        "Retain and dispose policy returned a null candidate list"
    ));
    return repository.findCases(candidateReferences, caseTypeIds);
  }

  private void deleteLocalCase(RetainAndDisposeCase terminalCase) {
    deletionTransaction.executeWithoutResult(status -> {
      policy.dispose(terminalCase.reference());
      repository.deleteCase(
          terminalCase.reference(),
          terminalCase.caseTypeId(),
          DISPOSAL_STATE_ID
      );
    });
  }

  private IllegalStateException aggregateFailures(List<CaseFailure> failures) {
    String summary = failures.stream()
        .map(failure -> failure.caseReference() + " (" + failure.operation() + ")")
        .collect(Collectors.joining(", "));
    IllegalStateException aggregate = new IllegalStateException(
        "Retain and dispose failed for " + failures.size() + " case operations: " + summary,
        failures.getFirst().exception()
    );
    failures.stream().skip(1).map(CaseFailure::exception).forEach(aggregate::addSuppressed);
    return aggregate;
  }

  private record CaseFailure(long caseReference, String operation, RuntimeException exception) {
  }
}
