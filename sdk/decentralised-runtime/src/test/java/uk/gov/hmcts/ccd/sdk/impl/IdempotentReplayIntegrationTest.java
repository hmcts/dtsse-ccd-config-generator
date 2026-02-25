package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;

@SpringBootTest(classes = IdempotentReplayIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class IdempotentReplayIntegrationTest {

  private static final long CASE_REFERENCE = 9999000000000000L;
  private static final long CASE_ID = 123L;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @Autowired
  private CaseDataRepository repository;

  @Test
  void idempotentReplayReturnsEventVersionAndRevision() {
    seedCaseData(3, 5);
    long eventId = insertEvent(1, 1, UUID.randomUUID());

    DecentralisedCaseDetails replayed = repository.caseDetailsAtEvent(CASE_REFERENCE, eventId);

    assertThat(replayed.getCaseDetails().getVersion()).isEqualTo(1);
    assertThat(replayed.getCaseDetails().getRevision()).isEqualTo(1L);

    DecentralisedCaseDetails latest = repository.getCase(CASE_REFERENCE);
    assertThat(latest.getCaseDetails().getVersion()).isEqualTo(3);
    assertThat(latest.getCaseDetails().getRevision()).isEqualTo(5L);
  }

  @Test
  void getCasesReturnsEmptyListWhenNoReferencesProvided() {
    assertThat(repository.getCases(List.of())).isEmpty();
  }

  private void seedCaseData(int version, long revision) {
    var params = new MapSqlParameterSource()
        .addValue("id", CASE_ID)
        .addValue("reference", CASE_REFERENCE)
        .addValue("version", version)
        .addValue("revision", revision);

    jdbc.update(
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
          case_revision,
          created_date,
          last_modified,
          last_state_modified_date
        ) values (
          :id,
          :reference,
          :version,
          'TEST',
          'TestCase',
          'Submitted',
          '{}'::jsonb,
          '{}'::jsonb,
          'PUBLIC',
          :revision,
          now(),
          now(),
          now()
        )
        """,
        params
    );
  }

  private long insertEvent(int version, long revision, UUID idempotencyKey) {
    var params = new MapSqlParameterSource()
        .addValue("case_data_id", CASE_ID)
        .addValue("case_type_version", 1)
        .addValue("event_id", "ev1")
        .addValue("summary", "summary")
        .addValue("description", "description")
        .addValue("user_id", "user-1")
        .addValue("case_type_id", "TestCase")
        .addValue("state_id", "Historical")
        .addValue("data", "{}")
        .addValue("user_first_name", "Test")
        .addValue("user_last_name", "User")
        .addValue("event_name", "Event One")
        .addValue("state_name", "Historical")
        .addValue("security_classification", "PUBLIC")
        .addValue("version", version)
        .addValue("case_revision", revision)
        .addValue("idempotency_key", idempotencyKey);

    return jdbc.queryForObject(
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
          :case_data_id,
          :case_type_version,
          :event_id,
          :summary,
          :description,
          :user_id,
          :case_type_id,
          :state_id,
          :data::jsonb,
          :user_first_name,
          :user_last_name,
          :event_name,
          :state_name,
          :security_classification::ccd.securityclassification,
          :version,
          :case_revision,
          :idempotency_key
        )
        returning id
        """,
        params,
        Long.class
    );
  }

  @Configuration
  @Import({CaseDataRepository.class, DecentralisedDataConfiguration.class})
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
