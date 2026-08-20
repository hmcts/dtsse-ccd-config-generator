package uk.gov.hmcts.ccd.sdk.bundling.job;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;

/**
 * Auto-configuration for the durable bundle job runner, following
 * {@code sdk/task-management}'s {@code TaskManagementAutoConfiguration}.
 *
 * <p>The outbox is never mandatory. {@code ccd.bundling.job.enabled=false} switches off every
 * bean here — a service that renders synchronously registers no outbox repository, service,
 * worker, or migration at all, so nothing can touch the absent {@code ccd_bundle_job} table.
 * When enabled (the default), every bean still backs off to a consumer-defined one, the runner
 * backs off without a {@code NamedParameterJdbcTemplate}, and the worker additionally requires a
 * {@link BundleRenderer} bean and keeps its own {@code ccd.bundling.job.worker.enabled} flag.
 * The worker's {@code @Scheduled} poll fires only when the consuming service enables scheduling.
 *
 * <p>The outbox's schema ({@code classpath:document-bundling-db/migration}) is applied by
 * {@link BundleJobFlywayConfiguration} through a module-owned Flyway instance with its own
 * history table. It deliberately does not add the location to the application's Flyway (the
 * module's version numbers, {@code V0001…}, would collide with the application's), and it
 * deliberately differs from the decentralised runtime's idiom — that runtime migrates a
 * dedicated {@code ccd} schema through the context's single {@code FlywayMigrationStrategy}
 * bean, a hook this module must leave free for it. Because the outbox table lives in the
 * consumer's own schema, which is never empty, the module instance baselines on migrate at
 * version 0 so an existing database is accepted and {@code V0001} still applies. Consumers who
 * manage the table themselves set {@code ccd.bundling.job.auto-migrate=false} and apply the
 * module's SQL from their own migrations.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ccd.bundling.job", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BundleJobProperties.class)
public class BundleJobAutoConfiguration {

  /**
   * The outbox repository over the consumer's database.
   *
   * @param jdbc the consumer's JDBC template
   * @return the repository
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(NamedParameterJdbcTemplate.class)
  public BundleJobRepository bundleJobRepository(NamedParameterJdbcTemplate jdbc) {
    return new BundleJobRepository(jdbc);
  }

  /**
   * The transactional-outbox job service.
   *
   * @param repository the outbox repository
   * @return the job service
   */
  @Bean
  @ConditionalOnMissingBean(BundleJobService.class)
  @ConditionalOnBean(NamedParameterJdbcTemplate.class)
  public OutboxBundleJobService bundleJobService(BundleJobRepository repository) {
    return new OutboxBundleJobService(repository);
  }

  /**
   * The bounded transient-failure retry policy.
   *
   * @param properties the job runner configuration
   * @return the retry policy
   */
  @Bean
  @ConditionalOnMissingBean
  public BundleJobRetryPolicy bundleJobRetryPolicy(BundleJobProperties properties) {
    BundleJobProperties.Retry retry = properties.getRetry();
    return new BundleJobRetryPolicy(retry.getMaxAttempts(), retry.getInitialDelay(),
        retry.getMultiplier(), retry.getMaxDelay());
  }

  /**
   * The overridable base-case document selector: the request exactly as submitted. A service
   * that registers its own {@link BundleDocumentSelector} moves document-list compilation to
   * execution time.
   *
   * @return the snapshot-at-submission selector
   */
  @Bean
  @ConditionalOnMissingBean(BundleDocumentSelector.class)
  public BundleDocumentSelector bundleDocumentSelector() {
    return BundleDocumentSelector.asSubmitted();
  }

  /**
   * The scheduled worker, present only when a {@link BundleRenderer} is configured.
   *
   * @param repository the outbox repository
   * @param renderer the rendering engine
   * @param selector the execution-time document selector
   * @param retryPolicy the retry policy
   * @param listeners the registered progress listeners
   * @param properties the job runner configuration
   * @return the worker
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({NamedParameterJdbcTemplate.class, BundleRenderer.class})
  @ConditionalOnProperty(name = "ccd.bundling.job.worker.enabled", havingValue = "true",
      matchIfMissing = true)
  public BundleJobWorker bundleJobWorker(BundleJobRepository repository, BundleRenderer renderer,
      BundleDocumentSelector selector, BundleJobRetryPolicy retryPolicy,
      ObjectProvider<BundleProgressListener> listeners, BundleJobProperties properties) {
    BundleJobProperties.Worker worker = properties.getWorker();
    return new BundleJobWorker(repository, renderer, selector, retryPolicy,
        listeners.orderedStream().toList(), worker.getBatchSize(),
        worker.getMaxConcurrentRenders(), worker.getLeaseDuration());
  }

  /**
   * Applies the outbox schema through a module-owned Flyway instance, present only when Flyway
   * and a {@link DataSource} are available and {@code ccd.bundling.job.auto-migrate} is not
   * turned off. The instance keeps its own history table so the module's migration versions
   * never collide with the application's own Flyway history, and baselines on migrate at
   * version 0: a consumer's schema is never empty, and without a baseline Flyway would refuse
   * it ("found non-empty schema(s) … but no schema history table"), while the default baseline
   * version of 1 would silently skip {@code V0001}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Flyway.class)
  public static class BundleJobFlywayConfiguration {

    /** The module-owned Flyway history table, distinct from the application's. */
    public static final String HISTORY_TABLE = "ccd_bundle_job_flyway_history";

    /**
     * Runs the module's migrations while this bean is instantiated during context refresh.
     *
     * <p>Ordering caveat, stated honestly: Spring gives no guarantee about where this singleton
     * falls in the refresh order relative to other beans' own initialisation callbacks. The
     * shipped {@link BundleJobWorker} is safe — its scheduled polling cannot start before the
     * context has refreshed — but a consumer bean that queries the outbox from its own
     * {@code @PostConstruct}/{@code afterPropertiesSet} must declare
     * {@code @DependsOn("bundleJobFlywayMigration")} to be sure the table exists.
     *
     * @param dataSource the consumer's data source
     * @return the migration hook
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "ccd.bundling.job", name = "auto-migrate", havingValue = "true",
        matchIfMissing = true)
    public InitializingBean bundleJobFlywayMigration(DataSource dataSource) {
      return () -> Flyway.configure()
          .dataSource(dataSource)
          .table(HISTORY_TABLE)
          .locations("classpath:document-bundling-db/migration")
          // Consumer schemas already hold the application's tables; accept them and still apply
          // V0001 (the default baseline version of 1 would skip it).
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .load()
          .migrate();
    }
  }
}
