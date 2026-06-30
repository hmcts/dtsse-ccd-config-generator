package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.Map.entry;

@Testcontainers
class DecentralisedESIndexerChaosTest {

  private static final String CASE_TYPE = "ChaosCase";
  private static final String CASE_INDEX = "chaoscase_cases";
  private static final String POISON_CASE_TYPE = "PoisonCase";
  private static final String POISON_INDEX = "poisoncase_cases";
  private static final int DEFAULT_CASE_COUNT = 200;
  private static final int DEFAULT_REVISION_COUNT = 4;
  private static final Network NETWORK = Network.newNetwork();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("ccd");

  @Container
  private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
      DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.4.2"))
      .withNetwork(NETWORK)
      .withNetworkAliases("elasticsearch")
      .withEnv("xpack.security.enabled", "false");

  @Container
  private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
      DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
      .withNetwork(NETWORK);

  private static ToxiproxyContainer.ContainerProxy elasticsearchProxy;

  private ConfigurableApplicationContext context;
  private ConfigurableApplicationContext secondContext;

  @AfterEach
  void closeContext() {
    unpauseElasticsearchContainer();
    restoreElasticsearchConnection();
    if (secondContext != null) {
      secondContext.close();
      secondContext = null;
    }
    if (context != null) {
      context.close();
      context = null;
    }
  }

