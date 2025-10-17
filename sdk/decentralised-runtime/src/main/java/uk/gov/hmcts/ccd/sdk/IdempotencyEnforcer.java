package uk.gov.hmcts.ccd.sdk;

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
  public boolean lockCaseAndCheckProcessed(UUID idempotencyKey, Long caseReference) {
    if (caseReference == null) {
      log.debug("No case reference supplied for idempotency key '{}'; assuming first-time submission.",
          idempotencyKey);
      return false;
    }

    var params = new MapSqlParameterSource()
        .addValue("reference", caseReference)
        .addValue("key", idempotencyKey);

    /**
     * We establish a lock on the case and look up the idempotency key to see if we've processed this event before.
     */
    var matches = db.query(
        """
        select ce.idempotency_key
        from ccd.case_data cd
        left join ccd.case_event ce
          on ce.case_data_id = cd.id
         and ce.idempotency_key = :key
        where cd.reference = :reference
        for update of cd
        """,
        params,
        (rs, rowNum) -> (UUID) rs.getObject("idempotency_key")
    );

    if (matches.isEmpty()) {
      log.debug("Case reference {} not found while acquiring idempotency lock; proceeding.", caseReference);
      return false;
    }

    boolean processed = matches.get(0) != null;
    if (processed) {
      log.info("Idempotency key '{}' already exists. Request previously processed.", idempotencyKey);
    } else {
      log.debug("Idempotency key '{}' not found; continuing processing (case reference {}).",
          idempotencyKey, caseReference);
    }
    return processed;
  }
}
