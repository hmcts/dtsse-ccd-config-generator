package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void parsesSingleHostWithScheme() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts("https://es-master:9243");

    assertThat(hosts).hasSize(1);
    assertThat(hosts[0].getSchemeName()).isEqualTo("https");
    assertThat(hosts[0].getHostName()).isEqualTo("es-master");
    assertThat(hosts[0].getPort()).isEqualTo(9243);
  }

  @Test
  void parsesSingleHostWithoutSchemeAsHttp() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts("es-master:9200");

    assertThat(hosts).hasSize(1);
    assertThat(hosts[0].getSchemeName()).isEqualTo("http");
    assertThat(hosts[0].getHostName()).isEqualTo("es-master");
    assertThat(hosts[0].getPort()).isEqualTo(9200);
  }

  @Test
  void parsesMultipleCommaSeparatedHosts() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts(
        "http://es-one:9200,https://es-two:9243,es-three:9200");

    assertThat(hosts).hasSize(3);
    assertThat(hosts[0].toURI()).isEqualTo("http://es-one:9200");
    assertThat(hosts[1].toURI()).isEqualTo("https://es-two:9243");
    assertThat(hosts[2].toURI()).isEqualTo("http://es-three:9200");
  }

  @Test
  void trimsWhitespaceAroundHosts() {
    var hosts = DecentralisedESIndexer.parseElasticSearchHosts(" http://es-one:9200 , es-two:9200 ");

    assertThat(hosts).hasSize(2);
    assertThat(hosts[0].toURI()).isEqualTo("http://es-one:9200");
    assertThat(hosts[1].toURI()).isEqualTo("http://es-two:9200");
  }

  @Test
  void rejectsBlankHostConfiguration() {
    assertThatThrownBy(() -> DecentralisedESIndexer.parseElasticSearchHosts("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ELASTIC_SEARCH_HOSTS must contain at least one Elasticsearch host");
  }

  @Test
  void rejectsEmptyCommaSeparatedEntry() {
    assertThatThrownBy(() -> DecentralisedESIndexer.parseElasticSearchHosts("http://es-one:9200, ,es-two:9200"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ELASTIC_SEARCH_HOSTS contains an empty host entry");
  }

  @Test
  void rejectsMalformedHost() {
    assertThatThrownBy(() -> DecentralisedESIndexer.parseElasticSearchHosts("http://:9200"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid Elasticsearch host in ELASTIC_SEARCH_HOSTS: http://:9200")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }
}
