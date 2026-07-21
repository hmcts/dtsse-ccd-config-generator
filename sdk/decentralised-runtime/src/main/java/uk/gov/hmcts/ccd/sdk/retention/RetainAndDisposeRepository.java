package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

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
        and case_type_id in (:caseTypeIds)
      order by reference asc
      """;
  private static final String SELECT_PENDING_DISPOSAL_CASES = """
      select reference, jurisdiction, case_type_id
      from ccd.case_data
      where case_type_id in (:caseTypeIds)
        and state = :state
      order by reference asc
      """;
  private static final String DELETE_PENDING_DISPOSAL_CASE = """
      delete from ccd.case_data
      where reference = :caseReference
        and case_type_id = :caseTypeId
        and state = :state
      """;

  private final NamedParameterJdbcTemplate db;

  List<RetainAndDisposeCase> resolveCandidates(Collection<Long> caseReferences, Set<String> caseTypeIds) {
    if (caseReferences.isEmpty()) {
      return List.of();
    }
    return db.query(
        SELECT_CASES_BY_REFERENCE,
        Map.of("caseReferences", caseReferences, "caseTypeIds", caseTypeIds),
        (resultSet, rowNumber) -> mapCase(resultSet)
    );
  }

  List<RetainAndDisposeCase> findPendingDisposalCases(Set<String> caseTypeIds) {
    return db.query(
        SELECT_PENDING_DISPOSAL_CASES,
        Map.of("caseTypeIds", caseTypeIds, "state", DISPOSAL_STATE_ID),
        (resultSet, rowNumber) -> mapCase(resultSet)
    );
  }

  void deletePendingDisposalCase(RetainAndDisposeCase disposalCase) {
    int deleted = db.update(
        DELETE_PENDING_DISPOSAL_CASE,
        Map.of(
            "caseReference", disposalCase.reference(),
            "caseTypeId", disposalCase.caseTypeId(),
            "state", DISPOSAL_STATE_ID
        )
    );
    if (deleted != 1) {
      throw new IllegalStateException(
          "Expected to delete local case " + disposalCase.reference() + " but deleted " + deleted
      );
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
