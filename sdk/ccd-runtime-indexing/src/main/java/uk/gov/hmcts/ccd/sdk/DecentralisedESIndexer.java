package uk.gov.hmcts.ccd.sdk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Node;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.util.BinaryData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@ConditionalOnProperty(
    name = "ccd.sdk.decentralised.es-indexer.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Component
@Slf4j
class DecentralisedESIndexer implements SmartLifecycle, DisposableBean {
  private static final String NOTIFICATION_CHANNEL = "ccd_es_queue_changed";
  private static final int NOTIFICATION_RECONNECT_DELAY_MS = 1000;

  enum BulkActionOutcome {
    COMPLETE,
    DEAD_LETTER,
    RETRYABLE
  }

  private final DataSource dataSource;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ExecutorService workerExecutor;
  private final ElasticsearchTransport transport;
  private final ElasticsearchClient client;
  private final ObjectMapper mapper = new ObjectMapper();
  private final int queueLockSeconds;
  private final int batchSize;
  private final int drainDelayMs;
  private final int pollIntervalMs;
  private final String bulkTimeout;

  @Autowired
  public DecentralisedESIndexer(DataSource dataSource,
                                JdbcTemplate jdbcTemplate,
                                TransactionTemplate transactionTemplate,
                                @Value("${ELASTIC_SEARCH_HOSTS:http://localhost:9200}")
                                String elasticSearchHosts,
                                @Value("${ccd.sdk.indexing.elasticsearch.connect-timeout-ms:10000}")
                                int connectTimeoutMs,
                                @Value("${ccd.sdk.indexing.elasticsearch.socket-timeout-ms:60000}")
                                int socketTimeoutMs,
                                @Value("${ccd.sdk.indexing.queue-lock-seconds:30}")
                                int queueLockSeconds,
                                @Value("${ccd.sdk.indexing.batch-size:25}")
                                int batchSize,
                                @Value("${ccd.sdk.indexing.drain-delay-ms:100}")
                                int drainDelayMs,
                                @Value("${ccd.sdk.decentralised.poll-interval-ms:10000}")
                                int pollIntervalMs,
                                @Value("${ccd.sdk.indexing.bulk-timeout:1m}")
                                String bulkTimeout) {
    this.dataSource = dataSource;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.queueLockSeconds = queueLockSeconds;
    this.batchSize = batchSize;
    this.drainDelayMs = drainDelayMs;
    this.pollIntervalMs = pollIntervalMs;
    this.bulkTimeout = bulkTimeout;
    if (batchSize < 1) {
      throw new IllegalArgumentException("ccd.sdk.indexing.batch-size must be greater than zero");
    }
    if (drainDelayMs < 0) {
      throw new IllegalArgumentException("ccd.sdk.indexing.drain-delay-ms must not be negative");
    }
    if (pollIntervalMs < 1) {
      throw new IllegalArgumentException("ccd.sdk.decentralised.poll-interval-ms must be greater than zero");
    }
    if (bulkTimeout.isBlank()) {
      throw new IllegalArgumentException("ccd.sdk.indexing.bulk-timeout must not be blank");
    }
    this.workerExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "ccd-es-indexer"));
    var hosts = parseElasticSearchHosts(elasticSearchHosts);
    var restClient = Rest5Client.builder(hosts)
        .setRequestConfigCallback(requestConfigBuilder -> {
          requestConfigBuilder.setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs));
          requestConfigBuilder.setResponseTimeout(Timeout.ofMilliseconds(socketTimeoutMs));
        })
        .setConnectionConfigCallback(connectionConfigBuilder -> {
          connectionConfigBuilder.setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs));
          connectionConfigBuilder.setSocketTimeout(Timeout.ofMilliseconds(socketTimeoutMs));
        })
        .setFailureListener(new Rest5Client.FailureListener() {
          @Override
          public void onFailure(Node node) {
            log.warn("Decentralised ES indexer marked Elasticsearch host {} as failed", node.getHost());
          }
        })
        .build();
    this.transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper(mapper));
    this.client = new ElasticsearchClient(transport);

    log.info("Starting decentralised ES indexer targeting {}", Arrays.toString(hosts));
  }

  static URI[] parseElasticSearchHosts(String elasticSearchHosts) {
    return Arrays.stream(elasticSearchHosts.split(",", -1))
        .map(String::trim)
        .map(DecentralisedESIndexer::stripSurroundingQuotes)
        .map(DecentralisedESIndexer::parseElasticSearchHost)
        .map(host -> URI.create(host.toURI()))
        .toArray(URI[]::new);
  }

  private static HttpHost parseElasticSearchHost(String host) {
    try {
      return HttpHost.create(host);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid Elasticsearch host in ELASTIC_SEARCH_HOSTS: " + host, e);
    }
  }

  private static String stripSurroundingQuotes(String host) {
    if (host.length() >= 2 && host.startsWith("\"") && host.endsWith("\"")) {
      return host.substring(1, host.length() - 1).trim();
    }
    return host;
  }

  @Override
  public void start() {
    if (terminated.get()) {
      return;
    }
    if (running.compareAndSet(false, true)) {
      workerExecutor.submit(this::listenAndPoll);
    }
  }

  @Override
  public void stop() {
    stopWorker();
  }

  @Override
  public void stop(Runnable callback) {
    stopWorker();
    callback.run();
  }

  private void stopWorker() {
    terminated.set(true);
    workerExecutor.shutdownNow();
    try {
      long waitMs = Math.max(30_000L, pollIntervalMs + 5_000L);
      if (!workerExecutor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
        log.warn("Decentralised ES indexer worker did not stop within {}ms", waitMs);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  public void runIndexer() {
    if (terminated.get()) {
      return;
    }
    try {
      PollResult result;
      boolean continueDrain;
      do {
        result = pollForNewCases();
        continueDrain = !terminated.get() && result.claimedCases() == batchSize && !result.hasTransientFailures();
        if (continueDrain) {
          Thread.sleep(drainDelayMs);
        }
      } while (continueDrain);
    } catch (Exception ex) {
      if (terminated.get()) {
        log.debug("Decentralised ES indexer stopped after shutdown signal", ex);
      } else {
        log.error("Decentralised ES indexer failed to poll for new cases", ex);
      }
    }
  }

  private void listenAndPoll() {
    try {
      while (!terminated.get()) {
        try (Connection connection = dataSource.getConnection()) {
          connection.setAutoCommit(true);
          try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + NOTIFICATION_CHANNEL);
          }

          try {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);
            log.info("Decentralised ES indexer listening for PostgreSQL notifications on {}", NOTIFICATION_CHANNEL);

            while (!terminated.get()) {
              runIndexer();
              waitForNotification(pgConnection);
            }
          } finally {
            unlistenQuietly(connection);
          }
        } catch (Exception ex) {
          if (terminated.get()) {
            log.debug("Decentralised ES indexer notification listener stopped after shutdown signal", ex);
          } else {
            log.warn("Decentralised ES indexer notification listener failed; reconnecting", ex);
            sleepBeforeReconnect();
          }
        }
      }
    } finally {
      running.set(false);
    }
  }

  private void unlistenQuietly(Connection connection) {
    try {
      if (!connection.isClosed()) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("UNLISTEN " + NOTIFICATION_CHANNEL);
        }
      }
    } catch (SQLException ex) {
      log.debug("Failed to unlisten ES indexer notification channel", ex);
    }
  }

  private void waitForNotification(PGConnection pgConnection) throws SQLException {
    PGNotification[] notifications = pgConnection.getNotifications(pollIntervalMs);
    if (notifications != null && notifications.length > 0) {
      log.debug("Decentralised ES indexer received {} PostgreSQL notifications", notifications.length);
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(NOTIFICATION_RECONNECT_DELAY_MS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressWarnings("unchecked")
  private PollResult pollForNewCases() {
    UUID lockToken = UUID.randomUUID();
    List<Map<String, Object>> results = transactionTemplate.execute(status -> claimBatch(lockToken));

    try {
      var operations = new ArrayList<BulkOperation>();
      var operationMetadata = new ArrayList<IndexOperation>();

      for (Map<String, Object> row : results) {
        var rowJson = row.get("row").toString();
        long reference = ((Number) row.get("reference")).longValue();
        long version = ((Number) row.get("case_revision")).longValue();
        long eventId = ((Number) row.get("event_id")).longValue();
        String indexId = row.get("index_id").toString();
        String docId = row.get("case_data_id").toString();
        var claim = new IndexClaim(reference, version, lockToken);

        appendBulkIndex(operations, indexId, docId, version, rowJson);
        operationMetadata.add(new IndexOperation(claim, eventId, indexId));

        // Replicate CCD's globalsearch logstash setup.
        // Where cases define a 'SearchCriteria' field we index certain fields into CCD's central
        // 'global search' index.
        // https://github.com/hmcts/cnp-flux-config/blob/master/apps/ccd/ccd-logstash/ccd-logstash.yaml#L99-L175
        Map<String, Object> map = mapper.readValue(rowJson, Map.class);
        var data = (Map<String, Object>) map.get("data");
        if (data.containsKey("SearchCriteria")) {
          filter(data, "SearchCriteria", "caseManagementLocation", "CaseAccessCategory",
              "caseNameHmctsInternal", "caseManagementCategory");
          filter((Map<String, Object>) map.get("supplementary_data"), "HMCTSServiceId");
          map.remove("last_state_modified_date");
          map.remove("last_modified");
          map.remove("created_date");
          map.put("index_id", "global_search");

          appendBulkIndex(
              operations,
              "global_search",
              docId,
              version,
              mapper.writeValueAsString(map));
          operationMetadata.add(new IndexOperation(claim, eventId, "global_search"));
        }
      }

      if (!operations.isEmpty()) {
        IndexingResult result = executeBulkIndexing(operations, operationMetadata);
        transactionTemplate.executeWithoutResult(status -> completeIndexing(result));
        return new PollResult(results.size(), result.hasTransientFailures());
      }
      return new PollResult(results.size(), false);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<Map<String, Object>> claimBatch(UUID lockToken) {
    // Replicates the behaviour of the previous logstash configuration.
    // https://github.com/hmcts/rse-cft-lib/blob/94aa0edeb0e1a4337a411ed8e6e20f170ed30bae/cftlib/lib/runtime/compose/logstash/logstash_conf.in#L3
    return jdbcTemplate.queryForList("""
          with next_batch as (
              select reference, case_revision
              from ccd.es_queue
              where locked_until is null
                 or locked_until < now()
              order by enqueued_at
              limit ?
              for update skip locked
          ),
          claimed as (
              update ccd.es_queue q
              set locked_until = now() + (? * interval '1 second'),
                  lock_token = ?
              from next_batch nb
              where q.reference = nb.reference
                and q.case_revision = nb.case_revision
              returning q.reference, q.case_revision
          )
          select
              row_to_json(row)::jsonb as row,
              row.reference,
              row.case_revision,
              row.id as case_data_id,
              row.event_id,
              row.index_id
          from (
              select
                  cd.reference,
                  c.case_revision,
                  cd.case_type_id,
                  lower(cd.case_type_id) || '_cases' as index_id,
                  cd.created_date,
                  cd.jurisdiction,
                  cd.id as id,
                  cd.state,
                  cd.security_classification,
                  cd.last_state_modified_date,
                  cd.supplementary_data,
                  ce.id as event_id,
                  ce.data,
                  coalesce(cd.last_modified, cd.created_date) as last_modified
              from claimed c
              join ccd.case_data cd on cd.reference = c.reference
              join lateral (
                  select ce.*
                  from ccd.case_event ce
                  where ce.case_data_id = cd.id
                    and ce.case_revision <= c.case_revision
                  order by ce.case_revision desc, ce.id desc
                  limit 1
              ) ce on true
          ) row
          """, batchSize, queueLockSeconds, lockToken);
  }

  private IndexingResult executeBulkIndexing(
      List<BulkOperation> operations,
      List<IndexOperation> operationMetadata
  ) throws Exception {
    var response = client.bulk(builder -> builder
        .timeout(Time.of(time -> time.time(bulkTimeout)))
        .operations(operations));

    var claims = operationMetadata.stream()
        .map(IndexOperation::claim)
        .distinct()
        .toList();

    if (!response.errors()) {
      return new IndexingResult(claims, operationMetadata.stream()
          .map(SuccessfulIndexOperation::from)
          .distinct()
          .toList(), List.of(), false);
    }

    var transientFailureClaims = new java.util.HashSet<IndexClaim>();
    var successfulOperations = new ArrayList<SuccessfulIndexOperation>();
    var deadLetters = new ArrayList<DeadLetter>();

    for (int i = 0; i < response.items().size(); i++) {
      BulkResponseItem item = response.items().get(i);
      IndexOperation metadata = operationMetadata.get(i);
      int itemStatus = item.status();

      BulkActionOutcome outcome = classifyBulkActionStatus(itemStatus);
      switch (outcome) {
        case COMPLETE -> {
          successfulOperations.add(SuccessfulIndexOperation.from(metadata));
          if (itemStatus == 409) {
            // This is a stale event. The version in ES is already higher.
            // The queued revision can be completed because a newer version already exists in Elasticsearch.
            log.info("Stale event processing skipped due to version conflict for id {}: {}",
                item.id(), extractErrorMessage(item.error()));
          }
        }
        case DEAD_LETTER -> {
          deadLetters.add(new DeadLetter(
              metadata.eventId(),
              metadata.claim().caseRevision(),
              metadata.indexId(),
              "%s: %s".formatted(metadata.indexId(), extractErrorMessage(item.error()))
          ));
          log.error("Cftlib elasticsearch indexing document rejected for id {}: {}",
              item.id(), extractErrorMessage(item.error()));
        }
        case RETRYABLE -> {
          transientFailureClaims.add(metadata.claim());
          log.warn("Cftlib elasticsearch indexing transient failure for id {}: {}",
              item.id(), extractErrorMessage(item.error()));
        }
        default -> throw new IllegalStateException("Unhandled bulk action outcome: " + outcome);
      }
    }

    var completedClaims = claims.stream()
        .filter(claim -> !transientFailureClaims.contains(claim))
        .toList();

    return new IndexingResult(
        completedClaims,
        successfulOperations.stream().distinct().toList(),
        deadLetters,
        !transientFailureClaims.isEmpty());
  }

  private void completeIndexing(IndexingResult result) {
    if (!result.deadLetters().isEmpty()) {
      var params = result.deadLetters().stream()
          .map(deadLetter -> new Object[] {
              deadLetter.caseEventId(),
              deadLetter.caseRevision(),
              deadLetter.indexId(),
              deadLetter.failureMessage()
          })
          .toList();

      jdbcTemplate.batchUpdate("""
          insert into ccd.es_dead_letter_queue(case_event_id, case_revision, index_id, failure_message)
          values (?, ?, ?, ?)
          on conflict (case_event_id, case_revision, index_id) do update
          set timestamp = now(),
              failure_message = excluded.failure_message
          """, params);
    }

    if (!result.successfulOperations().isEmpty()) {
      var params = result.successfulOperations().stream()
          .map(success -> new Object[] {
              success.reference(),
              success.indexId(),
              success.caseRevision()
          })
          .toList();

      jdbcTemplate.batchUpdate("""
          delete from ccd.es_dead_letter_queue dlq
          using ccd.case_event ce
          join ccd.case_data cd on cd.id = ce.case_data_id
          where dlq.case_event_id = ce.id
            and cd.reference = ?
            and dlq.index_id = ?
            and dlq.case_revision <= ?
          """, params);
    }

    if (!result.completedClaims().isEmpty()) {
      var params = result.completedClaims().stream()
          .map(claim -> new Object[] {
              claim.reference(),
              claim.caseRevision(),
              claim.lockToken()
          })
          .toList();

      jdbcTemplate.batchUpdate("""
          merge into ccd.es_queue q
          using (
              values (?::bigint, ?::bigint, ?::uuid)
          ) as completed(reference, case_revision, lock_token)
             on q.reference = completed.reference
             and q.lock_token = completed.lock_token
          when matched and q.case_revision = completed.case_revision then
              delete
          when matched then
              update set
                  locked_until = null,
                  lock_token = null
          """, params);
    }
  }

  static BulkActionOutcome classifyBulkActionStatus(int status) {
    if (status >= 200 && status < 300 || status == 409) {
      return BulkActionOutcome.COMPLETE;
    }
    if (status == 400 || status == 404) {
      return BulkActionOutcome.DEAD_LETTER;
    }
    return BulkActionOutcome.RETRYABLE;
  }

  private void appendBulkIndex(
      List<BulkOperation> operations,
      String index,
      String id,
      long version,
      String source) {
    operations.add(BulkOperation.of(operation -> operation.index(indexOperation ->
        indexOperation
            .index(index)
            .id(id)
            .version(version)
            .versionType(VersionType.ExternalGte)
            .document(BinaryData.of(source.getBytes(StandardCharsets.UTF_8), "application/json")))));
  }

  private String extractErrorMessage(ErrorCause error) {
    if (error == null) {
      return null;
    }
    return error.reason() != null ? error.reason() : error.toString();
  }

  public void filter(Map<String, Object> map, String... forKeys) {
    if (null != map) {
      var keyset = Set.of(forKeys);
      map.keySet().removeIf(k -> !keyset.contains(k));
    }
  }

  @Override
  public void destroy() throws Exception {
    stopWorker();
    transport.close();
  }

  private record IndexingResult(
      List<IndexClaim> completedClaims,
      List<SuccessfulIndexOperation> successfulOperations,
      List<DeadLetter> deadLetters,
      boolean hasTransientFailures
  ) {}

  private record IndexClaim(
      long reference,
      long caseRevision,
      UUID lockToken
  ) {}

  private record IndexOperation(
      IndexClaim claim,
      long eventId,
      String indexId
  ) {}

  private record SuccessfulIndexOperation(
      long reference,
      long caseRevision,
      String indexId
  ) {
    static SuccessfulIndexOperation from(IndexOperation operation) {
      return new SuccessfulIndexOperation(
          operation.claim().reference(),
          operation.claim().caseRevision(),
          operation.indexId());
    }
  }

  private record DeadLetter(
      long caseEventId,
      long caseRevision,
      String indexId,
      String failureMessage
  ) {}

  private record PollResult(
      int claimedCases,
      boolean hasTransientFailures
  ) {}
}
