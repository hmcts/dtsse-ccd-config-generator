package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in queue population; progress is recorded by the existing jobs and publications. */
@Data
@ConfigurationProperties(prefix = "ccd.bundling.backfill")
public class BundleBackfillProperties {
  private boolean enabled;
  private String definition;
  private int batchSize = 25;
  private Duration pollDelay = Duration.ofSeconds(10);

  void validate() {
    if (definition == null || !definition.matches("[a-zA-Z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("Backfill definition must be 1-128 letters, digits, ., _ or -");
    }
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("Backfill batch-size must be between 1 and 1000");
    }
    if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()) {
      throw new IllegalArgumentException("Backfill poll-delay must be positive");
    }
  }
}
