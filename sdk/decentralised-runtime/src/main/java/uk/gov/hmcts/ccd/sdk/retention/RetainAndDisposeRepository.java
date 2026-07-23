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

  List<CandidatePopulation> findCandidatePopulations(Collection<Long> caseReferences) {
    if (caseReferences.isEmpty()) {
      return List.of();
    }
    return db.sql("""
            with candidate_counts as (
              select case_type_id, state, count(*) as candidate_count
              from ccd.case_data
              where reference in (:caseReferences)
                and state <> :disposalState
                and resolved_ttl is null
              group by case_type_id, state
            )
            select candidates.case_type_id,
                   candidates.state,
                   candidates.candidate_count,
                   count(*) as total_count
            from candidate_counts candidates
            join ccd.case_data population
              on population.case_type_id = candidates.case_type_id
             and population.state = candidates.state
             and population.resolved_ttl is null
            group by candidates.case_type_id, candidates.state, candidates.candidate_count
            order by candidates.case_type_id asc, candidates.state asc
            """)
        .param("caseReferences", caseReferences)
        .param("disposalState", DISPOSAL_STATE_ID)
        .query(CandidatePopulation.class)
        .list();
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

  record CandidatePopulation(
      String caseTypeId,
      String state,
      long candidateCount,
      long totalCount
  ) {
  }
}
