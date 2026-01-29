package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxRecord;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.outbox.TaskOutboxStatus;

public class TaskOutboxRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final String tableName;
  private final String actionTypeName;
  private final Duration processingTimeout;

  public TaskOutboxRepository(NamedParameterJdbcTemplate jdbc, TaskManagementProperties properties) {
    this.jdbc = jdbc;
    String schema = properties.getOutbox().getSchema();
    this.tableName = StringUtils.hasText(schema) ? schema + ".task_outbox" : "task_outbox";
    this.actionTypeName = StringUtils.hasText(schema) ? schema + ".task_action" : "task_action";
    this.processingTimeout = properties.getOutbox().getPoller().getProcessingTimeout();
  }

  public void enqueue(String caseId, String caseTypeId, String payload, String action) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("action", action)
        .addValue("caseId", caseId)
        .addValue("caseTypeId", caseTypeId)
        .addValue("payload", payload);

    jdbc.update(
      """
          insert into %s (case_id, case_type_id, payload, action)
          values (:caseId, :caseTypeId, :payload::jsonb, :action::%s)
          """.formatted(tableName, actionTypeName),
      params
    );
  }

  public List<TaskOutboxRecord> findPending(int limit, int maxAttempts) {
    LocalDateTime now = LocalDateTime.now();
    return jdbc.query(
      """
          select id, payload::text, action, attempt_count
          from %s
          where status in (:newStatus, :failedStatus, :processingStatus)
           and (next_attempt_at is null or next_attempt_at <= :now)
           and (:maxAttempts = 0 or attempt_count < :maxAttempts)
          order by id
          limit :limit
          """.formatted(tableName),
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
          update %s
          set status = :status, updated = :updated, next_attempt_at = :nextAttemptAt
          where id = :id
           and status in (:newStatus, :failedStatus, :processingStatus)
           and (next_attempt_at is null or next_attempt_at <= :now)
          """.formatted(tableName),
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

  public void markProcessed(long id, int statusCode) {
    LocalDateTime now = LocalDateTime.now();
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("id", id)
      .addValue("status", TaskOutboxStatus.PROCESSED.name())
      .addValue("processed", now)
      .addValue("updated", now)
      .addValue("statusCode", statusCode)
      .addValue("lastError", null)
      .addValue("nextAttemptAt", null);

    jdbc.update(
      """
          update %s
          set status = :status,
            processed = :processed,
            updated = :updated,
            last_response_code = :statusCode,
            last_error = :lastError,
            next_attempt_at = :nextAttemptAt
          where id = :id
          """.formatted(tableName),
      params
    );
  }

  public void markFailed(long id, Integer statusCode, String error, LocalDateTime nextAttemptAt) {
    LocalDateTime now = LocalDateTime.now();
    MapSqlParameterSource params = new MapSqlParameterSource()
      .addValue("id", id)
      .addValue("status", TaskOutboxStatus.FAILED.name())
      .addValue("updated", now)
      .addValue("statusCode", statusCode)
      .addValue("lastError", error)
      .addValue("nextAttemptAt", nextAttemptAt);

    jdbc.update(
      """
          update %s
          set status = :status,
            updated = :updated,
            attempt_count = attempt_count + 1,
            last_response_code = :statusCode,
            last_error = :lastError,
            next_attempt_at = :nextAttemptAt
          where id = :id
          """.formatted(tableName),
      params
    );
  }
}
