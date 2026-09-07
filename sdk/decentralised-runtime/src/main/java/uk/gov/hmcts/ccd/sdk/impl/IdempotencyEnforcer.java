package uk.gov.hmcts.ccd.sdk.impl;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class IdempotencyEnforcer {

  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final NamedParameterJdbcTemplate db;

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Long> lockCaseAndGetExistingEvent(UUID idempotencyKey, Long caseReference) {
    var params = new MapSqlParameterSource()
        .addValue("reference", caseReference)
        .addValue("key", idempotencyKey);

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

    Optional<Long> existingEventId = db.query(
        """
        select ce.id
        from ccd.case_event ce
        where ce.case_data_id = :caseDataId
          and ce.idempotency_key = :key
        """,
        params.addValue("caseDataId", caseIds.get(0)),
        (rs, rowNum) -> rs.getLong("id")
    ).stream().findFirst();

    if (existingEventId.isPresent()) {
      log.info("Idempotency key '{}' already exists (event id {}). Request previously processed.",
          idempotencyKey, existingEventId.get());
      return existingEventId;
    }

    log.debug("Idempotency key '{}' not found; continuing processing (case reference {}).",
        idempotencyKey, caseReference);
    return Optional.empty();
  }
}
