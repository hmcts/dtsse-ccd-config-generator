package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

public class TaskOutboxRepository {
  private final NamedParameterJdbcTemplate jdbc;
  private final Duration processingTimeout;

  public TaskOutboxRepository(NamedParameterJdbcTemplate jdbc, TaskManagementProperties properties) {
    this.jdbc = jdbc;
    this.processingTimeout = properties.getOutbox().getPoller().getProcessingTimeout();
  }

  public void enqueue(String caseId, String payload, String action) {
    enqueue(caseId, payload, action, null);
  }

  public void enqueue(String caseId, String payload, String action, LocalDateTime nextAttemptAt) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("action", action)
        .addValue("caseId", parseCaseId(caseId))
        .addValue("payload", payload)
        .addValue("nextAttemptAt", nextAttemptAt);

    jdbc.update(
        """
            insert into ccd.task_outbox (case_id, payload, action, next_attempt_at)
            values (:caseId, :payload::jsonb, :action::ccd.task_action, :nextAttemptAt)
            """,
        params
    );
  }

  private long parseCaseId(String caseId) {
    try {
      return Long.parseLong(caseId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("caseId must be a numeric CCD case reference", ex);
    }
  }

  public List<TaskOutboxRecord> findPending(int limit, int maxAttempts) {
    LocalDateTime now = LocalDateTime.now();
    return jdbc.query(
        """
            select id, payload::text, action, attempt_count
            from ccd.task_outbox
            where status in (:newStatus, :failedStatus, :processingStatus)
             and (next_attempt_at is null or next_attempt_at <= :now)
             and (:maxAttempts = 0 or attempt_count < :maxAttempts)
            order by id
            limit :limit
            """,
        Map.of(
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "now", now,
            "limit", limit,
            "maxAttempts", maxAttempts
        ),
        (rs, rowNum) -> new TaskOutboxRecord(
            rs.getLong("id"),
            rs.getString("payload"),
            rs.getString("action"),
            rs.getInt("attempt_count")
        )
    );
  }

  public boolean markProcessing(long id) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime nextAttemptAt = now.plus(processingTimeout);
    int updated = jdbc.update(
        """
            update ccd.task_outbox
            set status = :status, updated = :updated, next_attempt_at = :nextAttemptAt
            where id = :id
             and status in (:newStatus, :failedStatus, :processingStatus)
             and (next_attempt_at is null or next_attempt_at <= :now)
            """,
        Map.of(
            "id", id,
            "status", TaskOutboxStatus.PROCESSING.name(),
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "updated", now,
            "nextAttemptAt", nextAttemptAt,
            "now", now
        )
    );
    return updated == 1;
  }

  @Transactional
  public void markProcessed(long id, int statusCode) {
    LocalDateTime now = LocalDateTime.now();
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("status", TaskOutboxStatus.PROCESSED.name())
        .addValue("updated", now)
        .addValue("nextAttemptAt", null);

    jdbc.update(
        """
            update ccd.task_outbox
            set status = :status,
              updated = :updated,
              next_attempt_at = :nextAttemptAt
            where id = :id
            """,
        params
    );

    recordHistory(id, TaskOutboxStatus.PROCESSED, statusCode, null, now);
  }

  @Transactional
  public void markFailed(long id, Integer statusCode, String error, LocalDateTime nextAttemptAt) {
    LocalDateTime now = LocalDateTime.now();
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("status", TaskOutboxStatus.FAILED.name())
        .addValue("updated", now)
        .addValue("nextAttemptAt", nextAttemptAt);

    jdbc.update(
        """
            update ccd.task_outbox
            set status = :status,
              updated = :updated,
              attempt_count = attempt_count + 1,
              next_attempt_at = :nextAttemptAt
            where id = :id
            """,
        params
    );

    recordHistory(id, TaskOutboxStatus.FAILED, statusCode, error, now);
  }

  private void recordHistory(
      long id,
      TaskOutboxStatus status,
      Integer statusCode,
      String error,
      LocalDateTime created
  ) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("taskOutboxId", id)
        .addValue("status", status.name())
        .addValue("statusCode", statusCode)
        .addValue("error", error)
        .addValue("created", created);

    jdbc.update(
        """
            insert into ccd.task_outbox_history (task_outbox_id, status, response_code, error, created)
            values (:taskOutboxId, :status, :statusCode, :error, :created)
            """,
        params
    );
  }
}
