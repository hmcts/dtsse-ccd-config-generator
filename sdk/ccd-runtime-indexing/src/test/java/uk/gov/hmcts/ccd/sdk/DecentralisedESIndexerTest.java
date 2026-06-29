package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DecentralisedESIndexerTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(DecentralisedESIndexer.class)
      .withBean(JdbcTemplate.class, () -> new JdbcTemplate(new DriverManagerDataSource()))
      .withBean(TransactionTemplate.class, () ->
          new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource())));

  @Test
  void startsIndexerWhenIndexerConfigurationIsMissing() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(DecentralisedESIndexer.class));
  }

  @Test
  void doesNotStartIndexerWhenDisabled() {
    contextRunner
        .withPropertyValues("ccd.sdk.decentralised.es-indexer.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DecentralisedESIndexer.class));
  }

  @Test
  void startsIndexerWhenExplicitlyEnabled() {
    contextRunner
        .withPropertyValues("ccd.sdk.decentralised.es-indexer.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(DecentralisedESIndexer.class));
  }

  @Test
  void treatsSuccessfulBulkActionsAsComplete() {
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(200))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.COMPLETE);
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(201))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.COMPLETE);
  }

  @Test
  void treatsVersionConflictAsComplete() {
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(409))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.COMPLETE);
  }

  @Test
  void treatsLogstashDlqDocumentStatusesAsDeadLetter() {
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(400))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.DEAD_LETTER);
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(404))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.DEAD_LETTER);
  }

  @Test
  void treatsOtherFailuresAsRetryable() {
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(413))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.RETRYABLE);
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(429))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.RETRYABLE);
    assertThat(DecentralisedESIndexer.classifyBulkActionStatus(500))
        .isEqualTo(DecentralisedESIndexer.BulkActionOutcome.RETRYABLE);
  }
}
