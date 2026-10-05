package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.runtime.CcdCallbackExecutor;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@SpringBootTest(classes = LegacyCallbackSubmissionIntegrationTest.TestConfig.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class LegacyCallbackSubmissionIntegrationTest {

  private static final long CASE_ID = 42L;
  private static final long CASE_REFERENCE = 1_000_000_000_000_042L;
  private static final String STORED_DATA = """
      {"applicantName": "Jane", "reason": "unchanged"}
      """;
  private static final TypeReference<Map<String, JsonNode>> JSON_NODE_MAP = new TypeReference<>() {};

  @Autowired
  private CaseSubmissionService submissionService;

  @Autowired
  private TestCaseConfig caseConfig;

  @Autowired
  private JdbcTemplate jdbc;

  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    jdbc.execute("truncate table ccd.case_data cascade");
    jdbc.update(
        """
        insert into ccd.case_data (
          id, reference, version, jurisdiction, case_type_id, state, data, supplementary_data,
          security_classification, case_revision, created_date, last_modified, last_state_modified_date
        ) values (?, ?, 1, 'TEST', 'TestCase', 'Open', ?::jsonb, '{}'::jsonb, 'PUBLIC', 1, now(), now(), now())
        """,
        CASE_ID, CASE_REFERENCE, STORED_DATA
    );
  }

  @Test
  void keepsCaseDataWhenCallbackReturnsOnlyAState() throws Exception {
    caseConfig.response = AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .state(State.Closed)
        .build();

    submit();

    assertThat(storedCase()).containsEntry("state", "Closed");
    assertThat(mapper.readTree((String) storedCase().get("data"))).isEqualTo(mapper.readTree(STORED_DATA));
    assertThat(auditEvent()).containsEntry("state_id", "Closed");
    assertThat(mapper.readTree((String) auditEvent().get("data"))).isEqualTo(mapper.readTree(STORED_DATA));
  }

  @Test
  void keepsCaseDataWhenCallbackReturnsOnlyEventMetadata() throws Exception {
    caseConfig.response = AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .eventMetadata(EventMetadata.builder().summary("Callback summary").build())
        .build();

    submit();

    assertThat(storedCase()).containsEntry("state", "Open");
    assertThat(mapper.readTree((String) storedCase().get("data"))).isEqualTo(mapper.readTree(STORED_DATA));
    assertThat(auditEvent()).containsEntry("summary", "Callback summary");
    assertThat(mapper.readTree((String) auditEvent().get("data"))).isEqualTo(mapper.readTree(STORED_DATA));
  }

  @Test
  void replacesCaseDataWhenCallbackReturnsEmptyData() throws Exception {
    caseConfig.response = AboutToStartOrSubmitResponse.<CaseData, State>builder()
        .data(new CaseData())
        .build();

    submit();

    assertThat(mapper.readTree((String) storedCase().get("data"))).isEqualTo(mapper.createObjectNode());
    assertThat(mapper.readTree((String) auditEvent().get("data"))).isEqualTo(mapper.createObjectNode());
  }

  private void submit() throws Exception {
    var response = submissionService.submit(event(), "Bearer token", UUID.randomUUID());
    assertThat(response.getErrors()).isNullOrEmpty();
  }

  private Map<String, Object> storedCase() {
    return jdbc.queryForMap("select state, data::text as data from ccd.case_data where reference = ?",
        CASE_REFERENCE);
  }

  private Map<String, Object> auditEvent() {
    return jdbc.queryForMap("select state_id, summary, data::text as data from ccd.case_event where case_data_id = ?",
        CASE_ID);
  }

  private DecentralisedCaseEvent event() throws Exception {
    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails())
        .caseDetailsBefore(caseDetails())
        .internalCaseId(CASE_ID)
        .startRevision(1L)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("update")
            .eventName("Update")
            .build())
        .build();
  }

  private CaseDetails caseDetails() throws Exception {
    var details = new CaseDetails();
    details.setReference(CASE_REFERENCE);
    details.setJurisdiction("TEST");
    details.setCaseTypeId("TestCase");
    details.setState("Open");
    details.setVersion(1);
    details.setSecurityClassification(SecurityClassification.PUBLIC);
    details.setData(mapper.convertValue(mapper.readTree(STORED_DATA), JSON_NODE_MAP));
    return details;
  }

  @Configuration
  @Import({
      CaseSubmissionService.class,
      DecentralisedSubmissionHandler.class,
      LegacyCallbackSubmissionHandler.class,
      CaseEventTransactionCoordinator.class,
      IdempotencyEnforcer.class,
      AuditEventService.class,
      CaseDataRepository.class,
      CaseProjectionService.class,
      CcdCallbackExecutor.class,
      CcdCaseDataMapperConfiguration.class,
      TestCaseConfig.class
  })
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class TestConfig {

    @Bean
    @Primary
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ResolvedConfigRegistry resolvedConfigRegistry(TestCaseConfig config) {
      return new ResolvedConfigRegistry(new CCDDefinitionGenerator(List.of(config), null).loadConfigs());
    }

    @Bean
    DefinitionRegistry definitionRegistry(ObjectMapper mapper) {
      return new DefinitionRegistry(mapper, new File("build/no-definition-snapshots"));
    }

    @Bean
    IdamService idamService() {
      IdamClient idamClient = mock(IdamClient.class);
      when(idamClient.getUserInfo(any())).thenReturn(UserInfo.builder()
          .uid("user-1")
          .givenName("Test")
          .familyName("User")
          .build());
      return new IdamService(idamClient, 10, 60);
    }

    @Bean
    CaseView<CaseData, State> caseView() {
      return new CaseView<>() {
        @Override
        public CaseData getCase(CaseViewRequest<State> request, CaseData blobCase) {
          return blobCase;
        }
      };
    }
  }

  static class TestCaseConfig implements CCDConfig<CaseData, State, Role> {

    AboutToStartOrSubmitResponse<CaseData, State> response;

    @Override
    public void configure(ConfigBuilder<CaseData, State, Role> builder) {
      builder.caseType("TestCase", "Test case", "Test case");
      builder.event("update")
          .forAllStates()
          .name("Update")
          .aboutToSubmitCallback((details, before) -> response);
    }
  }

  static class CaseData {
    public String applicantName;
    public String reason;
  }

  enum State {
    Open,
    Closed
  }

  enum Role implements HasRole {
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
