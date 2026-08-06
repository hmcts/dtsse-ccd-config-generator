package uk.gov.hmcts.ccd.sdk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Helper to queue or count cases that need Elasticsearch reindexing.
 */
@Service
public class CaseReindexingService {

  private static final long DEFAULT_REINDEX_QUEUE_PRIORITY_OFFSET_SECONDS = 21_600;

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final long reindexQueuePriorityOffsetSeconds;

  public CaseReindexingService(NamedParameterJdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, DEFAULT_REINDEX_QUEUE_PRIORITY_OFFSET_SECONDS);
  }

  @Autowired
  public CaseReindexingService(
      NamedParameterJdbcTemplate jdbcTemplate,
      @Value("${ccd.sdk.reindexing.queue-priority-offset-seconds:"
          + DEFAULT_REINDEX_QUEUE_PRIORITY_OFFSET_SECONDS + "}") long reindexQueuePriorityOffsetSeconds
  ) {
    if (reindexQueuePriorityOffsetSeconds < 0) {
      throw new IllegalArgumentException("ccd.sdk.reindexing.queue-priority-offset-seconds must not be negative");
    }
    this.jdbcTemplate = jdbcTemplate;
    this.reindexQueuePriorityOffsetSeconds = reindexQueuePriorityOffsetSeconds;
  }

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

    // enqueued_at denotes priority; we use an offset to deprioritise reindexing vs live updates to cases.
    return jdbcTemplate.update(
        """
            insert into ccd.es_queue(reference, case_revision, enqueued_at)
            select reference, case_revision, now() + (:priority_offset_seconds * interval '1 second')
            from ccd.case_data
            where coalesce(last_modified, created_date) >= :since
            on conflict (reference) do update
            set case_revision = greatest(ccd.es_queue.case_revision, excluded.case_revision),
                enqueued_at = least(ccd.es_queue.enqueued_at, excluded.enqueued_at)
            """,
        java.util.Map.of(
            "since", since,
            "priority_offset_seconds", reindexQueuePriorityOffsetSeconds
        )
    );
  }
}
