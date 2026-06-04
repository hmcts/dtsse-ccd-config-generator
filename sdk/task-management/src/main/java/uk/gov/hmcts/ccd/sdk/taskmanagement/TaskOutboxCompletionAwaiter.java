package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

@Slf4j
public class TaskOutboxCompletionAwaiter {

  private static final String CHANNEL = "task_outbox_complete_finished";

  private final TaskOutboxRepository repository;
  private final TaskManagementProperties properties;
  private final DataSource dataSource;
  private final ObjectMapper objectMapper;

  public TaskOutboxCompletionAwaiter(
      TaskOutboxRepository repository,
      TaskManagementProperties properties,
      DataSource dataSource,
      ObjectMapper objectMapper
  ) {
    this.repository = repository;
    this.properties = properties;
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
  }

  public long latestOutboxIdForCase(long caseId) {
    return repository.findLatestIdForCase(caseId);
  }

  public void awaitCompletionsCreatedAfter(long caseId, long afterId) {
    if (!properties.getOutbox().getCompletion().isAwaitProcessed()) {
      return;
    }

    List<Long> outboxIds = repository.findCompletionIdsForCaseAfter(caseId, afterId);
    outboxIds.forEach(this::awaitProcessed);
  }

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
      listen(connection);
      PGConnection pgConnection = connection.unwrap(PGConnection.class);

      Optional<TaskOutboxCompletionResult> current = repository.findFinishedCompleteTask(outboxId);
      if (current.isPresent()) {
        return current.get();
      }

      while (Instant.now().isBefore(deadline)) {
        int waitMillis = waitMillis(deadline, pollInterval);
        if (waitMillis <= 0) {
          break;
        }

        PGNotification[] notifications = pgConnection.getNotifications(waitMillis);
        if (notifications == null || notifications.length == 0) {
          continue;
        }

        for (PGNotification notification : notifications) {
          if (!matches(notification, outboxId)) {
            continue;
          }

          Optional<TaskOutboxCompletionResult> result = repository.findFinishedCompleteTask(outboxId);
          if (result.isPresent()) {
            return result.get();
          }
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Unable to listen for task outbox completion", ex);
    }

    throw new TaskOutboxTimeoutException(
        "Timed out waiting for task_outbox id " + outboxId + " to become PROCESSED or FAILED"
    );
  }

  private void listen(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("LISTEN " + CHANNEL);
    }
  }

  private boolean matches(PGNotification notification, long outboxId) {
    if (!CHANNEL.equals(notification.getName())) {
      return false;
    }

    try {
      TaskOutboxNotification payload = objectMapper.readValue(
          notification.getParameter(),
          TaskOutboxNotification.class
      );
      return payload.id() == outboxId;
    } catch (JsonProcessingException ex) {
      log.warn("Ignoring malformed task outbox notification payload", ex);
      return false;
    }
  }

  private int waitMillis(Instant deadline, Duration pollInterval) {
    Duration remaining = Duration.between(Instant.now(), deadline);
    return Math.toIntExact(Math.min(Math.max(remaining.toMillis(), 0), pollInterval.toMillis()));
  }

  private record TaskOutboxNotification(
      long id,
      @JsonProperty("case_id")
      long caseId,
      @JsonProperty("requested_action")
      String requestedAction,
      @JsonProperty("old_status")
      String oldStatus,
      @JsonProperty("new_status")
      String newStatus,
      @JsonProperty("attempt_count")
      int attemptCount,
      String updated
  ) {}
}
