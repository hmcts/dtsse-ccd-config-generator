package uk.gov.hmcts.ccd.sdk.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy;

@Slf4j
@RequiredArgsConstructor
public final class RetainAndDisposeTask implements Runnable {

  private static final String LOCK_NAMESPACE = "ccd-retain-and-dispose";
  private static final String LOCK_NAME = "task";
  private static final String TERMINAL_EVENT = "MarkForDisposal";
  private static final String TERMINAL_STATE = "PendingDisposal";

  private final RetainAndDisposePolicy policy;
  private final RetainAndDisposeRepository repository;
  private final CoreCaseDataRetainAndDisposeClient ccdClient;
  private final DataSource dataSource;
  private final TransactionTemplate deletionTransaction;

  @Override
  public void run() {
    Set<String> caseTypeIds = validateCaseTypes();
    log.info("Starting retain and dispose task caseTypeIds={}", caseTypeIds);

    AdvisoryLock taskLock = tryLock();
    if (taskLock == null) {
      log.warn("Retain and dispose task is already running for caseTypeIds={}; skipping this invocation",
          caseTypeIds);
      return;
    }

    try (taskLock) {
      runWithLock(caseTypeIds);
    }
  }

  private void runWithLock(Set<String> caseTypeIds) {
    List<RetainAndDisposeCase> candidates = selectCandidates(caseTypeIds);
    List<CaseFailure> failures = new ArrayList<>();
    int transitioned = 0;
    for (RetainAndDisposeCase candidate : candidates) {
      try {
        ccdClient.moveToTerminalState(candidate, TERMINAL_EVENT, TERMINAL_STATE);
        transitioned++;
      } catch (RuntimeException exception) {
        log.error("Failed to move retain and dispose candidate {} to terminal state", candidate.reference(), exception);
        failures.add(new CaseFailure(candidate.reference(), "terminal event", exception));
      }
    }

    List<RetainAndDisposeCase> terminalCases = repository.findCasesInState(caseTypeIds, TERMINAL_STATE);
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
          TERMINAL_STATE
      );
    });
  }

  private Set<String> validateCaseTypes() {
    Set<String> caseTypeIds = policy.caseTypes();
    if (caseTypeIds == null || caseTypeIds.isEmpty()) {
      throw new IllegalStateException("Retain and dispose policy case types must not be empty");
    }
    TreeSet<String> canonicalCaseTypeIds = new TreeSet<>();
    for (String caseTypeId : caseTypeIds) {
      canonicalCaseTypeIds.add(requireText(caseTypeId, "case type"));
    }
    return Set.copyOf(canonicalCaseTypeIds);
  }

  private String requireText(String value, String description) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Retain and dispose policy " + description + " must not be blank");
    }
    return value;
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

  private AdvisoryLock tryLock() {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_try_advisory_lock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, LOCK_NAMESPACE);
        statement.setString(2, LOCK_NAME);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            return new AdvisoryLock(connection);
          }
        }
      }
      connection.close();
      return null;
    } catch (SQLException exception) {
      closeLockConnection(connection);
      throw new IllegalStateException("Could not acquire retain and dispose advisory lock", exception);
    }
  }

  private void closeLockConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException closeException) {
      log.warn("Failed to close retain and dispose advisory lock connection", closeException);
    }
  }

  private void abortLockConnection(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException abortException) {
      log.warn("Failed to abort retain and dispose advisory lock connection", abortException);
      closeLockConnection(connection);
    }
  }

  @RequiredArgsConstructor
  private final class AdvisoryLock implements AutoCloseable {
    private final Connection connection;

    @Override
    public void close() {
      boolean unlocked = false;
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_advisory_unlock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, LOCK_NAMESPACE);
        statement.setString(2, LOCK_NAME);
        try (ResultSet result = statement.executeQuery()) {
          unlocked = result.next() && result.getBoolean(1);
        }
      } catch (SQLException exception) {
        log.warn("Failed to release retain and dispose advisory lock", exception);
      } finally {
        if (unlocked) {
          closeLockConnection(connection);
        } else {
          abortLockConnection(connection);
        }
      }
    }
  }

  private record CaseFailure(long caseReference, String operation, RuntimeException exception) {
  }
}
