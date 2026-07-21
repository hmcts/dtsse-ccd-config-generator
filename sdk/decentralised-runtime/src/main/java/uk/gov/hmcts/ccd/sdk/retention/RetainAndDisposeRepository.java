package uk.gov.hmcts.ccd.sdk.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class RetainAndDisposeRepository {

  private static final String SELECT_CASES_BY_REFERENCE = """
      select reference, jurisdiction, case_type_id, state
      from ccd.case_data
      where reference in (:caseReferences)
      order by reference asc
      """;
  private static final String SELECT_CASES_IN_STATE = """
      select reference, jurisdiction, case_type_id, state
      from ccd.case_data
      where case_type_id in (:caseTypeIds)
        and state = :state
      order by reference asc
      """;
  private static final String DELETE_CASE = """
      delete from ccd.case_data
      where reference = :caseReference
        and case_type_id in (:caseTypeIds)
        and state = :state
      """;

  private final NamedParameterJdbcTemplate db;

  RetainAndDisposeRepository(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  Map<Long, RetainAndDisposeCase> findCases(Collection<Long> caseReferences) {
    if (caseReferences.isEmpty()) {
      return Map.of();
    }

    Map<Long, RetainAndDisposeCase> cases = new LinkedHashMap<>();
    db.query(
        SELECT_CASES_BY_REFERENCE,
        Map.of("caseReferences", caseReferences),
        resultSet -> {
          RetainAndDisposeCase disposalCase = mapCase(resultSet);
          cases.put(disposalCase.reference(), disposalCase);
        }
    );
    return cases;
  }

  List<RetainAndDisposeCase> findCasesInState(Set<String> caseTypeIds, String state) {
    return db.query(
        SELECT_CASES_IN_STATE,
        Map.of("caseTypeIds", caseTypeIds, "state", state),
        (resultSet, rowNumber) -> mapCase(resultSet)
    );
  }

  void deleteCase(long caseReference, Set<String> caseTypeIds, String state) {
    int deleted = db.update(
        DELETE_CASE,
        Map.of("caseReference", caseReference, "caseTypeIds", caseTypeIds, "state", state)
    );
    if (deleted != 1) {
      throw new RetainAndDisposeException("Expected to delete local case " + caseReference + " but deleted " + deleted);
    }
  }

  private RetainAndDisposeCase mapCase(ResultSet resultSet) throws SQLException {
    return new RetainAndDisposeCase(
        resultSet.getLong("reference"),
        resultSet.getString("jurisdiction"),
        resultSet.getString("case_type_id"),
        resultSet.getString("state")
    );
  }
}
