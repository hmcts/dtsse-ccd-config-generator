package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the durable bundle job outbox, bound from {@code ccd.bundling.job}.
 */
@Data
@ConfigurationProperties(prefix = "ccd.bundling.job")
public class BundleJobProperties {

  /**
   * Whether the durable job runner registers at all. {@code false} backs off every outbox bean —
   * repository, service, worker, and the schema migration — so a service that renders
   * synchronously needs no {@code ccd_bundle_job} table.
   */
  private boolean enabled = true;

  /**
   * Whether the module applies its own outbox schema ({@code document-bundling-db/migration})
   * through a module-owned Flyway instance on startup. Turn off to manage the table from the
   * consuming service's own migrations instead.
   */
  private boolean autoMigrate = true;

  private Worker worker = new Worker();
  private Retry retry = new Retry();

  /**
   * The scheduled worker that claims and executes outbox rows.
   */
  @Data
  public static class Worker {

    /** Whether the scheduled worker runs; the outbox is never mandatory. */
    private boolean enabled = true;

    /** The delay between polls; read by the worker's scheduled trigger. */
    private Duration pollDelay = Duration.ofSeconds(1);

    /** The maximum number of jobs one poll claims. */
    private int batchSize = 5;

    /** The maximum number of renders in flight at once in this JVM. */
    private int maxConcurrentRenders = 2;

    /**
     * How long a claim's lease lasts before the job is reclaimable by any worker.
     *
     * <p>Invariant: this MUST comfortably exceed the renderer's enforced end-to-end timeout
     * ({@code BundleLimits.maxElapsed}, one minute by default). A render outliving its lease is
     * reclaimed and rendered again; the lease-guarded writes keep the recorded outcome single,
     * but the duplicate render work is avoidable by honouring this bound.
     */
    private Duration leaseDuration = Duration.ofMinutes(5);
  }

  /**
   * Bounded backoff for typed transient failures; rendering and validation failures are never
   * retried.
   */
  @Data
  public static class Retry {

    /** The total number of executions a job may consume before it fails. */
    private int maxAttempts = 3;

    /** The delay before the second attempt. */
    private Duration initialDelay = Duration.ofSeconds(5);

    /** The backoff multiplier applied to each subsequent delay. */
    private double multiplier = 2.0;

    /** The delay ceiling; zero means uncapped. */
    private Duration maxDelay = Duration.ofMinutes(5);
  }
}
