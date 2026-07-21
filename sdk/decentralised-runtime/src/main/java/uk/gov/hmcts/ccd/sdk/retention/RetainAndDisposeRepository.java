package uk.gov.hmcts.ccd.sdk.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@RequiredArgsConstructor
class RetainAndDisposeRepository {

  private static final String SELECT_CASES_BY_REFERENCE = """
      select reference, jurisdiction, case_type_id
      from ccd.case_data
      where reference in (:caseReferences)
      order by reference asc
      """;
  private static final String SELECT_CASES_IN_STATE = """
      select reference, jurisdiction, case_type_id
      from ccd.case_data
      where case_type_id in (:caseTypeIds)
        and state = :state
      order by reference asc
      """;
  private static final String DELETE_CASE = """
      delete from ccd.case_data
      where reference = :caseReference
        and case_type_id = :caseTypeId
        and state = :state
      """;

  private final NamedParameterJdbcTemplate db;

  List<RetainAndDisposeCase> findCases(Collection<Long> caseReferences) {
    if (caseReferences.isEmpty()) {
      return List.of();
    }
    return db.query(
        SELECT_CASES_BY_REFERENCE,
        Map.of("caseReferences", caseReferences),
        (resultSet, rowNumber) -> mapCase(resultSet)
    );
  }

  List<RetainAndDisposeCase> findCasesInState(Set<String> caseTypeIds, String state) {
    return db.query(
        SELECT_CASES_IN_STATE,
        Map.of("caseTypeIds", caseTypeIds, "state", state),
        (resultSet, rowNumber) -> mapCase(resultSet)
    );
  }

  void deleteCase(long caseReference, String caseTypeId, String state) {
    int deleted = db.update(
        DELETE_CASE,
        Map.of("caseReference", caseReference, "caseTypeId", caseTypeId, "state", state)
    );
    if (deleted != 1) {
      throw new IllegalStateException("Expected to delete local case " + caseReference + " but deleted " + deleted);
    }
  }

  private RetainAndDisposeCase mapCase(ResultSet resultSet) throws SQLException {
    return new RetainAndDisposeCase(
        resultSet.getLong("reference"),
        resultSet.getString("jurisdiction"),
        resultSet.getString("case_type_id")
    );
  }
}
