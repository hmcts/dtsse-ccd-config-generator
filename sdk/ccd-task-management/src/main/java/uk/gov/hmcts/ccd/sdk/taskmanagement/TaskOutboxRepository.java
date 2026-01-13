package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;

public class TaskOutboxRepository {

  private final NamedParameterJdbcTemplate jdbc;
  private final String tableName;

  public TaskOutboxRepository(NamedParameterJdbcTemplate jdbc, TaskManagementProperties properties) {
    this.jdbc = jdbc;
    String schema = properties.getOutbox().getSchema();
    this.tableName = StringUtils.hasText(schema) ? schema + ".task_outbox" : "task_outbox";
  }

  public void enqueue(String taskId, String caseId, String caseTypeId, String payload) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("taskId", taskId)
        .addValue("caseId", caseId)
        .addValue("caseTypeId", caseTypeId)
        .addValue("payload", payload);

    jdbc.update(
        """
            insert into %s (task_id, case_id, case_type_id, payload)
            values (:taskId, :caseId, :caseTypeId, :payload::jsonb)
            """.formatted(tableName),
        params
    );
  }

  public List<TaskOutboxRecord> findPending(int limit, int maxAttempts) {
    LocalDateTime now = LocalDateTime.now();
    return jdbc.query(
        """
            select id, task_id, payload::text, attempt_count
            from %s
            where status in (:newStatus, :failedStatus)
             and (next_attempt_at is null or next_attempt_at <= :now)
             and (:maxAttempts = 0 or attempt_count < :maxAttempts)
            order by id
            limit :limit
            """.formatted(tableName),
        Map.of(
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "now", now,
            "limit", limit,
            "maxAttempts", maxAttempts
        ),
        (rs, rowNum) -> new TaskOutboxRecord(
            rs.getLong("id"),
            rs.getString("task_id"),
            rs.getString("payload"),
            rs.getInt("attempt_count")
        )
    );
  }

  public boolean markProcessing(long id) {
    LocalDateTime now = LocalDateTime.now();
    int updated = jdbc.update(
        """
            update %s
            set status = :status, updated = :updated
            where id = :id
             and status in (:newStatus, :failedStatus)
             and (next_attempt_at is null or next_attempt_at <= :now)
            """.formatted(tableName),
        Map.of(
            "id", id,
            "status", TaskOutboxStatus.PROCESSING.name(),
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "updated", now,
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
