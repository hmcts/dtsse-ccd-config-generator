package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;

@Slf4j
@RequiredArgsConstructor
public class TaskOutboxCompletionAwaiter {

  private static final String CHANNEL_PREFIX = "task_outbox_complete_finished_";

  private final TaskOutboxRepository repository;
  private final TaskManagementProperties properties;
  private final DataSource dataSource;

  public void awaitProcessed(long outboxId) {
    TaskOutboxCompletionResult result = awaitCompleteFinished(outboxId);
    if (result.processed()) {
      log.info("Task outbox {} completed before response", outboxId);
    } else if (result.failed()) {
      log.warn("Task outbox {} failed before response", outboxId);
    }
  }

  public TaskOutboxCompletionResult awaitCompleteFinished(long outboxId) {
    Duration timeout = properties.getOutbox().getCompletion().getTimeout();
    Duration pollInterval = properties.getOutbox().getCompletion().getPollInterval();
    Instant deadline = Instant.now().plus(timeout);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      listen(connection, outboxId);
      PGConnection pgConnection = connection.unwrap(PGConnection.class);

      while (Instant.now().isBefore(deadline)) {
        Optional<TaskOutboxCompletionResult> current = repository.findFinishedCompleteTask(outboxId);
        if (current.isPresent()) {
          return current.get();
        }

        int waitMillis = waitMillis(deadline, pollInterval);
        if (waitMillis <= 0) {
          break;
        }

        pgConnection.getNotifications(waitMillis);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Unable to listen for task outbox completion", ex);
    }

    throw new TaskOutboxTimeoutException(
        "Timed out waiting for task_outbox id " + outboxId + " to become PROCESSED or FAILED"
    );
  }

  private void listen(Connection connection, long outboxId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("LISTEN " + channel(outboxId));
    }
  }

  private String channel(long outboxId) {
    return CHANNEL_PREFIX + outboxId;
  }

  private int waitMillis(Instant deadline, Duration pollInterval) {
    Duration remaining = Duration.between(Instant.now(), deadline);
    return Math.toIntExact(Math.min(Math.max(remaining.toMillis(), 0), pollInterval.toMillis()));
  }
}
