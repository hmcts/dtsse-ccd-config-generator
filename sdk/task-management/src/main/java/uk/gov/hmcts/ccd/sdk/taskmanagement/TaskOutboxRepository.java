package uk.gov.hmcts.ccd.sdk.taskmanagement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
      LocalDateTime availableAt
  ) {
    enqueueAndReturnId(caseId, eventId, created, payload, action, availableAt);
  }

  public long enqueueAndReturnId(
      String caseId,
      String eventId,
      LocalDateTime created,
      String payload,
      String action,
      LocalDateTime availableAt
  ) {
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("action", action)
        .addValue("caseId", parseCaseId(caseId))
        .addValue("eventId", eventId)
        .addValue("created", created)
        .addValue("payload", payload)
        .addValue("availableAt", availableAt)
        .addValue("status", TaskOutboxStatus.PENDING.name());

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
              available_at
            )
            values (
              :caseId,
              :eventId,
              coalesce(:created, (current_timestamp at time zone 'UTC')),
              coalesce(:created, (current_timestamp at time zone 'UTC')),
              :payload::jsonb,
              :action::ccd.task_action,
              cast(:status as ccd.task_outbox_status),
              coalesce(:availableAt, (current_timestamp at time zone 'UTC'))
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
            with exhausted as (
              -- A crashed worker has already consumed the final allowed claim. Make that row terminal rather than
              -- leaving it permanently in PROCESSING. maxAttempts = 0 retains the unlimited-attempt convention.
              update ccd.task_outbox outbox
              set status = cast(:unprocessableStatus as ccd.task_outbox_status),
                  updated = (current_timestamp at time zone 'UTC'),
                  available_at = null,
                  claim_token = null,
                  lease_until = null
              where outbox.status::text = :processingStatus
                and outbox.lease_until <= (current_timestamp at time zone 'UTC')
                and :maxAttempts > 0
                and outbox.attempt_count >= :maxAttempts
              returning outbox.id, outbox.updated
            ),
            exhausted_history as (
              insert into ccd.task_outbox_history (task_outbox_id, status, error, created)
              select exhausted.id,
                     cast(:unprocessableStatus as ccd.task_outbox_status),
                     'Processing lease expired after final attempt',
                     exhausted.updated
              from exhausted
              returning task_outbox_id
            ),
            ordered as (
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
              where (
                  (
                    o.status::text = :pendingStatus
                    and o.available_at <= (current_timestamp at time zone 'UTC')
                  )
                  or (
                    o.status::text = :processingStatus
                    and o.lease_until <= (current_timestamp at time zone 'UTC')
                  )
                )
                and (:maxAttempts = 0 or o.attempt_count < :maxAttempts)
                and not exists (
                  -- A case has at most one live processing lease, including when a delayed trigger was bypassed.
                  select 1
                  from ordered active
                  where active.case_id = o.case_id
                    and active.id <> o.id
                    and active.status::text = :processingStatus
                    and active.lease_until > (current_timestamp at time zone 'UTC')
                )
                and not exists (
                  -- Preserve ordering between triggers for a case. Retry PENDING rows, PROCESSING rows, and terminal
                  -- failures always block. A future never-attempted PENDING row retains the delayed-work bypass policy.
                  select 1
                  from ordered prior
                  where prior.case_id = o.case_id
                    and (
                      prior.status::text in (:processingStatus, :unprocessableStatus)
                      or (
                        prior.status::text = :pendingStatus
                        and (
                          prior.attempt_count > 0
                          or prior.available_at <= (current_timestamp at time zone 'UTC')
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
                  updated = (current_timestamp at time zone 'UTC'),
                  available_at = null,
                  attempt_count = outbox.attempt_count + 1,
                  claim_token = gen_random_uuid(),
                  lease_until =
                    (current_timestamp at time zone 'UTC') + (:processingTimeoutMillis * interval '1 millisecond')
              from claimable
              where outbox.id = claimable.id
              returning outbox.id,
                        outbox.case_id,
                        outbox.event_id,
                        outbox.created,
                        outbox.payload::text as payload,
                        outbox.requested_action::text as requested_action,
                        outbox.attempt_count,
                        outbox.claim_token
            )
            select id, case_id, event_id, created, payload, requested_action, attempt_count, claim_token
            from updated
            order by id
            """,
        Map.of(
            "pendingStatus", TaskOutboxStatus.PENDING.name(),
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
            rs.getInt("attempt_count"),
            rs.getObject("claim_token", UUID.class)
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
  public boolean markProcessed(long id, UUID claimToken, int statusCode) {
    LocalDateTime now = utcNow();
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("claimToken", claimToken)
        .addValue("processingStatus", TaskOutboxStatus.PROCESSING.name())
        .addValue("status", TaskOutboxStatus.PROCESSED.name())
        .addValue("updated", now);

    int updated = jdbc.update(
        """
            update ccd.task_outbox
            set status = cast(:status as ccd.task_outbox_status),
              updated = :updated,
              available_at = null,
              claim_token = null,
              lease_until = null
            where id = :id
              and status = cast(:processingStatus as ccd.task_outbox_status)
              and claim_token = :claimToken
            """,
        params
    );

    if (updated == 0) {
      return false;
    }

    recordHistory(id, TaskOutboxStatus.PROCESSED, statusCode, null, now);
    return true;
  }

  @Transactional
  public boolean rescheduleAfterFailure(
      long id,
      UUID claimToken,
      Integer statusCode,
      String error,
      LocalDateTime availableAt
  ) {
    return updateFailure(
        id,
        claimToken,
        TaskOutboxStatus.PENDING,
        statusCode,
        error,
        availableAt
    );
  }

  @Transactional
  public boolean markUnprocessable(long id, UUID claimToken, Integer statusCode, String error) {
    return updateFailure(
        id,
        claimToken,
        TaskOutboxStatus.UNPROCESSABLE,
        statusCode,
        error,
        null
    );
  }

  private boolean updateFailure(
      long id,
      UUID claimToken,
      TaskOutboxStatus status,
      Integer statusCode,
      String error,
      LocalDateTime availableAt
  ) {
    LocalDateTime now = utcNow();
    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", id)
        .addValue("claimToken", claimToken)
        .addValue("processingStatus", TaskOutboxStatus.PROCESSING.name())
        .addValue("status", status.name())
        .addValue("updated", now)
        .addValue("availableAt", availableAt);

    int updated = jdbc.update(
        """
            update ccd.task_outbox
            set status = cast(:status as ccd.task_outbox_status),
              updated = :updated,
              available_at = :availableAt,
              claim_token = null,
              lease_until = null
            where id = :id
              and status = cast(:processingStatus as ccd.task_outbox_status)
              and claim_token = :claimToken
            """,
        params
    );

    if (updated == 0) {
      return false;
    }

    recordHistory(id, status, statusCode, error, now);
    return true;
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
