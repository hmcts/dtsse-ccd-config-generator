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
          replay.id as replay_event_id,
          conflicting.event_id as conflicting_event_id
        from ccd.case_data cd
        left join lateral (
          select ce.id
          from ccd.case_event ce
          where ce.case_data_id = cd.id
            and ce.idempotency_key = :idempotencyKey
          limit 1
        ) replay on true
        left join lateral (
          select ce.event_id
          from ccd.case_event ce
          where replay.id is null
            and ce.case_data_id = cd.id
            and ce.case_revision > :startRevision
            and ce.event_id = any(:conflictingEventIds)
          limit 1
        ) conflicting on true
        where cd.id = :caseDataId
        """,
        params,
        (rs, rowNum) -> new GuardResult(
            rs.getObject("replay_event_id", Long.class),
            rs.getString("conflicting_event_id")
        )
    );

    if (result.replayEventId() != null) {
      logReplay(idempotencyKey, result.replayEventId());
      return Optional.of(result.replayEventId());
    }

    if (!request.conflictingEventIds().isEmpty() && request.startRevision() == null) {
      log.warn("Rejecting event for case {} due to missing start revision", caseReference);
      throw conflict();
    }

    if (result.conflictingEventId() != null) {
      log.info(
          "Rejecting event for case {}: startRevision={}, conflictingEvent={}",
          caseReference, request.startRevision(), result.conflictingEventId()
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

    static Request concurrent() {
      return new Request(null, Set.of());
    }

    static Request noneCommittedSince(Set<String> eventIds, Long revision) {
      return new Request(revision, eventIds);
    }
  }

  private record GuardResult(
      Long replayEventId,
      String conflictingEventId
  ) {
  }
}