  @Test
  void notificationDrivenIndexerRecoversAfterElasticsearchNetworkCut() {
    context = startApplication();
    resetState();

    int caseCount = Integer.getInteger("ccd.indexing.chaos.case-count", DEFAULT_CASE_COUNT);
    int revisionCount = Integer.getInteger("ccd.indexing.chaos.revision-count", DEFAULT_REVISION_COUNT);

    for (long caseId = 1; caseId <= caseCount; caseId++) {
      commitCaseRevision(caseId, caseId, CASE_TYPE, 1, "revision-1-case-" + caseId);
    }
    awaitCohortIndexed(1, caseCount, 1);

    cutElasticsearchConnection();
    for (int revision = 2; revision <= revisionCount; revision++) {
      for (long caseId = 1; caseId <= caseCount; caseId++) {
        commitCaseRevision(caseId, caseId, CASE_TYPE, revision, "revision-" + revision + "-case-" + caseId);
      }
    }

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      assertThat(context.isActive()).isTrue();
      assertThat(queueSize()).isGreaterThan(0);
    });

    restoreElasticsearchConnection();

    awaitCohortIndexed(1, caseCount, revisionCount);
    assertQueueEventuallyEmpty();
  }

  @Test
  void twoSpringIndexerInstancesDrainTheSameQueueWithoutLosingUpdates() {
    context = startApplication();
    resetState();
    secondContext = startApplication();

    for (long caseId = 4001; caseId <= 4100; caseId++) {
      commitCaseRevision(caseId, caseId, CASE_TYPE, 1, "two-indexers-initial-" + caseId);
      commitCaseRevision(caseId, caseId, CASE_TYPE, 2, "two-indexers-latest-" + caseId);
    }

    awaitCohortIndexed(4001, 4100, 2);
    assertQueueEventuallyEmpty();
  }

  @Test
  void concurrentCommittedWritersEventuallyIndexLatestRevision() throws Exception {
    context = startApplication();
    resetState();

    int writerCount = 8;
    int casesPerWriter = 25;
    int revisions = 3;
    try (ExecutorService executor = Executors.newFixedThreadPool(writerCount)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int writer = 0; writer < writerCount; writer++) {
        long firstCaseId = 5001L + (long) writer * casesPerWriter;
        futures.add(executor.submit(() -> {
          for (long caseId = firstCaseId; caseId < firstCaseId + casesPerWriter; caseId++) {
            for (int revision = 1; revision <= revisions; revision++) {
              commitCaseRevision(caseId, caseId, CASE_TYPE, revision,
                  "concurrent-revision-" + revision + "-case-" + caseId);
            }
          }
        }));
      }

      for (Future<?> future : futures) {
        future.get();
      }
    }

    awaitCohortIndexed(5001, 5200, revisions);
    assertQueueEventuallyEmpty();
  }

  @Test
  void notificationDrivenIndexerRecoversAfterElasticsearchContainerPause() {
    context = startApplication();
    resetState();

    ELASTICSEARCH.getDockerClient()
        .pauseContainerCmd(ELASTICSEARCH.getContainerId())
        .exec();

    for (long caseId = 6001; caseId <= 6025; caseId++) {
      commitCaseRevision(caseId, caseId, CASE_TYPE, 1, "es-restart-" + caseId);
    }

    unpauseElasticsearchContainer();
    awaitElasticsearchAvailable();
    awaitCohortIndexed(6001, 6025, 1);
    assertQueueEventuallyEmpty();
  }

  @Test
  void searchCriteriaCasesAreAlsoIndexedIntoGlobalSearch() throws Exception {
    context = startApplication();
    resetState();

    commitCaseRevisionWithData(
        7001,
        7001,
        CASE_TYPE,
        1,
        """
        {
          "marker": "global-search-case",
          "counter": 7001,
          "expected_revision": 1,
          "SearchCriteria": {
            "OtherCaseReferences": [
              {
                "value": {
                  "CaseReference": "7001"
                }
              }
            ]
          },
          "caseNameHmctsInternal": "Global Search Case",
          "fieldNotForGlobalSearch": "must-not-leak"
        }
        """);

    awaitLatestIndexed(7001, 7001, 1);

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      JsonNode document = fetchDocument("global_search", 7001);
      assertThat(document.path("index_id").asText()).isEqualTo("global_search");
      assertThat(document.path("data").has("SearchCriteria")).isTrue();
      assertThat(document.path("data").path("caseNameHmctsInternal").asText()).isEqualTo("Global Search Case");
      assertThat(document.path("data").has("fieldNotForGlobalSearch")).isFalse();
    });
  }

  @Test
  void validItemsInMixedBulkCanBeAcceptedWhilePoisonDocumentsAreDeadLettered() throws Exception {
    context = startApplication();
    resetState();
    createPoisonMapping();

    cutElasticsearchConnection();
    for (long caseId = 8001; caseId <= 8099; caseId++) {
      commitCaseRevision(caseId, caseId, POISON_CASE_TYPE, 1, "mixed-poison-" + caseId);
    }
    commitCaseRevision(8100, 8100, CASE_TYPE, 1, "mixed-valid");
    restoreElasticsearchConnection();

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      JsonNode document = fetchDocument(CASE_INDEX, 8100);
      assertThat(document.path("data").path("marker").asText()).isEqualTo("mixed-valid");
      assertThat(deadLetterSize()).isEqualTo(99);
      assertThat(queueSize()).isZero();
    });
  }

  @Test
  void pendingQueueSurvivesSpringContextRestart() {
    context = startApplication();
    resetState();

    cutElasticsearchConnection();
    commitCaseRevision(1001, 1001, CASE_TYPE, 1, "restart-one");
    commitCaseRevision(1002, 1002, CASE_TYPE, 1, "restart-two");

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(queueSize()).isGreaterThan(0));

    context.close();
    context = null;
    restoreElasticsearchConnection();

    context = startApplication();

    awaitLatestIndexed(1001, 1002, 1);
    assertQueueEventuallyEmpty();
  }

  @Test
  void poisonPillDoesNotBlockLaterIndexableCases() throws Exception {
    context = startApplication();
    resetState();
    createPoisonMapping();

    cutElasticsearchConnection();
    for (long caseId = 2001; caseId <= 2100; caseId++) {
      commitCaseRevision(caseId, caseId, POISON_CASE_TYPE, 1, "poison-" + caseId);
    }
    commitCaseRevision(3001, 3001, CASE_TYPE, 1, "valid-behind-poison");
    makePoisonQueueEntriesOlderThanValidEntry();
    restoreElasticsearchConnection();

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      JsonNode document = fetchDocument(CASE_INDEX, 3001);
      assertThat(document.path("data").path("marker").asText()).isEqualTo("valid-behind-poison");
    });
  }

  @Test
  void poisonPillFailuresAreRecordedInDeadLetterQueue() throws Exception {
    context = startApplication();
    resetState();
    createPoisonMapping();

    commitCaseRevision(9001, 9001, POISON_CASE_TYPE, 1, "dead-letter-poison");
    long eventId = latestEventId(9001);

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Map<String, Object> deadLetter = jdbc().queryForMap(
          """
          select case_event_id, index_id, timestamp, failure_message
            from ccd.es_dead_letter_queue
           where case_event_id = :case_event_id
             and index_id = :index_id
          """,
          Map.of("case_event_id", eventId, "index_id", POISON_INDEX));

      assertThat(((Number) deadLetter.get("case_event_id")).longValue()).isEqualTo(eventId);
      assertThat(deadLetter.get("index_id")).isEqualTo(POISON_INDEX);
      assertThat(deadLetter.get("timestamp")).isNotNull();
      assertThat((String) deadLetter.get("failure_message")).isNotBlank();
    });
  }

  @Test
  void globalSearchPoisonFailuresAreRecordedInDeadLetterQueue() throws Exception {
    context = startApplication();
    resetState();
    createGlobalSearchPoisonMapping();

    commitCaseRevisionWithData(
        9101,
        9101,
        CASE_TYPE,
        1,
        """
        {
          "marker": "global-search-poison",
          "counter": 9101,
          "expected_revision": 1,
          "SearchCriteria": {
            "OtherCaseReferences": [
              {
                "value": {
                  "CaseReference": "9101"
                }
              }
            ]
          }
        }
        """);
    long eventId = latestEventId(9101);

    awaitLatestIndexed(9101, 9101, 1);
    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Map<String, Object> deadLetter = jdbc().queryForMap(
          """
          select case_event_id, index_id, timestamp, failure_message
            from ccd.es_dead_letter_queue
           where case_event_id = :case_event_id
             and index_id = :index_id
          """,
          Map.of("case_event_id", eventId, "index_id", "global_search"));

      assertThat(((Number) deadLetter.get("case_event_id")).longValue()).isEqualTo(eventId);
      assertThat(deadLetter.get("index_id")).isEqualTo("global_search");
      assertThat(deadLetter.get("timestamp")).isNotNull();
      assertThat((String) deadLetter.get("failure_message")).contains("global_search");
      assertThat(queueSize()).isZero();
    });
  }

  @Test
  void successfulRemediationReindexClearsOlderDeadLetterRowsForTheSameIndex() throws Exception {
    context = startApplication();
    resetState();
    createPoisonMapping();

    commitCaseRevision(9201, 9201, POISON_CASE_TYPE, 1, "dead-letter-before-remediation");

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(deadLetterSize()).isEqualTo(1);
      assertThat(queueSize()).isZero();
    });

    deleteIndex(POISON_INDEX);
    commitCaseRevision(9201, 9201, POISON_CASE_TYPE, 2, "indexed-after-remediation");

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      JsonNode document = fetchDocument(POISON_INDEX, 9201);
      assertThat(document.path("data").path("poison").path("marker").asText())
          .isEqualTo("indexed-after-remediation");
      assertThat(deadLetterSize()).isZero();
      assertThat(queueSize()).isZero();
    });
  }

  @Test
  void queueNotificationWakesIndexerBeforeFallbackPoll() throws Exception {
    context = startApplication();
    resetState();

    commitCaseRevision(9301, 9301, CASE_TYPE, 1, "notification-wakeup");

    await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      JsonNode document = fetchDocument(CASE_INDEX, 9301);
      assertThat(document.path("data").path("marker").asText()).isEqualTo("notification-wakeup");
      assertThat(queueSize()).isZero();
    });
  }

  @Test
  void caseReindexingServiceQueueUpdateWakesIndexerBeforeFallbackPoll() throws Exception {
    context = startApplication();
    resetState();

    commitCaseRevision(9401, 9401, CASE_TYPE, 1, "reindex-notification");
    awaitLatestIndexed(9401, 9401, 1);
    deleteIndex(CASE_INDEX);

    int queued = context.getBean(CaseReindexingService.class)
        .enqueueCasesModifiedSince(LocalDate.now().minusDays(1));

    assertThat(queued).isEqualTo(1);
    await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      JsonNode document = fetchDocument(CASE_INDEX, 9401);
      assertThat(document.path("data").path("marker").asText()).isEqualTo("reindex-notification");
      assertThat(queueSize()).isZero();
    });
  }

  private ConfigurableApplicationContext startApplication() {
    SpringApplication application = new SpringApplicationBuilder(ChaosApplication.class)
        .properties(Map.ofEntries(
            entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
            entry("spring.datasource.username", POSTGRES.getUsername()),
            entry("spring.datasource.password", POSTGRES.getPassword()),
            entry("spring.datasource.driver-class-name", POSTGRES.getDriverClassName()),
            entry("ELASTIC_SEARCH_HOSTS", elasticsearchProxyUrl()),
            entry("ccd.sdk.indexing.elasticsearch.connect-timeout-ms", "500"),
            entry("ccd.sdk.indexing.elasticsearch.socket-timeout-ms", "500"),
            entry("ccd.sdk.indexing.queue-lock-seconds", "1"),
            entry("spring.main.banner-mode", "off"),
            entry("spring.main.web-application-type", "none")
        ))
        .build();
    return application.run();
  }

  private void resetState() {
    restoreElasticsearchConnection();
    deleteIndex(CASE_INDEX);
    deleteIndex(POISON_INDEX);
    deleteIndex("global_search");
    jdbc().getJdbcTemplate().execute("truncate table ccd.case_data restart identity cascade");
  }

  private void commitCaseRevision(long caseId, long reference, String caseType, int expectedRevision, String marker) {
    String data = caseType.equals(POISON_CASE_TYPE)
        ? "{\"poison\":{\"marker\":\"" + marker + "\"}}"
        : """
          {
            "marker": "%s",
            "counter": %d,
            "expected_revision": %d
          }
          """.formatted(marker, caseId, expectedRevision);

    commitCaseRevisionWithData(caseId, reference, caseType, expectedRevision, data);
  }

  private void commitCaseRevisionWithData(long caseId, long reference, String caseType, int expectedRevision,
                                          String data) {
    transactionTemplate().executeWithoutResult(status -> {
      boolean exists = Boolean.TRUE.equals(jdbc().queryForObject(
          "select exists(select 1 from ccd.case_data where reference = :reference)",
          Map.of("reference", reference),
          Boolean.class));

      var params = new MapSqlParameterSource()
          .addValue("id", caseId)
          .addValue("reference", reference)
          .addValue("case_type", caseType)
          .addValue("data", data);

      if (exists) {
        jdbc().update(
            """
            update ccd.case_data
               set data = :data::jsonb,
                   last_modified = now() at time zone 'UTC'
             where reference = :reference
            """,
            params);
      } else {
        jdbc().update(
            """
            insert into ccd.case_data (
                id,
                reference,
                version,
                jurisdiction,
                case_type_id,
                state,
                data,
                supplementary_data,
                security_classification,
                created_date,
                last_modified,
                last_state_modified_date
            ) values (
                :id,
                :reference,
                1,
                'TEST',
                :case_type,
                'Submitted',
                :data::jsonb,
                '{}'::jsonb,
                'PUBLIC',
                now() at time zone 'UTC',
                now() at time zone 'UTC',
                now() at time zone 'UTC'
            )
            """,
            params);
      }

      long revision = jdbc().queryForObject(
          "select case_revision from ccd.case_data where reference = :reference",
          Map.of("reference", reference),
          Long.class);

      jdbc().update(
          """
          insert into ccd.case_event (
              case_data_id,
              case_type_version,
              event_id,
              summary,
              description,
              user_id,
              case_type_id,
              state_id,
              data,
              user_first_name,
              user_last_name,
              event_name,
              state_name,
              security_classification,
              version,
              case_revision,
              idempotency_key
          ) values (
              :id,
              1,
              'chaos-update',
              'Chaos update',
              'Chaos update',
              'chaos-user',
              :case_type,
              'Submitted',
              :data::jsonb,
              'Chaos',
              'User',
              'Chaos update',
              'Submitted',
              'PUBLIC',
              1,
              :revision,
              :idempotency_key
          )
          """,
          new MapSqlParameterSource()
              .addValue("id", caseId)
              .addValue("case_type", caseType)
              .addValue("data", data)
              .addValue("revision", revision)
              .addValue("idempotency_key", UUID.randomUUID()));
    });
  }

  private void awaitLatestIndexed(long firstCaseId, long lastCaseId, int expectedRevision) {
    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      for (long caseId = firstCaseId; caseId <= lastCaseId; caseId++) {
        JsonNode document = fetchDocument(CASE_INDEX, caseId);
        assertThat(document.path("case_revision").asLong()).isEqualTo(latestRevision(caseId));
        assertThat(document.path("data").path("expected_revision").asInt()).isEqualTo(expectedRevision);
      }
    });
  }

  private void awaitCohortIndexed(long firstCaseId, long lastCaseId, int expectedRevision) {
    long caseCount = lastCaseId - firstCaseId + 1;
    long expectedCounterSum = (firstCaseId + lastCaseId) * caseCount / 2;

    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      JsonNode aggregate = searchByExpectedRevision(expectedRevision);

      assertThat(aggregate.path("hits").path("total").path("value").asLong()).isEqualTo(caseCount);
      assertThat(aggregate.path("aggregations").path("counter_sum").path("value").asLong())
          .isEqualTo(expectedCounterSum);
    });
  }

  private void assertQueueEventuallyEmpty() {
    await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(queueSize()).isZero());
  }

  private void awaitElasticsearchAvailable() {
    await().pollInterval(Duration.ofMillis(500)).atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      try {
        HttpResponse<String> response = sendEsRequest("GET", "/", null);
        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException | InterruptedException ex) {
        if (ex instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw new AssertionError("Elasticsearch is not available yet", ex);
      }
    });
  }

  private long latestRevision(long caseId) {
    return jdbc().queryForObject(
        "select case_revision from ccd.case_data where id = :id",
        Map.of("id", caseId),
        Long.class);
  }

  private long latestEventId(long caseId) {
    return jdbc().queryForObject(
        "select max(id) from ccd.case_event where case_data_id = :id",
        Map.of("id", caseId),
        Long.class);
  }

  private long queueSize() {
    return jdbc().queryForObject("select count(*) from ccd.es_queue", Map.of(), Long.class);
  }

  private long deadLetterSize() {
    return jdbc().queryForObject("select count(*) from ccd.es_dead_letter_queue", Map.of(), Long.class);
  }

  private void makePoisonQueueEntriesOlderThanValidEntry() {
    jdbc().update(
        "update ccd.es_queue set enqueued_at = now() - interval '10 minutes' where reference between 2001 and 2100",
        Map.of());
    jdbc().update(
        "update ccd.es_queue set enqueued_at = now() where reference = 3001",
        Map.of());
  }

  private void createPoisonMapping() throws Exception {
    sendEsRequest(
        "PUT",
        "/" + POISON_INDEX,
        """
        {
          "mappings": {
            "properties": {
              "data": {
                "properties": {
                  "poison": {
                    "type": "keyword"
                  }
                }
              }
            }
          }
        }
        """);
  }

  private void createGlobalSearchPoisonMapping() throws Exception {
    sendEsRequest(
        "PUT",
        "/global_search",
        """
        {
          "mappings": {
            "properties": {
              "data": {
                "properties": {
                  "SearchCriteria": {
                    "type": "keyword"
                  }
                }
              }
            }
          }
        }
        """);
  }

  private JsonNode fetchDocument(String index, long caseId) throws Exception {
    HttpResponse<String> response = sendEsRequest("GET", "/" + index + "/_doc/" + caseId, null);
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body()).path("_source");
  }

  private JsonNode searchByExpectedRevision(int expectedRevision) throws Exception {
    HttpResponse<String> response = sendEsRequest(
        "POST",
        "/" + CASE_INDEX + "/_search",
        """
        {
          "size": 0,
          "track_total_hits": true,
          "query": {
            "term": {
              "data.expected_revision": %d
            }
          },
          "aggs": {
            "counter_sum": {
              "sum": {
                "field": "data.counter"
              }
            }
          }
        }
        """.formatted(expectedRevision));

    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  private void deleteIndex(String index) {
    try {
      sendEsRequest("DELETE", "/" + index, null);
    } catch (Exception ignored) {
      // Index deletion is best-effort test cleanup.
    }
  }

  private HttpResponse<String> sendEsRequest(String method, String path, String body)
      throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(URI.create("http://" + ELASTICSEARCH.getHttpHostAddress() + path))
        .timeout(Duration.ofSeconds(10))
        .method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void cutElasticsearchConnection() {
    elasticsearchProxy().setConnectionCut(true);
  }

  private void restoreElasticsearchConnection() {
    if (elasticsearchProxy != null) {
      elasticsearchProxy.setConnectionCut(false);
    }
  }

  private void unpauseElasticsearchContainer() {
    if (ELASTICSEARCH.isRunning()) {
      try {
        ELASTICSEARCH.getDockerClient()
            .unpauseContainerCmd(ELASTICSEARCH.getContainerId())
            .exec();
      } catch (RuntimeException ignored) {
        // Container may not be paused; best-effort cleanup between chaos scenarios.
      }
    }
  }

  private static String elasticsearchProxyUrl() {
    return "http://" + TOXIPROXY.getHost() + ":" + elasticsearchProxy().getProxyPort();
  }

  private static ToxiproxyContainer.ContainerProxy elasticsearchProxy() {
    if (elasticsearchProxy == null) {
      elasticsearchProxy = TOXIPROXY.getProxy(ELASTICSEARCH, 9200);
    }
    return elasticsearchProxy;
  }

  private NamedParameterJdbcTemplate jdbc() {
    return context.getBean(NamedParameterJdbcTemplate.class);
  }

  private TransactionTemplate transactionTemplate() {
    return context.getBean(TransactionTemplate.class);
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  @Import({CaseReindexingService.class, DecentralisedDataConfiguration.class, DecentralisedESIndexer.class})
  static class ChaosApplication {
  }
}
