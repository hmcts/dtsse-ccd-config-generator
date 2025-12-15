package uk.gov.hmcts.ccd.sdk;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Helper to queue or count cases that need Elasticsearch reindexing.
 */
@Service
@RequiredArgsConstructor
public class CaseReindexingService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public long countCasesModifiedSince(LocalDate modifiedSince) {
        LocalDateTime since = modifiedSince.atStartOfDay();

        return jdbcTemplate.queryForObject(
            """
                select count(*)
                from ccd.case_data
                where coalesce(last_modified, created_date) >= :since
                """,
            java.util.Map.of("since", since),
            Long.class
        );
    }

    public int enqueueCasesModifiedSince(LocalDate modifiedSince) {
        LocalDateTime since = modifiedSince.atStartOfDay();

        return jdbcTemplate.update(
            """
                insert into ccd.es_queue(reference, case_revision)
                select reference, case_revision
                from ccd.case_data
                where coalesce(last_modified, created_date) >= :since
                on conflict do nothing
                """,
            java.util.Map.of("since", since)
        );
    }
}
