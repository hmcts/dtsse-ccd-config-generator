package uk.gov.hmcts.ccd.sdk.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class DatabaseAuditContext {

  private final NamedParameterJdbcTemplate db;

  @Transactional(propagation = Propagation.MANDATORY)
  long reserveCaseEventId() {
    Long caseEventId = db.getJdbcTemplate().queryForObject(
        "select nextval('ccd.case_event_id_seq')",
        Long.class
    );
    db.getJdbcTemplate().queryForObject(
        "select set_config('ccd.case_event_id', ?, true)",
        String.class,
        String.valueOf(caseEventId)
    );
    return caseEventId;
  }
}
