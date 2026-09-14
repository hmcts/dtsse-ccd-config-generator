package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = CaseDataRepositoryIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class CaseDataRepositoryIntegrationTest {

  private static final long CASE_ID = 9876L;
  private static final long CASE_REFERENCE = 9999000000009876L;

  @Autowired
  private CaseDataRepository repository;

  @Autowired
  private NamedParameterJdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from ccd.case_event where case_data_id = :caseId", Map.of("caseId", CASE_ID));
    jdbc.update("delete from ccd.case_data where id = :caseId", Map.of("caseId", CASE_ID));
    seedCaseData();
    seedCommittedEvent();
  }

  @Test
  void logsIncomingAndCommittedEventsWhenOptimisticUpdateConflicts(CapturedOutput output) {
    assertThatThrownBy(() -> repository.upsertCase(incomingEvent(), Optional.empty()))
        .isInstanceOf(EmptyResultDataAccessException.class);

    assertThat(output)
        .contains("Rejecting event incoming-event for case " + CASE_REFERENCE + " due to concurrent update")
        .contains("submittedVersion=1")
        .contains("conflictingEvent=committed-event")
        .contains("conflictingEventRevision=2");
  }

  private void seedCaseData() {
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
          2,
          'TEST',
          'TestCase',
          'Submitted',
          '{}'::jsonb,
          '{}'::jsonb,
          'PUBLIC',
          2,
          now(),
          now(),
          now()
        )
        """,
        Map.of("id", CASE_ID, "reference", CASE_REFERENCE)
    );
  }

  private void seedCommittedEvent() {
    jdbc.update(
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
          :caseId,
          1,
          'committed-event',
          'summary',
          'description',
          'user',
          'TestCase',
          'Submitted',
          '{}'::jsonb,
          'Test',
          'User',
          'Committed event',
          'Submitted',
          'PUBLIC'::ccd.securityclassification,
          2,
          2,
          :idempotencyKey
        )
        """,
        Map.of("caseId", CASE_ID, "idempotencyKey", UUID.randomUUID())
    );
  }

  private DecentralisedCaseEvent incomingEvent() {
    var caseDetails = new CaseDetails();
    caseDetails.setReference(CASE_REFERENCE);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId("TestCase");
    caseDetails.setState("Submitted");
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);
    caseDetails.setVersion(1);

    return DecentralisedCaseEvent.builder()
        .internalCaseId(CASE_ID)
        .caseDetails(caseDetails)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("incoming-event")
            .build())
        .build();
  }

  @Configuration
  @Import(CaseDataRepository.class)
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ResolvedConfigRegistry resolvedConfigRegistry() {
      return new ResolvedConfigRegistry(List.of());
    }
  }
}
