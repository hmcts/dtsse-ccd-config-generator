package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleDefinition;

/**
 * Queues a page of each definition's cases per poll. The scan's position lives in
 * bundling.bundle_backfill and advances with the page it queued, so pods share one scan and a
 * restart resumes it.
 */
@Slf4j
public class BundleBackfillRunner {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ManagedCaseBundles bundles;
  private final List<CaseBundleDefinition> definitions;
  private final String token;
  private final int batchSize;
  private final Set<String> unsupported = new HashSet<>();

  public BundleBackfillRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions,
      ManagedCaseBundles bundles, List<CaseBundleDefinition> definitions, String token, int batchSize) {
    if (token == null || !token.matches("[a-zA-Z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("Backfill token must be 1-128 letters, digits, ., _ or -");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("Backfill batch-size must be between 1 and 1000");
    }
    if (definitions.isEmpty()) {
      throw new IllegalArgumentException("Backfill needs at least one CaseBundleDefinition");
    }
    this.jdbc = jdbc;
    this.bundles = bundles;
    this.definitions = List.copyOf(definitions);
    this.token = token;
    this.batchSize = batchSize;
    transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** One short transaction per definition; a failed page retries without holding up other definitions. */
  @Scheduled(fixedDelayString = "${ccd.bundling.backfill.poll-delay:10s}",
      initialDelayString = "${ccd.bundling.backfill.poll-delay:10s}")
  public void poll() {
    for (var definition : definitions) {
      String name = definition.name();
      if (!unsupported.contains(name)) {
        try {
          transaction.executeWithoutResult(status -> scan(definition));
        } catch (UnsupportedOperationException failure) {
          unsupported.add(name);
          log.warn("Bundle backfill token={} definition={} unsupported; skipping until restart: {}",
              token, name, failure.getMessage());
        } catch (RuntimeException failure) {
          log.warn("Bundle backfill token={} definition={} page failed; retrying next poll",
              token, name, failure);
        }
      }
    }
  }

  private void scan(CaseBundleDefinition definition) {
    String name = definition.name();
    var scan = Map.of("token", token, "definition", name);
    jdbc.update("""
        insert into bundling.bundle_backfill (token, definition) values (:token, :definition)
        on conflict do nothing
        """, scan);
    // No row: the scan is complete, or another pod is queuing its next page.
    List<String> position = jdbc.query("""
        select after_key from bundling.bundle_backfill
        where token = :token and definition = :definition and completed_at is null
        for update skip locked
        """, scan, (rs, n) -> rs.getString(1));
    if (position.isEmpty()) {
      return;
    }
    String after = position.getFirst();
    List<String> page = definition.caseKeysAfter(after, batchSize);
    if (page.size() > batchSize || after != null && page.contains(after)) {
      throw new IllegalArgumentException("Backfill definition must return a bounded, advancing page of case keys");
    }
    page.forEach(key -> bundles.backfill(name, key));
    if (page.isEmpty()) {
      jdbc.update("""
          update bundling.bundle_backfill set completed_at = now()
          where token = :token and definition = :definition
          """, scan);
    } else {
      jdbc.update("""
          update bundling.bundle_backfill set after_key = :last
          where token = :token and definition = :definition
          """, Map.of("token", token, "definition", name, "last", page.getLast()));
    }
    log.info("Bundle backfill token={} definition={} queued={} scanComplete={}",
        token, name, page.size(), page.isEmpty());
  }
}
