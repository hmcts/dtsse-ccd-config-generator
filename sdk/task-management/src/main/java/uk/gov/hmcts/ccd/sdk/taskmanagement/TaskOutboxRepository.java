package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
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
    enqueueAndReturnId(caseId, payload, action, nextAttemptAt);
  }

  public long enqueueAndReturnId(String caseId, String payload, String action) {
    return enqueueAndReturnId(caseId, payload, action, null);
  }

  public long enqueueAndReturnId(String caseId, String payload, String action, LocalDateTime nextAttemptAt) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("action", action)
        .addValue("caseId", parseCaseId(caseId))
        .addValue("payload", payload)
        .addValue("nextAttemptAt", nextAttemptAt);

    Long id = jdbc.queryForObject(
        """
            insert into ccd.task_outbox (case_id, payload, requested_action, next_attempt_at)
            values (:caseId, :payload::jsonb, :action::ccd.task_action, :nextAttemptAt)
            returning id
            """,
        params,
        Long.class
    );
    if (id == null) {
      throw new IllegalStateException("Task outbox insert did not return an id");
    }
    return id;
  }

  private long parseCaseId(String caseId) {
    try {
      return Long.parseLong(caseId);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("caseId must be a numeric CCD case reference", ex);
    }
  }

  public List<TaskOutboxRecord> claimPending(int limit, int maxAttempts) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime processingDeadline = now.plus(processingTimeout);
    return jdbc.query(
        """
            with claimable as (
              select o.id
              from ccd.task_outbox o
              where o.status::text in (:newStatus, :failedStatus, :processingStatus)
                and (o.next_attempt_at is null or o.next_attempt_at <= :now)
                and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
                and not exists (
                  select 1
                  from ccd.task_outbox prior
                  where prior.case_id = o.case_id
                    and prior.id < o.id
                    and prior.status::text in (:newStatus, :failedStatus, :processingStatus)
                    and (:maxAttempts = 0 or prior.attempt_count < :maxAttempts)
                )
              order by o.id
              limit :limit
              for update skip locked
            ),
            updated as (
              update ccd.task_outbox outbox
              set status = cast(:processingStatus as ccd.task_outbox_status),
                  updated = :now,
                  next_attempt_at = :processingDeadline
              from claimable
              where outbox.id = claimable.id
              returning outbox.id,
                        outbox.case_id,
                        outbox.payload::text as payload,
                        outbox.requested_action::text as requested_action,
                        outbox.attempt_count
            )
            select id, case_id, payload, requested_action, attempt_count
            from updated
            order by id
            """,
        Map.of(
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "now", now,
            "processingDeadline", processingDeadline,
            "limit", limit,
            "maxAttempts", maxAttempts
        ),
        (rs, rowNum) -> new TaskOutboxRecord(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getString("payload"),
            rs.getString("requested_action"),
            rs.getInt("attempt_count")
        )
    );
  }

  public Long findNextRetryableInCase(long caseId, long afterId, int maxAttempts) {
    return jdbc.query(
        """
            select o.id
            from ccd.task_outbox o
            where o.case_id = :caseId
              and o.id > :afterId
              and o.status::text in (:newStatus, :failedStatus, :processingStatus)
              and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
            order by o.id
            limit 1
            """,
        Map.of(
            "caseId", caseId,
            "afterId", afterId,
            "newStatus", TaskOutboxStatus.NEW.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "maxAttempts", maxAttempts
        ),
        rs -> rs.next() ? rs.getLong("id") : null
    );
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Optional<TaskOutboxStatus> findStatus(long id) {
    return jdbc.query(
        """
            select status::text
            from ccd.task_outbox
            where id = :id
            """,
        Map.of("id", id),
        rs -> rs.next() ? Optional.of(TaskOutboxStatus.valueOf(rs.getString("status"))) : Optional.empty()
    );
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
            set status = cast(:status as ccd.task_outbox_status),
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
            set status = cast(:status as ccd.task_outbox_status),
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
            values (:taskOutboxId, cast(:status as ccd.task_outbox_status), :statusCode, :error, :created)
            """,
        params
    );
  }
}
