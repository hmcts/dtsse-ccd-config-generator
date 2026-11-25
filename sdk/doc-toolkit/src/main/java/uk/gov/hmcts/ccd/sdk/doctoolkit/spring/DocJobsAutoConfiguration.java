package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobWorker;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;

/**
 * The job outbox, when {@code ccd.doc-toolkit.jobs.enabled} is true: {@link DocJobs} to queue
 * renders and, unless {@code ccd.doc-toolkit.jobs.worker.enabled} is false, a
 * {@link DocJobWorker} running them, which needs the service's {@link DocJobHandler}.
 */
@AutoConfiguration(after = DocToolkitAutoConfiguration.class,
    afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.doc-toolkit.jobs", name = "enabled", havingValue = "true")
public class DocJobsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DocJobRepository docJobRepository(NamedParameterJdbcTemplate jdbc) {
    return new DocJobRepository(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean
  public DocJobs docJobs(DocJobRepository repository) {
    return new DocJobs(repository);
  }

  @Bean
  @ConditionalOnProperty(prefix = "ccd.doc-toolkit.jobs.worker", name = "enabled",
      matchIfMissing = true)
  public DocJobWorkerLifecycle docJobWorker(DocJobRepository repository, DocToolkit toolkit,
      ObjectProvider<DocJobHandler> handlers) {
    List<DocJobHandler> found = handlers.orderedStream().toList();
    if (found.size() != 1) {
      throw new IllegalStateException("The doc job worker needs one DocJobHandler bean to hand"
          + " finished documents to, but found " + found.size() + ". Provide one, or set"
          + " ccd.doc-toolkit.jobs.worker.enabled=false on instances that only queue jobs.");
    }
    return new DocJobWorkerLifecycle(new DocJobWorker(repository, toolkit, found.getFirst(),
        DocJobWorker.Settings.defaults()));
  }

  /** Starts the worker with the application and stops it on shutdown. */
  public static final class DocJobWorkerLifecycle implements SmartLifecycle {
    private final DocJobWorker worker;
    private volatile boolean running;

    DocJobWorkerLifecycle(DocJobWorker worker) {
      this.worker = worker;
    }

    /** The worker. */
    public DocJobWorker worker() {
      return worker;
    }

    @Override
    public void start() {
      worker.start();
      running = true;
    }

    @Override
    public void stop() {
      worker.close();
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }
  }
}
