package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class DecentralisedESIndexer implements DisposableBean {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private volatile boolean terminated;
  private final Thread t;
  private final RestHighLevelClient client;

  @SneakyThrows
  @Autowired
  public DecentralisedESIndexer(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.client = new RestHighLevelClient(RestClient.builder(
        new HttpHost("localhost", 9200)));

    this.t = new Thread(this::index);
    t.setDaemon(true);
    t.setUncaughtExceptionHandler(this::failFast);
    t.setName("****Decentralised ElasticSearch indexer");
    t.start();
  }

  private void failFast(Thread thread, Throwable exception) {
    exception.printStackTrace();
    System.out.println("***  Decentralised ES indexer terminated with an unhandled exception ***");
    System.out.println("For further support visit https://moj.enterprise.slack.com/archives/C033F1GDD6Z");
    Runtime.getRuntime().halt(-1);
  }

  @SneakyThrows
  private void index() {
    while (!terminated) {
      pollForNewCases();
      Thread.sleep(250);
    }
  }

  private void pollForNewCases() {
    transactionTemplate.execute(status -> {
      // Replicates the behaviour of the previous logstash configuration.
      // https://github.com/hmcts/rse-cft-lib/blob/94aa0edeb0e1a4337a411ed8e6e20f170ed30bae/cftlib/lib/runtime/compose/logstash/logstash_conf.in#L3
      var results = jdbcTemplate.queryForList("""
          with updated as (
            delete from ccd.es_queue es where id in (select id from ccd.es_queue limit 2000)
            returning id
          )
            select reference as id, case_type_id, index_id, row_to_json(row)::jsonb as row
            from (
              select
                now() as "@timestamp",
                version::text as "@version",
                cd.case_type_id,
                cd.created_date,
                ce.data,
                jurisdiction,
                cd.reference,
                ce.created_date as last_modified,
                last_state_modified_date,
                supplementary_data,
                lower(cd.case_type_id) || '_cases' as index_id,
                cd.state,
                cd.security_classification
             from updated
              join ccd.case_event ce using(id)
              join ccd.case_data cd on cd.reference = ce.case_reference
          ) row
          """);

      try {
        BulkRequest request = new BulkRequest();
        ObjectMapper mapper = new ObjectMapper();

        for (Map<String, Object> row : results) {
          var rowJson = row.get("row").toString();
          request.add(new IndexRequest(row.get("index_id").toString())
              .id(row.get("id").toString())
              .source(rowJson, XContentType.JSON));

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
                .id(row.get("id").toString())
                .source(mapper.writeValueAsString(map), XContentType.JSON));
          }
        }

        if (request.numberOfActions() > 0) {
          var r = client.bulk(request, RequestOptions.DEFAULT);
          if (r.hasFailures()) {
            // TODO: Track the failed cases in our database, monitoring, retries etc.
            status.setRollbackOnly();
            log.error("**** Cftlib elasticsearch indexing error(s): ");
            r.buildFailureMessage();
            for (BulkItemResponse item : r.getItems()) {
              log.error(item.getFailureMessage());
            }
            return false;
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
  public void destroy() throws InterruptedException {
    this.terminated = true;
    // Wait for indexing to stop before allowing shutdown to continue.
    this.t.join();
  }
}

