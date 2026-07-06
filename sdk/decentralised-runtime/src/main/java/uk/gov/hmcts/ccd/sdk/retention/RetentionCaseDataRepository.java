package uk.gov.hmcts.ccd.sdk.retention;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class RetentionCaseDataRepository {
  private static final String SELECT_CASE_DATA = """
      select reference, id, case_type_id, jurisdiction, resolved_ttl
      from ccd.case_data
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public RetentionCaseDataRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<RetentionCaseData> findExpiredCases(Collection<String> caseTypeIds, int limit) {
    if (caseTypeIds.isEmpty() || limit <= 0) {
      return List.of();
    }

    String sql = SELECT_CASE_DATA + """
        where case_type_id in (:caseTypeIds)
          and resolved_ttl < current_date
        order by resolved_ttl desc
        limit :limit
        """;

    return jdbcTemplate.query(sql, new MapSqlParameterSource()
        .addValue("caseTypeIds", caseTypeIds)
        .addValue("limit", limit), rowMapper());
  }

  public int deleteCases(Collection<Long> references) {
    if (references.isEmpty()) {
      return 0;
    }

    return jdbcTemplate.update("delete from ccd.case_data where reference in (:references)",
        new MapSqlParameterSource("references", references));
  }

  private RowMapper<RetentionCaseData> rowMapper() {
    return (rs, rowNum) -> new RetentionCaseData(
        rs.getObject("reference", Long.class),
        rs.getObject("id", Long.class),
        rs.getString("case_type_id"),
        rs.getString("jurisdiction"),
        rs.getObject("resolved_ttl", java.time.LocalDate.class)
    );
  }
}
