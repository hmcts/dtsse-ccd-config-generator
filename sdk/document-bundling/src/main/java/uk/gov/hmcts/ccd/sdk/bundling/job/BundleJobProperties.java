package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the durable bundle job outbox, bound from ccd.bundling.job. */
@Data
@ConfigurationProperties(prefix = "ccd.bundling.job")
public class BundleJobProperties {
  private boolean enabled = false;

  private Worker worker = new Worker();
  private Retry retry = new Retry();

  /** The scheduled worker that claims and executes outbox rows. */
  @Data
  public static class Worker {
    // Matches the auto-configuration, which runs the worker unless this is set to false.
    private boolean enabled = true;

    private Duration pollDelay = Duration.ofSeconds(1);

    private int batchSize = 5;

    private int maxConcurrentRenders = 2;

    private Duration leaseDuration = Duration.ofMinutes(5);
  }

  /** Bounded backoff for transient resolution and conversion failures. */
  @Data
  public static class Retry {
    private int maxAttempts = 3;

    private Duration initialDelay = Duration.ofSeconds(5);

    private double multiplier = 2.0;

    private Duration maxDelay = Duration.ofMinutes(5);
  }
}
