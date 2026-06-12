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

  public void enqueue(
      String caseId,
      String eventId,
      LocalDateTime created,
      String payload,
      String action
  ) {
    enqueue(caseId, eventId, created, payload, action, null);
  }

  public void enqueue(
      String caseId,
      String eventId,
      LocalDateTime created,
      String payload,
      String action,
      LocalDateTime nextAttemptAt
  ) {
    enqueueAndReturnId(caseId, eventId, created, payload, action, nextAttemptAt);
  }

  public long enqueueAndReturnId(
      String caseId,
      String eventId,
      LocalDateTime created,
      String payload,
      String action,
      LocalDateTime nextAttemptAt
  ) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("action", action)
        .addValue("caseId", parseCaseId(caseId))
        .addValue("eventId", eventId)
        .addValue("created", created)
        .addValue("payload", payload)
        .addValue("nextAttemptAt", nextAttemptAt)
        .addValue("status", nextAttemptAt == null ? TaskOutboxStatus.NEW.name() : TaskOutboxStatus.WAITING.name());

    Long id = jdbc.queryForObject(
        """
            insert into ccd.task_outbox (
              case_id,
              event_id,
              created,
              updated,
              payload,
              requested_action,
              status,
              next_attempt_at
            )
            values (
              :caseId,
              :eventId,
              coalesce(:created, (current_timestamp at time zone 'UTC')),
              coalesce(:created, (current_timestamp at time zone 'UTC')),
              :payload::jsonb,
              :action::ccd.task_action,
              cast(:status as ccd.task_outbox_status),
              :nextAttemptAt
            )
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
            with ordered as (
              -- Give every row an action precedence and a stable position for its trigger.
              -- min(id) is shared by all rows in a trigger and breaks ties when triggers have the same created value.
              select o.*,
                     case o.requested_action
                       when 'complete'::ccd.task_action then 0
                       when 'cancel'::ccd.task_action then 10
                       when 'reconfigure'::ccd.task_action then 20
                       when 'initiate'::ccd.task_action then 30
                     end as action_priority,
                     min(o.id) over (
                       partition by o.case_id, o.event_id, o.created
                     ) as trigger_first_id
              from ccd.task_outbox o
            ),
            eligible as (
              -- Start with rows that are due and still have an attempt available.
              select o.id,
                     o.created,
                     o.trigger_first_id,
                     o.action_priority
              from ordered o
              where o.status::text in (:newStatus, :waitingStatus, :failedStatus, :processingStatus)
                and (o.next_attempt_at is null or o.next_attempt_at <= (current_timestamp at time zone 'UTC'))
                and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
                and not exists (
                  -- Preserve ordering between triggers for a case. A failed or unprocessable row always blocks later
                  -- triggers. A future WAITING row retains the existing bypass policy until it becomes due.
                  select 1
                  from ordered prior
                  where prior.case_id = o.case_id
                    and (
                      prior.status::text in (
                        :newStatus,
                        :processingStatus,
                        :failedStatus,
                        :unprocessableStatus
                      )
                      or (
                        prior.status::text = :waitingStatus
                        and (
                          prior.next_attempt_at is null
                          or prior.next_attempt_at <= (current_timestamp at time zone 'UTC')
                        )
                      )
                    )
                    and (
                      prior.created < o.created
                      or (
                        prior.created = o.created
                        and prior.trigger_first_id < o.trigger_first_id
                      )
                    )
                )
                and not exists (
                  -- Within one trigger, every lower-priority action must succeed before this action can run.
                  -- Unlike cross-trigger ordering, a failed or delayed predecessor deliberately blocks its successors.
                  select 1
                  from ordered predecessor
                  where predecessor.case_id = o.case_id
                    and predecessor.event_id = o.event_id
                    and predecessor.created = o.created
                    and predecessor.action_priority < o.action_priority
                    and predecessor.status::text <> :processedStatus
                )
            ),
            claimable as (
              -- Lock only rows that passed both ordering checks. SKIP LOCKED allows independent cases to progress
              -- concurrently while preventing two pollers from claiming the same physical outbox row.
              select outbox.id
              from ccd.task_outbox outbox
              join eligible on eligible.id = outbox.id
              order by eligible.created,
                       eligible.trigger_first_id,
                       eligible.action_priority,
                       outbox.id
              limit :limit
              for update of outbox skip locked
            ),
            updated as (
              -- Claiming and setting the processing lease happen atomically in the same statement.
              update ccd.task_outbox outbox
              set status = cast(:processingStatus as ccd.task_outbox_status),
                  updated = localtimestamp,
                  next_attempt_at =
                    (current_timestamp at time zone 'UTC') + (:processingTimeoutMillis * interval '1 millisecond')
              from claimable
              where outbox.id = claimable.id
              returning outbox.id,
                        outbox.case_id,
                        outbox.event_id,
                        outbox.created,
                        outbox.payload::text as payload,
                        outbox.requested_action::text as requested_action,
                        outbox.attempt_count
            )
            select id, case_id, event_id, created, payload, requested_action, attempt_count
            from updated
            order by id
            """,
        Map.of(
            "newStatus", TaskOutboxStatus.NEW.name(),
            "waitingStatus", TaskOutboxStatus.WAITING.name(),
            "failedStatus", TaskOutboxStatus.FAILED.name(),
            "unprocessableStatus", TaskOutboxStatus.UNPROCESSABLE.name(),
            "processingStatus", TaskOutboxStatus.PROCESSING.name(),
            "processedStatus", TaskOutboxStatus.PROCESSED.name(),
            "processingTimeoutMillis", processingTimeout.toMillis(),
            "limit", limit,
            "maxAttempts", maxAttempts
        ),
        (rs, rowNum) -> new TaskOutboxRecord(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getString("event_id"),
            rs.getObject("created", LocalDateTime.class),
            rs.getString("payload"),
            rs.getString("requested_action"),
            rs.getInt("attempt_count")
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
                'UNPROCESSABLE'::ccd.task_outbox_status
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
    updateFailure(id, TaskOutboxStatus.FAILED, statusCode, error, nextAttemptAt);
  }

  @Transactional
  public void markUnprocessable(long id, Integer statusCode, String error) {
    updateFailure(id, TaskOutboxStatus.UNPROCESSABLE, statusCode, error, null);
  }

  private void updateFailure(
      long id,
      TaskOutboxStatus status,
      Integer statusCode,
      String error,
      LocalDateTime nextAttemptAt
  ) {
    LocalDateTime now = utcNow();
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("status", status.name())
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

    recordHistory(id, status, statusCode, error, now);
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
