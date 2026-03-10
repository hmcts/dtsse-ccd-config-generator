package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedDataConfiguration;

@SpringBootTest(classes = IdempotentReplayIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class IdempotentReplayIntegrationTest {

  private static final long CASE_REFERENCE = 9999000000000000L;
  private static final long CASE_ID = 123L;
  private static final long CASE_ID_A = 456L;
  private static final long CASE_ID_B = 789L;
  private static final long CASE_REFERENCE_A = 1111000000000000L;
  private static final long CASE_REFERENCE_B = 2222000000000000L;
  private static final long UPSERT_CASE_REFERENCE = 3333000000000000L;

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

  @Test
  void getCasesReturnsStableOrderByReferenceAscending() {
    seedCaseData(CASE_ID_A, CASE_REFERENCE_A, 1, 1);
    seedCaseData(CASE_ID_B, CASE_REFERENCE_B, 1, 1);

    var cases = repository.getCases(java.util.List.of(CASE_REFERENCE_B, CASE_REFERENCE_A));

    assertThat(cases)
        .extracting(details -> details.getCaseDetails().getReference())
        .containsExactly(CASE_REFERENCE_A, CASE_REFERENCE_B);
  }

  @Test
  void upsertCaseEnforcesHmctsServiceIdInSupplementaryData() {
    repository.upsertCase(buildEvent(UPSERT_CASE_REFERENCE, "TestCase"), Optional.empty());

    String hmctsServiceId = jdbc.queryForObject(
        "select supplementary_data->>'HMCTSServiceId' from ccd.case_data where reference = :ref",
        Map.of("ref", UPSERT_CASE_REFERENCE),
        String.class
    );

    assertThat(hmctsServiceId).isEqualTo("ABA1");
  }

  @Test
  void upsertCaseLeavesSupplementaryDataEmptyWhenCaseTypeHasNoConfiguredServiceId() {
    long caseRef = UPSERT_CASE_REFERENCE + 1;
    repository.upsertCase(buildEvent(caseRef, "UnknownCaseType"), Optional.empty());

    String supplementaryData = jdbc.queryForObject(
        "select supplementary_data::text from ccd.case_data where reference = :ref",
        Map.of("ref", caseRef),
        String.class
    );

    assertThat(supplementaryData).isEqualTo("{}");
  }

  @Test
  void upsertCasePreservesUnrelatedSupplementaryDataKeys() {
    long caseRef = UPSERT_CASE_REFERENCE + 2;
    seedCaseData(caseRef, caseRef, 1, 1);

    jdbc.update(
        "update ccd.case_data set supplementary_data = :supplementary::jsonb where reference = :ref",
        Map.of(
            "supplementary", "{\"foo\":\"bar\",\"existingNumber\":42}",
            "ref", caseRef
        )
    );

    repository.upsertCase(buildEvent(caseRef, "TestCase"), Optional.empty());

    Map<String, Object> result = jdbc.queryForMap(
        """
            select supplementary_data->>'foo' as foo,
                   supplementary_data->>'existingNumber' as existing_number,
                   supplementary_data->>'HMCTSServiceId' as hmcts_service_id
            from ccd.case_data
            where reference = :ref
            """,
        Map.of("ref", caseRef)
    );

    assertThat(result.get("foo")).isEqualTo("bar");
    assertThat(result.get("existing_number")).isEqualTo("42");
    assertThat(result.get("hmcts_service_id")).isEqualTo("ABA1");
  }
  private void seedCaseData(int version, long revision) {
    seedCaseData(CASE_ID, CASE_REFERENCE, version, revision);
  }

  private DecentralisedCaseEvent buildEvent(long caseReference, String caseType) {
    var caseDetails = new CaseDetails();
    caseDetails.setReference(caseReference);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId(caseType);
    caseDetails.setState("Submitted");
    caseDetails.setVersion(1);
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);

    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails)
        .internalCaseId(caseReference)
        .build();
  }
  private void seedCaseData(long caseId, long caseReference, int version, long revision) {
    var params = new MapSqlParameterSource()
        .addValue("id", caseId)
        .addValue("reference", caseReference)
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

    @Bean
    ResolvedConfigRegistry resolvedConfigRegistry() {
      var resolved = new ResolvedCCDConfig<>(
          Object.class,
          TestState.class,
          TestRole.class,
          Map.of(),
          ImmutableSet.copyOf(TestState.values())
      ) {
        @Override
        public String getCaseType() {
          return "TestCase";
        }

        @Override
        public String getHmctsServiceId() {
          return "ABA1";
        }
      };

      return new ResolvedConfigRegistry(List.of(resolved));
    }
  }

  private enum TestState {
    Submitted
  }

  private enum TestRole implements HasRole {
    CASEWORKER;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
