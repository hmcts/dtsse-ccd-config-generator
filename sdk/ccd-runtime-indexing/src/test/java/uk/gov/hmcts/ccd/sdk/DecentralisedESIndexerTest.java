package uk.gov.hmcts.ccd.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecentralisedESIndexerTest {

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
