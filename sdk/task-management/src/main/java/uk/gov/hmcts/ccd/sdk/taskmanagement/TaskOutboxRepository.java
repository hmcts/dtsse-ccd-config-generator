package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskAction;
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
        .addValue("nextAttemptAt", nextAttemptAt)
        .addValue("status", nextAttemptAt == null ? TaskOutboxStatus.NEW.name() : TaskOutboxStatus.WAITING.name());

    Long id = jdbc.queryForObject(
        """
            insert into ccd.task_outbox (case_id, payload, requested_action, status, next_attempt_at)
            values (:caseId, :payload::jsonb, :action::ccd.task_action,
                    cast(:status as ccd.task_outbox_status), :nextAttemptAt)
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
    return jdbc.query(
        """
            with claimable as (
              select o.id
              from ccd.task_outbox o
              where o.status::text in (:newStatus, :waitingStatus, :failedStatus, :processingStatus)
                and (o.next_attempt_at is null or o.next_attempt_at <= (current_timestamp at time zone 'UTC'))
                and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
                and not exists (
                  select 1
                  from ccd.task_outbox prior
                  where prior.case_id = o.case_id
                    and prior.id < o.id
                    and (
                      prior.status::text in (:newStatus, :processingStatus)
                      or (
                        prior.status::text = :waitingStatus
                        and (
                          prior.next_attempt_at is null
                          or prior.next_attempt_at <= (current_timestamp at time zone 'UTC')
                        )
                      )
                    )
                    and (:maxAttempts = 0 or prior.attempt_count < :maxAttempts)
                )
              order by o.id
              limit :limit
              for update skip locked
            ),
            updated as (
              update ccd.task_outbox outbox
              set status = cast(:processingStatus as ccd.task_outbox_status),
                  updated = localtimestamp,
                  next_attempt_at =
                    (current_timestamp at time zone 'UTC') + (:processingTimeoutMillis * interval '1 millisecond')
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
            "waitingStatus", TaskOutboxStatus.WAITING.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "processingTimeoutMillis", processingTimeout.toMillis(),
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
              and o.status::text in (:newStatus, :waitingStatus, :failedStatus, :processingStatus)
              and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
            order by o.id
            limit 1
            """,
        Map.of(
            "caseId", caseId,
            "afterId", afterId,
            "newStatus", TaskOutboxStatus.NEW.name(),
            "waitingStatus", TaskOutboxStatus.WAITING.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "maxAttempts", maxAttempts
        ),
        rs -> rs.next() ? rs.getLong("id") : null
    );
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public List<TaskOutboxFailureLogEntry> findFailedHistoryAfter(long historyId, int limit) {
    return jdbc.query(
        """
            select h.id as history_id,
                   h.task_outbox_id,
                   o.case_id,
                   o.requested_action::text as requested_action,
                   o.payload::text as payload,
                   h.response_code,
                   h.error,
                   h.created
            from ccd.task_outbox_history h
            join ccd.task_outbox o on o.id = h.task_outbox_id
            where h.id > :historyId
              and h.status::text = :failedStatus
            order by h.id
            limit :limit
            """,
        Map.of(
            "historyId", historyId,
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "limit", limit
        ),
        (rs, rowNum) -> new TaskOutboxFailureLogEntry(
            rs.getLong("history_id"),
            rs.getLong("task_outbox_id"),
            rs.getLong("case_id"),
            rs.getString("requested_action"),
            rs.getString("payload"),
            rs.getObject("response_code", Integer.class),
            rs.getString("error"),
            rs.getTimestamp("created").toLocalDateTime()
        )
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

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public Optional<TaskOutboxCompletionResult> findFinishedCompleteTask(long id) {
    return jdbc.query(
        """
            select id,
                   case_id,
                   requested_action::text as requested_action,
                   status::text as status,
                   attempt_count
            from ccd.task_outbox
            where id = :id
              and requested_action = 'complete'::ccd.task_action
              and status in (
                'PROCESSED'::ccd.task_outbox_status,
                'FAILED'::ccd.task_outbox_status
              )
            """,
        Map.of("id", id),
        rs -> {
          if (!rs.next()) {
            return Optional.empty();
          }

          return Optional.of(new TaskOutboxCompletionResult(
              rs.getLong("id"),
              rs.getLong("case_id"),
              rs.getString("requested_action"),
              TaskOutboxStatus.valueOf(rs.getString("status")),
              rs.getInt("attempt_count")
          ));
        }
    );
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public long findLatestIdForCase(long caseId) {
    return jdbc.query(
        """
            select coalesce(max(id), 0)
            from ccd.task_outbox
            where case_id = :caseId
            """,
        Map.of("caseId", caseId),
        rs -> rs.next() ? rs.getLong(1) : 0L
    );
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public List<Long> findCompletionIdsForCaseAfter(long caseId, long afterId) {
    return jdbc.queryForList(
        """
            select id
            from ccd.task_outbox
            where case_id = :caseId
              and id > :afterId
              and requested_action::text = :completeAction
            order by id
            """,
        Map.of(
            "caseId", caseId,
            "afterId", afterId,
            "completeAction", TaskAction.COMPLETE.getId()
        ),
        Long.class
    );
  }

  @Transactional
  public void markProcessed(long id, int statusCode) {
    LocalDateTime now = utcNow();
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
    LocalDateTime now = utcNow();
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

  private LocalDateTime utcNow() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
