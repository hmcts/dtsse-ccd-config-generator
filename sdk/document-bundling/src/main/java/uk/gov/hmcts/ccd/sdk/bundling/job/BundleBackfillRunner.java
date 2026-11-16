package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.HashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.managed.CaseBundleBackfillSource;

/** Scans once per process; the existing queue and publications make rescanning safe on restart. */
@Slf4j
public class BundleBackfillRunner {
  private final TransactionTemplate transaction;
  private final ManagedCaseBundles bundles;
  private final CaseBundleBackfillSource source;
  private final BundleBackfillProperties properties;
  private String after;
  private boolean complete;

  public BundleBackfillRunner(PlatformTransactionManager transactions, ManagedCaseBundles bundles,
      CaseBundleBackfillSource source, BundleBackfillProperties properties) {
    properties.validate();
    this.bundles = bundles;
    this.source = source;
    this.properties = properties;
    transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** Disabling the scanner does not cancel jobs that it has already queued. */
  @Scheduled(fixedDelayString = "${ccd.bundling.backfill.poll-delay:10s}",
      initialDelayString = "${ccd.bundling.backfill.poll-delay:10s}")
  public synchronized void poll() {
    if (!properties.isEnabled() || complete) {
      return;
    }
    List<String> keys = transaction.execute(status -> {
      var page = List.copyOf(source.caseKeysAfter(after, properties.getBatchSize()));
      if (page.size() > properties.getBatchSize()
          || page.stream().anyMatch(String::isBlank)
          || new HashSet<>(page).size() != page.size()
          || after != null && page.contains(after)) {
        throw new IllegalArgumentException("Backfill source must return a bounded, advancing page of case keys");
      }
      for (String key : page) {
        bundles.backfill(properties.getDefinition(), key);
      }
      return page;
    });
    // Move forward only after the jobs commit. A failed page is retried on the next poll.
    complete = keys.isEmpty();
    if (!complete) {
      after = keys.getLast();
    }
    log.info("Bundle backfill definition={} scanned={} scanComplete={}",
        properties.getDefinition(), keys.size(), complete);
  }
}
