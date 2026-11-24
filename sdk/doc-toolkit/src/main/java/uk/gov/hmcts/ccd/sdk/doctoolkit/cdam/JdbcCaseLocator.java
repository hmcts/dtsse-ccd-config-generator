package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the case's type and jurisdiction from the decentralised runtime's case table. */
public final class JdbcCaseLocator implements CaseLocator {
  private final JdbcTemplate jdbc;

  public JdbcCaseLocator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public CaseType locate(long caseId) {
    List<CaseType> found = jdbc.query(
        "select case_type_id, jurisdiction from ccd.case_data where reference = ?",
        (rs, row) -> new CaseType(rs.getString("case_type_id"), rs.getString("jurisdiction")),
        caseId);
    if (found.isEmpty()) {
      throw new IllegalArgumentException("Case " + caseId + " does not exist");
    }
    return found.getFirst();
  }
}
