package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobHandler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobWorker;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocJobs;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.jobs.DocJobRepository;

/**
 * Background rendering, on with {@code ccd.doc-toolkit.jobs.enabled=true}: the job store,
 * {@link DocJobs}, and a worker unless {@code ccd.doc-toolkit.jobs.worker.enabled} is false.
 */
@AutoConfiguration(after = DocToolkitAutoConfiguration.class,
    afterName = {"org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"})
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
      ObjectProvider<DocJobHandler> handlers,
      ObjectProvider<PlatformTransactionManager> transactionManagers, DataSource dataSource,
      DocToolkitProperties properties) {
    List<DocJobHandler> found = handlers.orderedStream().toList();
    if (found.size() != 1) {
      throw new IllegalStateException("The doc job worker needs one DocJobHandler bean to hand"
          + " finished documents to, but found " + found.size() + ". Provide one, or set"
          + " ccd.doc-toolkit.jobs.worker.enabled=false on instances that only queue jobs.");
    }
    // The handler and the worker's bookkeeping share the application's transaction manager, so a
    // JPA handler and the toolkit's JDBC writes commit or roll back together.
    PlatformTransactionManager transactionManager = transactionManagers.getIfAvailable(
        () -> new DataSourceTransactionManager(dataSource));
    DocToolkitProperties.Jobs.Worker worker = properties.getJobs().getWorker();
    DocJobWorker.Settings settings = DocJobWorker.Settings.defaults()
        .withMaxConcurrentRenders(worker.getMaxConcurrentRenders())
        .withPollDelay(worker.getPollDelay());
    return new DocJobWorkerLifecycle(new DocJobWorker(repository, toolkit, found.getFirst(),
        new TransactionTemplate(transactionManager), settings));
  }

  /** Starts and stops the worker with the application. */
  public static final class DocJobWorkerLifecycle implements SmartLifecycle {
    private final DocJobWorker worker;
    private volatile boolean running;

    DocJobWorkerLifecycle(DocJobWorker worker) {
      this.worker = worker;
    }

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
