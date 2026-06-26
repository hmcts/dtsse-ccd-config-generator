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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@ConditionalOnProperty(
    name = "ccd.sdk.decentralised",
    havingValue = "true",
    matchIfMissing = true)
@Component
@Slf4j
class DecentralisedESIndexer implements DisposableBean {
  private static final String SCHEDULER_BEAN_NAME = "ccdEsIndexerScheduler";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final ElasticsearchTransport transport;
  private final ElasticsearchClient client;
  private final ObjectMapper mapper = new ObjectMapper();
  private final int queueLockSeconds;
  private final int batchSize;
  private final int drainDelayMs;

  @Autowired
  public DecentralisedESIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
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
                                int drainDelayMs) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.queueLockSeconds = queueLockSeconds;
    this.batchSize = batchSize;
    this.drainDelayMs = drainDelayMs;
    if (batchSize < 1) {
      throw new IllegalArgumentException("ccd.sdk.indexing.batch-size must be greater than zero");
    }
    if (drainDelayMs < 0) {
      throw new IllegalArgumentException("ccd.sdk.indexing.drain-delay-ms must not be negative");
    }
    var hosts = Arrays.stream(elasticSearchHosts.split(",")).map(String::trim).map(URI::create).toArray(URI[]::new);
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

  @Bean(name = SCHEDULER_BEAN_NAME, destroyMethod = "shutdown", defaultCandidate = false)
  @ConditionalOnMissingBean(name = SCHEDULER_BEAN_NAME)
  ScheduledExecutorService ccdEsIndexerScheduler() {
    return Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "ccd-es-indexer"));
  }

  @Scheduled(
      fixedDelayString = "${ccd.sdk.decentralised.poll-interval-ms:250}",
      scheduler = SCHEDULER_BEAN_NAME)
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

  @SuppressWarnings("unchecked")
  private PollResult pollForNewCases() {
    UUID lockToken = UUID.randomUUID();
    List<Map<String, Object>> results = transactionTemplate.execute(status -> claimBatch(lockToken));

    try {
      var operations = new ArrayList<BulkOperation>();
      var operationMetadata = new ArrayList<Map<String, Object>>();

      for (Map<String, Object> row : results) {
        var rowJson = row.get("row").toString();
        long reference = ((Number) row.get("reference")).longValue();
        long version = ((Number) row.get("case_revision")).longValue();
        long eventId = ((Number) row.get("event_id")).longValue();
        String indexId = row.get("index_id").toString();
        String docId = row.get("case_data_id").toString();

        appendBulkIndex(operations, indexId, docId, version, rowJson);
        operationMetadata.add(metadata(reference, version, lockToken, eventId, indexId));

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
          operationMetadata.add(metadata(reference, version, lockToken, eventId, "global_search"));
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
      List<Map<String, Object>> operationMetadata
  ) throws Exception {
    var response = client.bulk(builder -> builder
        .timeout(Time.of(time -> time.time("1m")))
        .operations(operations));

    Map<String, Map<String, Object>> claimsByKey = new HashMap<>();
    operationMetadata.forEach(metadata -> claimsByKey.putIfAbsent(claimKey(metadata), metadata));

    if (!response.errors()) {
      return new IndexingResult(new ArrayList<>(claimsByKey.values()), List.of(), false);
    }

    var transientFailureKeys = new java.util.HashSet<String>();
    var deadLetters = new ArrayList<Map<String, Object>>();

    for (int i = 0; i < response.items().size(); i++) {
      BulkResponseItem item = response.items().get(i);
      Map<String, Object> metadata = operationMetadata.get(i);
      int itemStatus = item.status();

      if (itemStatus >= 200 && itemStatus < 300) {
        continue;
      }

      if (itemStatus == 409) {
        // This is a stale event. The version in ES is already higher.
        // The queued revision can be completed because a newer version already exists in Elasticsearch.
        log.info("Stale event processing skipped due to version conflict for id {}: {}",
            item.id(), extractErrorMessage(item.error()));
      } else if (isDocumentRejection(itemStatus, item.error())) {
        deadLetters.add(Map.of(
            "case_event_id", metadata.get("event_id"),
            "case_revision", metadata.get("case_revision"),
            "index_id", metadata.get("index_id"),
            "failure_message", "%s: %s".formatted(metadata.get("index_id"), extractErrorMessage(item.error()))
        ));
        log.error("Cftlib elasticsearch indexing document rejected for id {}: {}",
            item.id(), extractErrorMessage(item.error()));
      } else {
        transientFailureKeys.add(claimKey(metadata));
        log.warn("Cftlib elasticsearch indexing transient failure for id {}: {}",
            item.id(), extractErrorMessage(item.error()));
      }
    }

    var completedClaims = claimsByKey.entrySet().stream()
        .filter(entry -> !transientFailureKeys.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .toList();

    return new IndexingResult(completedClaims, deadLetters, !transientFailureKeys.isEmpty());
  }

  private void completeIndexing(IndexingResult result) {
    if (!result.deadLetters().isEmpty()) {
      var params = result.deadLetters().stream()
          .map(deadLetter -> new Object[] {
              deadLetter.get("case_event_id"),
              deadLetter.get("case_revision"),
              deadLetter.get("index_id"),
              deadLetter.get("failure_message")
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

    if (!result.completedClaims().isEmpty()) {
      var params = result.completedClaims().stream()
          .map(claim -> new Object[] {
              claim.get("reference"),
              claim.get("case_revision"),
              claim.get("lock_token")
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

  private boolean isDocumentRejection(int status, ErrorCause error) {
    if (status == 408 || status == 409 || status == 429 || status == 401 || status == 403 || status >= 500) {
      return false;
    }

    if (status == 400 || status == 413) {
      return true;
    }

    String type = error == null ? null : error.type();
    return Set.of(
        "document_parsing_exception",
        "mapper_parsing_exception",
        "strict_dynamic_mapping_exception"
    ).contains(type);
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

  private Map<String, Object> metadata(long reference, long caseRevision, UUID lockToken, long eventId,
                                       String indexId) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("reference", reference);
    metadata.put("case_revision", caseRevision);
    metadata.put("lock_token", lockToken);
    metadata.put("event_id", eventId);
    metadata.put("index_id", indexId);
    return metadata;
  }

  private String claimKey(Map<String, Object> metadata) {
    return "%s:%s:%s".formatted(
        metadata.get("reference"),
        metadata.get("case_revision"),
        metadata.get("lock_token"));
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
    terminated.set(true);
    transport.close();
  }

  private record IndexingResult(
      List<Map<String, Object>> completedClaims,
      List<Map<String, Object>> deadLetters,
      boolean hasTransientFailures
  ) {}

  private record PollResult(
      int claimedCases,
      boolean hasTransientFailures
  ) {}
}
