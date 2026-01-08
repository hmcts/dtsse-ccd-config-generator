package uk.gov.hmcts.divorce.tasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class TaskOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TaskOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String taskId, String caseId, String caseTypeId, String payload) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("taskId", taskId)
            .addValue("caseId", caseId)
            .addValue("caseTypeId", caseTypeId)
            .addValue("payload", payload);

        jdbc.update(
            """
            insert into task_outbox (task_id, case_id, case_type_id, payload)
            values (:taskId, :caseId, :caseTypeId, :payload::jsonb)
            """,
            params
        );
    }

    public List<TaskOutboxRecord> findPending(int limit) {
        return jdbc.query(
            """
            select id, task_id, payload::text
            from task_outbox
            where status = :status
            order by id
            limit :limit
            """,
            Map.of("status", TaskOutboxStatus.NEW.name(), "limit", limit),
            (rs, rowNum) -> new TaskOutboxRecord(
                rs.getLong("id"),
                rs.getString("task_id"),
                rs.getString("payload")
            )
        );
    }

    public boolean markProcessing(long id) {
        int updated = jdbc.update(
            """
            update task_outbox
            set status = :status, updated = :updated
            where id = :id and status = :expected
            """,
            Map.of(
                "id", id,
                "status", TaskOutboxStatus.PROCESSING.name(),
                "expected", TaskOutboxStatus.NEW.name(),
                "updated", LocalDateTime.now()
            )
        );
        return updated == 1;
    }

    public void markProcessed(long id, int statusCode) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", TaskOutboxStatus.PROCESSED.name())
            .addValue("processed", LocalDateTime.now())
            .addValue("updated", LocalDateTime.now())
            .addValue("statusCode", statusCode)
            .addValue("lastError", null);

        jdbc.update(
            """
            update task_outbox
            set status = :status,
                processed = :processed,
                updated = :updated,
                last_response_code = :statusCode,
                last_error = :lastError
            where id = :id
            """,
            params
        );
    }

    public void markFailed(long id, Integer statusCode, String error) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", TaskOutboxStatus.FAILED.name())
            .addValue("updated", LocalDateTime.now())
            .addValue("statusCode", statusCode)
            .addValue("lastError", error);

        jdbc.update(
            """
            update task_outbox
            set status = :status,
                updated = :updated,
                attempt_count = attempt_count + 1,
                last_response_code = :statusCode,
                last_error = :lastError
            where id = :id
            """,
            params
        );
    }
}
