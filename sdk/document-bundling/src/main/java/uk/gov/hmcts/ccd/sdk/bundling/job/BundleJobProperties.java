package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the bundle job queue, bound from ccd.bundling.job. */
@Data
@ConfigurationProperties(prefix = "ccd.bundling.job")
public class BundleJobProperties {
  private boolean enabled = false;

  private Worker worker = new Worker();
  private Retry retry = new Retry();

  /** The scheduled worker that claims and renders queue rows. */
  @Data
  public static class Worker {
    // Matches the auto-configuration, which runs the worker unless this is set to false.
    private boolean enabled = true;

    private Duration pollDelay = Duration.ofSeconds(1);

    private int batchSize = 5;

    private int maxConcurrentRenders = 2;

    private Duration leaseDuration = Duration.ofMinutes(5);
  }

  /** How a render that did not finish is tried again. */
  @Data
  public static class Retry {
    /** How many times a bundle may be claimed without recording a result, as a crash leaves it. */
    private int maxAttempts = 3;

    /** How long to wait before retrying while a dependency is down; retried until it is back. */
    private Duration delay = Duration.ofMinutes(1);
  }
}
