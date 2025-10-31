package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.xcontent.XContentType;
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
  private final RestHighLevelClient client;

  @Autowired
  public DecentralisedESIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                @Value("${ELASTIC_SEARCH_HOSTS:http://localhost:9200}")
                                String elasticSearchHost) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.client = new RestHighLevelClient(RestClient.builder(
        HttpHost.create(elasticSearchHost)));

    log.info("Starting decentralised ES indexer targeting {}", elasticSearchHost);
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
              limit 1000
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
        BulkRequest request = new BulkRequest();
        ObjectMapper mapper = new ObjectMapper();

        for (Map<String, Object> row : results) {
          var rowJson = row.get("row").toString();
          long version = ((Number) row.get("case_revision")).longValue();
          String indexId = row.get("index_id").toString();
          String docId = row.get("case_data_id").toString();

          request.add(new IndexRequest(indexId)
              .id(docId)
              .source(rowJson, XContentType.JSON)
              .versionType(VersionType.EXTERNAL)
              .version(version));

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

            request.add(new IndexRequest("global_search")
                .id(docId)
                .source(mapper.writeValueAsString(map), XContentType.JSON)
                .versionType(VersionType.EXTERNAL)
                .version(version));
          }
        }

        if (request.numberOfActions() > 0) {
          var r = client.bulk(request, RequestOptions.DEFAULT);
          if (r.hasFailures()) {
            boolean hasGenuineFailure = false;
            for (BulkItemResponse item : r.getItems()) {
              if (item.isFailed()) {
                BulkItemResponse.Failure failure = item.getFailure();
                if (409 == failure.getStatus().getStatus()) {
                  // This is a stale event. The version in ES is already higher.
                  // We can safely ignore this and let the transaction commit,
                  // which will remove the item from the es_queue.
                  log.info("Stale event processing skipped due to version conflict for id {}: {}",
                      failure.getId(), failure.getMessage());
                } else {
                  hasGenuineFailure = true;
                  log.error("Cftlib elasticsearch indexing error for id {}: {}",
                      failure.getId(), failure.getMessage());
                }
              }
            }

            if (hasGenuineFailure) {
              // Rollback the transaction only if we encountered a non-version-conflict error.
              log.error("**** Cftlib elasticsearch indexing has genuine failures. Rolling back transaction.");
              status.setRollbackOnly();
              return false;
            }
          }
        }
        return true;  // Transaction success
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
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
    client.close();
  }
}
