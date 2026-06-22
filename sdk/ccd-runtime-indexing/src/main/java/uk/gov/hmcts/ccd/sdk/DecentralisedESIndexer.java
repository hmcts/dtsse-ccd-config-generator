package uk.gov.hmcts.ccd.sdk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final ElasticsearchTransport transport;
  private final ElasticsearchClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired
  public DecentralisedESIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                @Value("${ELASTIC_SEARCH_HOSTS:http://localhost:9200}")
                                String elasticSearchHosts,
                                @Value("${ccd.sdk.indexing.elasticsearch.connect-timeout-ms:10000}")
                                int connectTimeoutMs,
                                @Value("${ccd.sdk.indexing.elasticsearch.socket-timeout-ms:60000}")
                                int socketTimeoutMs) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
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

  @Scheduled(fixedDelayString = "${ccd.sdk.decentralised.poll-interval-ms:250}")
  public void runIndexer() {
    if (terminated.get()) {
      return;
    }
    try {
      pollForNewCases();
    } catch (Exception ex) {
      if (terminated.get()) {
        log.debug("Decentralised ES indexer stopped after shutdown signal", ex);
      } else {
        log.error("Decentralised ES indexer failed to poll for new cases", ex);
      }
    }
  }

  private void pollForNewCases() {
    transactionTemplate.execute(status -> {
      // Replicates the behaviour of the previous logstash configuration.
      // https://github.com/hmcts/rse-cft-lib/blob/94aa0edeb0e1a4337a411ed8e6e20f170ed30bae/cftlib/lib/runtime/compose/logstash/logstash_conf.in#L3
      var results = jdbcTemplate.queryForList("""
          with next_batch as (
              select reference, case_revision
              from ccd.es_queue
              order by enqueued_at
              limit 100
              for update skip locked
          ),
          deleted as (
              delete from ccd.es_queue q
              using next_batch nb
              where q.reference = nb.reference
                and q.case_revision = nb.case_revision
              returning q.reference, q.case_revision
          )
          select
              row_to_json(row)::jsonb as row,
              row.reference,
              row.case_revision,
              row.case_data_id,
              row.index_id
          from (
              select
                  cd.reference,
                  cd.case_revision,
                  cd.case_type_id,
                  lower(cd.case_type_id) || '_cases' as index_id,
                  cd.created_date,
                  cd.jurisdiction,
                  cd.id as case_data_id,
                  cd.state,
                  cd.security_classification,
                  cd.last_state_modified_date,
                  cd.supplementary_data,
                  ce.id as event_id,
                  ce.data,
                  ce.created_date as last_modified
              from deleted d
              join ccd.case_data cd on cd.reference = d.reference
              join lateral (
                  select ce.*
                  from ccd.case_event ce
                  where ce.case_data_id = cd.id
                  order by ce.id desc
                  limit 1
              ) ce on true
              where cd.case_revision = d.case_revision
          ) row
          """);

      try {
        var operations = new ArrayList<BulkOperation>();

        for (Map<String, Object> row : results) {
          var rowJson = row.get("row").toString();
          long version = ((Number) row.get("case_revision")).longValue();
          String indexId = row.get("index_id").toString();
          String docId = row.get("case_data_id").toString();

          appendBulkIndex(operations, indexId, docId, version, rowJson);

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
          }
        }

        if (!operations.isEmpty() && executeBulkIndexingAndCheckForRollback(operations)) {
          // Rollback the transaction only if we encountered a non-version-conflict error.
          log.error("**** Cftlib elasticsearch indexing has genuine failures. Rolling back transaction.");
          status.setRollbackOnly();
          return false;
        }
        return true;  // Transaction success
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  @SuppressWarnings("unchecked")
  private boolean executeBulkIndexingAndCheckForRollback(
      java.util.List<BulkOperation> operations) throws Exception {
    var response = client.bulk(builder -> builder
        .timeout(Time.of(time -> time.time("1m")))
        .operations(operations));

    if (!response.errors()) {
      return false;
    }

    boolean hasGenuineFailure = false;
    for (var item : response.items()) {
      if (item.status() == 409) {
        // This is a stale event. The version in ES is already higher.
        // We can safely ignore this and let the transaction commit,
        // which will remove the item from the es_queue.
        log.info("Stale event processing skipped due to version conflict for id {}: {}",
            item.id(), extractErrorMessage(item.error()));
      } else if (item.status() >= 400) {
        hasGenuineFailure = true;
        log.error("Cftlib elasticsearch indexing error for id {}: {}",
            item.id(), extractErrorMessage(item.error()));
      }
    }

    return hasGenuineFailure;
  }

  private void appendBulkIndex(
      java.util.List<BulkOperation> operations,
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
    terminated.set(true);
    transport.close();
  }
}
