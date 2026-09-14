package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
class IdempotencyEnforcer {

  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final NamedParameterJdbcTemplate db;

  /**
   * Locks the case and returns any event already created with this idempotency key. A non-null
   * {@code startRevision} additionally rejects the event if anything committed after that revision.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Long> lockCaseAndGetExistingEvent(UUID idempotencyKey, Long caseReference, Long startRevision) {
    var params = new MapSqlParameterSource()
        .addValue("reference", caseReference)
        .addValue("key", idempotencyKey)
        .addValue("startRevision", startRevision);

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
      log.debug("Case reference {} not found while acquiring idempotency lock; proceeding.", caseReference);
      return Optional.empty();
    }

    var result = db.queryForObject(
        """
        select
          replay.id as existing_event_id,
          conflicting.event_id as conflicting_event_id
        from ccd.case_data cd
        left join lateral (
          select ce.id
          from ccd.case_event ce
          where ce.case_data_id = cd.id
            and ce.idempotency_key = :key
          limit 1
        ) replay on true
        left join lateral (
          select ce.event_id
          from ccd.case_event ce
          where replay.id is null
            and ce.case_data_id = cd.id
            and ce.case_revision > :startRevision
          limit 1
        ) conflicting on true
        where cd.id = :caseDataId
        """,
        params.addValue("caseDataId", caseIds.get(0)),
        (rs, rowNum) -> new LockResult(
            rs.getObject("existing_event_id", Long.class),
            rs.getString("conflicting_event_id")
        )
    );

    if (result.existingEventId() != null) {
      log.info("Idempotency key '{}' already exists (event id {}). Request previously processed.",
          idempotencyKey, result.existingEventId());
      return Optional.of(result.existingEventId());
    }

    if (result.conflictingEventId() != null) {
      log.info("Rejecting non-concurrent event for case {}: startRevision={}, conflictingEvent={}",
          caseReference, startRevision, result.conflictingEventId());
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Event %s was committed to case %d after this event started (revision %d)"
              .formatted(result.conflictingEventId(), caseReference, startRevision));
    }

    log.debug("Idempotency key '{}' not found; continuing processing (case reference {}).",
        idempotencyKey, caseReference);
    return Optional.empty();
  }

  private record LockResult(Long existingEventId, String conflictingEventId) {
  }
}
