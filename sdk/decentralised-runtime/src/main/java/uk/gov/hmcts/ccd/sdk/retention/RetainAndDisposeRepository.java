package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

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
      where reference = :reference
        and case_type_id = :caseTypeId
        and state = :state
      """;

  private final JdbcClient db;

  List<RetainAndDisposeCase> resolveCandidates(Collection<Long> caseReferences, Set<String> caseTypeIds) {
    if (caseReferences.isEmpty()) {
      return List.of();
    }
    return db.sql(SELECT_CASES_BY_REFERENCE)
        .param("caseReferences", caseReferences)
        .param("caseTypeIds", caseTypeIds)
        .query(RetainAndDisposeCase.class)
        .list();
  }

  List<RetainAndDisposeCase> findPendingDisposalCases(Set<String> caseTypeIds) {
    return db.sql(SELECT_PENDING_DISPOSAL_CASES)
        .param("caseTypeIds", caseTypeIds)
        .param("state", DISPOSAL_STATE_ID)
        .query(RetainAndDisposeCase.class)
        .list();
  }

  void deletePendingDisposalCase(RetainAndDisposeCase disposalCase) {
    int deleted = db.sql(DELETE_PENDING_DISPOSAL_CASE)
        .param("reference", disposalCase.reference())
        .param("caseTypeId", disposalCase.caseTypeId())
        .param("state", DISPOSAL_STATE_ID)
        .update();
    if (deleted != 1) {
      throw new IllegalStateException(
          "Expected to delete local case " + disposalCase.reference() + " but deleted " + deleted
      );
    }
  }
}
