package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.HashMap;
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

/** Completion markers remember scans; the existing job queue deduplicates work after a crash. */
@Slf4j
public class BundleBackfillRunner {
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ManagedCaseBundles bundles;
  private final List<CaseBundleDefinition> definitions;
  private final String token;
  private final int batchSize;
  private final Map<String, String> cursors = new HashMap<>();
  private final Set<String> completed = new HashSet<>();

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
  public synchronized void poll() {
    for (var definition : definitions) {
      if (!completed.contains(definition.name())) {
        try {
          scan(definition);
        } catch (UnsupportedOperationException unsupported) {
          completed.add(definition.name());
          log.warn("Bundle backfill token={} definition={} unsupported; skipping until restart: {}",
              token, definition.name(), unsupported.getMessage());
        } catch (RuntimeException failure) {
          log.warn("Bundle backfill token={} definition={} page failed; retrying next poll",
              token, definition.name(), failure);
        }
      }
    }
  }

  private void scan(CaseBundleDefinition definition) {
    String name = definition.name();
    var run = Map.of("token", token, "definition", name);
    List<String> keys = transaction.execute(status -> {
      if (Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists (select 1 from bundling.bundle_backfill
                         where token = :token and definition = :definition)
          """, run, Boolean.class))) {
        return List.of();
      }
      String after = cursors.get(name);
      var page = List.copyOf(definition.caseKeysAfter(after, batchSize));
      if (page.size() > batchSize || page.stream().anyMatch(String::isBlank)
          || new HashSet<>(page).size() != page.size() || after != null && page.contains(after)) {
        throw new IllegalArgumentException("Backfill definition must return a bounded, advancing page of case keys");
      }
      for (String key : page) {
        bundles.backfill(token, name, key);
      }
      if (page.isEmpty()) {
        jdbc.update("""
            insert into bundling.bundle_backfill (token, definition) values (:token, :definition)
            on conflict do nothing
            """, run);
      }
      return page;
    });
    // Advance only after commit. A crash can rescan, but deterministic job IDs prevent repeat work.
    if (keys.isEmpty()) {
      completed.add(name);
    } else {
      cursors.put(name, keys.getLast());
    }
    log.info("Bundle backfill token={} definition={} scanned={} scanComplete={}",
        token, name, keys.size(), keys.isEmpty());
  }
}
