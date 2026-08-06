package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DecentralisedESIndexerTest {
  private final DriverManagerDataSource dataSource = new DriverManagerDataSource();

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(DecentralisedESIndexer.class)
      .withBean(DataSource.class, () -> dataSource)
      .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
      .withBean(TransactionTemplate.class, () ->
          new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

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
  void parsesSingleHostWithScheme() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts("https://es-master:9243");

    assertThat(hosts).hasSize(1);
    assertThat(hosts[0].toString()).isEqualTo("https://es-master:9243");
  }

  @Test
  void parsesSingleHostWithoutSchemeAsHttp() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts("es-master:9200");

    assertThat(hosts).hasSize(1);
    assertThat(hosts[0].toString()).isEqualTo("http://es-master:9200");
  }

  @Test
  void parsesBareIpAsHttp() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts("10.96.149.253");

    assertThat(hosts).hasSize(1);
    assertThat(hosts[0].toString()).isEqualTo("http://10.96.149.253");
  }

  @Test
  void parsesMultipleCommaSeparatedHosts() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts(
        "http://es-one:9200,https://es-two:9243,es-three:9200");

    assertThat(hosts).extracting(Object::toString)
        .containsExactly(
            "http://es-one:9200",
            "https://es-two:9243",
            "http://es-three:9200");
  }

  @Test
  void parsesQuotedCommaSeparatedHosts() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts(
        "\"http://ccd-data-0.service.core-compute-aat.internal:9200\","
            + "\"http://ccd-data-1.service.core-compute-aat.internal:9200\"");

    assertThat(hosts).extracting(Object::toString)
        .containsExactly(
            "http://ccd-data-0.service.core-compute-aat.internal:9200",
            "http://ccd-data-1.service.core-compute-aat.internal:9200");
  }

  @Test
  void trimsWhitespaceAroundHosts() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts(" http://es-one:9200 , es-two:9200 ");

    assertThat(hosts).extracting(Object::toString)
        .containsExactly("http://es-one:9200", "http://es-two:9200");
  }

  @Test
  void rejectsEmptyCommaSeparatedEntry() {
    assertThatThrownBy(() -> DecentralisedESIndexer.parseElasticSearchHosts("http://es-one:9200, ,es-two:9200"))
        .isInstanceOf(IllegalArgumentException.class);
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
