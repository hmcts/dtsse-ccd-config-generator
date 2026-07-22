package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;

@RequiredArgsConstructor
class RetainAndDisposeRepository {

  private final JdbcClient db;

  List<RetainAndDisposeCase> resolveCandidates(Collection<Long> caseReferences, Set<String> caseTypeIds) {
    if (caseReferences.isEmpty()) {
      return List.of();
    }
    return db.sql("""
            select reference, jurisdiction, case_type_id, state
            from ccd.case_data
            where reference in (:caseReferences)
              and case_type_id in (:caseTypeIds)
              and state <> :disposalState
              and resolved_ttl is null
            order by reference asc
            """)
        .param("caseReferences", caseReferences)
        .param("caseTypeIds", caseTypeIds)
        .param("disposalState", DISPOSAL_STATE_ID)
        .query(RetainAndDisposeCase.class)
        .list();
  }

  List<RetainAndDisposeCase> findUnconfirmedPendingDisposalCases(Set<String> caseTypeIds) {
    return db.sql("""
            select reference, jurisdiction, case_type_id, state
            from ccd.case_data
            where case_type_id in (:caseTypeIds)
              and state = :state
              and resolved_ttl is null
            order by reference asc
            """)
        .param("caseTypeIds", caseTypeIds)
        .param("state", DISPOSAL_STATE_ID)
        .query(RetainAndDisposeCase.class)
        .list();
  }

  List<RetainAndDisposeCase> findExpiredPendingDisposalCases(Set<String> caseTypeIds) {
    return db.sql("""
            select reference, jurisdiction, case_type_id, state
            from ccd.case_data
            where case_type_id in (:caseTypeIds)
              and state = :state
              and resolved_ttl < current_date
            order by reference asc
            """)
        .param("caseTypeIds", caseTypeIds)
        .param("state", DISPOSAL_STATE_ID)
        .query(RetainAndDisposeCase.class)
        .list();
  }

  long countCasesInState(String caseTypeId, String state) {
    return db.sql("""
            select count(*)
            from ccd.case_data
            where case_type_id = :caseTypeId
              and state = :state
              and resolved_ttl is null
            """)
        .param("caseTypeId", caseTypeId)
        .param("state", state)
        .query(Long.class)
        .single();
  }

  void deletePendingDisposalCase(RetainAndDisposeCase disposalCase) {
    int deleted = db.sql("""
            delete from ccd.case_data
            where reference = :reference
              and case_type_id = :caseTypeId
              and state = :state
              and resolved_ttl < current_date
            """)
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
