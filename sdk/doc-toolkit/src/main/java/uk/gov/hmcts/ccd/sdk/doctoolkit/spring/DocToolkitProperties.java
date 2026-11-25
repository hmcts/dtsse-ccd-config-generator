package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import java.net.URI;
import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the toolkit's services are, per environment, and whether this instance queues and runs
 * jobs. Everything about the output is set on each request, not here.
 */
@Data
@ConfigurationProperties(prefix = "ccd.doc-toolkit")
public class DocToolkitProperties {
  /**
   * Where documents are fetched and rendered. Defaults to {@code java.io.tmpdir}.
   */
  private Path tempDirectory;
  private Docmosis docmosis = new Docmosis();
  private Jobs jobs = new Jobs();

  /** The shared Docmosis service, for office formats. */
  @Data
  public static class Docmosis {
    /**
     * The absolute {@code /rs/convert} URL.
     */
    private URI convertEndpoint;
    private String accessKey;
  }

  /** The job outbox, for rendering in the background. */
  @Data
  public static class Jobs {
    /**
     * Whether {@code DocJobs} is available.
     */
    private boolean enabled;
    private Worker worker = new Worker();

    /** Whether this instance runs queued jobs. */
    @Data
    public static class Worker {
      /** False on instances that only queue jobs, when a separate deployment runs them. */
      private boolean enabled = true;
      /** How many jobs one worker renders at a time. */
      private int maxConcurrentRenders = 2;
      /** How often the worker looks for jobs. */
      private java.time.Duration pollDelay = java.time.Duration.ofSeconds(1);
    }
  }
}
