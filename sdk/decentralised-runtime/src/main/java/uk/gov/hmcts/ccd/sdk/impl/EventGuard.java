package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
class EventGuard {

  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private static final String CONFLICT_MESSAGE = "Case was updated by a conflicting event";

  private final NamedParameterJdbcTemplate db;

  @Transactional(propagation = Propagation.MANDATORY)
  Optional<Long> lockAndCheck(
      UUID idempotencyKey,
      long caseReference,
      Request request
  ) {
    var params = new MapSqlParameterSource()
        .addValue("reference", caseReference)
        .addValue("idempotencyKey", idempotencyKey);

    var caseIds = db.query(
        """
        select cd.id
        from ccd.case_data cd
        where cd.reference = :reference
        for update
        """,
        params,
        (rs, rowNum) -> rs.getLong("id")
    );

    if (caseIds.isEmpty()) {
      if (!request.conflictingEventIds().isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
      }
      log.debug("Case reference {} not found while acquiring event lock; proceeding.", caseReference);
      return Optional.empty();
    }

    params.addValue("caseDataId", caseIds.getFirst())
        .addValue("startRevision", request.startRevision())
        .addValue(
            "conflictingEventIds",
            new SqlArrayValue(
                "varchar",
                request.conflictingEventIds().toArray()
            )
        );

    GuardResult result = db.queryForObject(
        """
        select
          cd.case_revision as current_revision,
          replay.id as replay_event_id,
          conflicting.event_id as conflicting_event_id,
          conflicting.case_revision as conflicting_revision
        from ccd.case_data cd
        left join lateral (
          select ce.id
          from ccd.case_event ce
          where ce.case_data_id = cd.id
            and ce.idempotency_key = :idempotencyKey
          limit 1
        ) replay on true
        left join lateral (
          select ce.event_id, ce.case_revision
          from ccd.case_event ce
          where replay.id is null
            and ce.case_data_id = cd.id
            and ce.case_revision > :startRevision
            and ce.event_id = any(:conflictingEventIds)
          order by ce.case_revision
          limit 1
        ) conflicting on true
        where cd.id = :caseDataId
        """,
        params,
        (rs, rowNum) -> new GuardResult(
            rs.getLong("current_revision"),
            rs.getObject("replay_event_id", Long.class),
            rs.getString("conflicting_event_id"),
            rs.getObject("conflicting_revision", Long.class)
        )
    );

    if (result.replayEventId() != null) {
      logReplay(idempotencyKey, result.replayEventId());
      return Optional.of(result.replayEventId());
    }

    Long startRevision = request.startRevision();
    if (!request.conflictingEventIds().isEmpty()
        && (startRevision == null
            || startRevision < 1
            || startRevision > result.currentRevision())) {
      log.warn(
          "Rejecting event for case {} due to invalid concurrency revisions: startRevision={}, "
              + "currentRevision={}, conflictingEventIds={}",
          caseReference, startRevision, result.currentRevision(), request.conflictingEventIds()
      );
      throw conflict();
    }

    if (result.conflictingEventId() != null) {
      log.info(
          "Rejecting event for case {}: startRevision={}, currentRevision={}, conflictingEvent={}, "
              + "conflictingRevision={}",
          caseReference, request.startRevision, result.currentRevision(), result.conflictingEventId(),
          result.conflictingRevision()
      );
      throw conflict();
    }

    log.debug("Event guard passed for case reference {} and idempotency key '{}'.",
        caseReference, idempotencyKey);
    return Optional.empty();
  }

  private void logReplay(UUID idempotencyKey, long eventId) {
    log.info("Idempotency key '{}' already exists (event id {}). Request previously processed.",
        idempotencyKey, eventId);
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, CONFLICT_MESSAGE);
  }

  record Request(Long startRevision, Set<String> conflictingEventIds) {
  }

  private record GuardResult(
      long currentRevision,
      Long replayEventId,
      String conflictingEventId,
      Long conflictingRevision
  ) {
  }
}
