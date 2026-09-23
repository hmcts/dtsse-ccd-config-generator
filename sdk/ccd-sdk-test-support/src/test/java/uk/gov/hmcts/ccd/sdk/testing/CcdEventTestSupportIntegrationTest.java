package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

@SpringBootTest(classes = CcdEventTestSupportIntegrationTest.TestApplication.class)
@CcdSdkPostgresTest
class CcdEventTestSupportIntegrationTest {

  private static final String CASE_TYPE = "TestCase";

  @Autowired
  private CcdEventTestSupport<TestCase, TestState> events;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void decentralisedEventWritesAuditWithoutChangingBlob() {
    var cases = events.caseType();
    long reference = events.seed(TestState.Open, new TestCase("original"));
    var before = cases.rawData(reference);

    var result = cases.event(reference, "readOnly", new TestCase("submitted")).submitExpectingSuccess();

    assertThat(result.rawData()).isEqualTo(before);
    assertThat(result.projectedCase().value()).isEqualTo("original");
    assertThat(result.storedData().value()).isEqualTo("original");
    assertThat(result.audit().eventId()).isEqualTo("readOnly");
    assertThat(result.audit().revision()).isEqualTo(1);
    assertThat(result.caseRevision()).isEqualTo(1);
    assertThat(result.blobVersion()).isEqualTo(1);
  }

  @Test
  void legacyCallbackEventPersistsItsData() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seed(TestState.Open, new TestCase("original"));
    var dates = jdbc.queryForMap("""
        select created_date, last_modified, last_state_modified_date
        from ccd.case_data where reference = ?
        """, reference);

    var result = cases.event(reference, "legacy", new TestCase("submitted")).submitExpectingSuccess();

    assertThat(dates).doesNotContainValue(null);
    assertThat(result.storedData().value()).isEqualTo("from callback");
    assertThat(result.audit().eventId()).isEqualTo("legacy");
  }

  @Test
  void validationErrorDoesNotWriteAnAuditEvent() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seed(TestState.Open, new TestCase("original"));

    var result = cases.event(reference, "reject", new TestCase("submitted")).submitExpectingErrors();

    assertThat(result.errors()).containsExactly("invalid");
    assertThat(result.storedData().value()).isEqualTo("original");
    assertThat(result.state()).isEqualTo(TestState.Open);
  }

  @Test
  void duplicateSubmissionReplaysExistingAuditEvent() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seed(TestState.Open, new TestCase("original"));
    UUID key = UUID.randomUUID();

    var first = cases.event(reference, "readOnly", new TestCase("submitted"))
        .withIdempotencyKey(key).submitExpectingSuccess();
    var replayed = cases.event(reference, "readOnly", new TestCase("submitted"))
        .withIdempotencyKey(key).submitExpectingSuccess();

    assertThat(replayed.audit().id()).isEqualTo(first.audit().id());
    assertThat(replayed.audit().revision()).isEqualTo(1);
  }

  @Test
  void concurrentEventAcceptsAnOlderStartRevision() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seed(TestState.Open, new TestCase("original"));
    cases.event(reference, "readOnly", new TestCase("submitted")).submitExpectingSuccess();

    var later = cases.event(reference, "readOnly", new TestCase("submitted"))
        .atRevision(0).submitExpectingSuccess();

    assertThat(later.audit().revision()).isEqualTo(2);
    assertThat(later.storedData().value()).isEqualTo("original");
    assertThat(later.caseRevision()).isEqualTo(2);
    assertThat(later.blobVersion()).isEqualTo(1);
  }

  @Test
  void seedSupportsCaseMetadata() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seedCase(TestState.Open, new TestCase("original"))
        .supplementaryData(Map.of("source", "test"))
        .classification(TestClassification.PRIVATE)
        .ttl(LocalDate.of(2027, 1, 1))
        .insert();

    var row = jdbc.queryForMap("""
        select supplementary_data::text as supplementary_data,
               security_classification::text as classification, resolved_ttl
        from ccd.case_data where reference = ?
        """, reference);
    assertThat(row.get("supplementary_data").toString()).contains("source", "test");
    assertThat(row.get("classification")).isEqualTo("PRIVATE");
    assertThat(row.get("resolved_ttl").toString()).isEqualTo("2027-01-01");
  }

  @Test
  void createEventWritesCaseAndHistory() {
    var cases = events.forCaseType(CASE_TYPE);
    var request = cases.create("create", TestState.Open, new TestCase("submitted"));

    var created = request.submitExpectingSuccess();

    assertThat(request.reference()).isBetween(1000000000000000L, 9999999999999999L);
    assertThat(created.audit().eventId()).isEqualTo("create");
    assertThat(created.storedData().value()).isEqualTo("created");
    assertThat(created.caseRevision()).isEqualTo(1);
  }

  @Test
  void rejectedCreationHasNoStoredCase() {
    var request = events.create("createReject", TestState.Open, new TestCase("submitted"));

    var rejected = request.submitExpectingErrors();

    assertThat(rejected.errors()).containsExactly("cannot create");
    assertThat(jdbc.queryForObject("select count(*) from ccd.case_data where reference = ?",
        Long.class, request.reference())).isZero();
  }

  @Test
  void registeredActorIsUsedForAudit() {
    var cases = events.forCaseType(CASE_TYPE);
    long reference = cases.seed(TestState.Open, new TestCase("original"));
    var actor = events.registerActor(new ActorDetails(
        "actor-123", "actor@example.com", "Example", "Actor", List.of("caseworker")));

    var result = cases.event(reference, "readOnly", new TestCase("submitted"))
        .as(actor).submitExpectingSuccess();

    assertThat(jdbc.queryForObject("select user_id from ccd.case_event where id = ?",
        String.class, result.audit().id())).isEqualTo("actor-123");
  }

  @Test
  void rejectsEventOutsideItsAllowedPreStates() {
    long reference = events.seed(TestState.Closed, new TestCase("closed"));

    assertThatThrownBy(() -> events.event(reference, "openOnly", new TestCase("submitted"))
        .submitExpectingSuccess())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable in state Closed");
    assertThat(jdbc.queryForObject("select count(*) from ccd.case_event where case_data_id = ?",
        Long.class, reference)).isZero();
  }

  @Test
  void acceptedResultExposesMetadataWithoutRuntimeDto() {
    long reference = events.seedCase(TestState.Open, new TestCase("original"))
        .supplementaryData(Map.of("source", "fixture"))
        .insert();

    var result = events.event(reference, "metadata", new TestCase("submitted"))
        .submitExpectingSuccess();

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings()).containsExactly("check");
    assertThat(result.confirmationHeader()).isEqualTo("Done");
    assertThat(result.confirmationBody()).isEqualTo("Saved");
    assertThat(result.state()).isEqualTo(TestState.Closed);
    assertThat(result.classification()).isEqualTo(TestClassification.PRIVATE);
    assertThat(result.supplementaryData()).containsEntry("source", "fixture");
  }

  record TestCase(String value) {
  }

  enum TestState {
    Open,
    Closed
  }

  enum TestRole implements HasRole {
    User;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }

  @SpringBootConfiguration
  @ImportAutoConfiguration({
      DataSourceAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class,
      DecentralisedFlywayAutoConfiguration.class
  })
  static class TestApplication {

    @Bean({"objectMapper", "ccd_mapper", "ccdCaseDataObjectMapper"})
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    CCDConfig<TestCase, TestState, TestRole> config() {
      return new CCDConfig<>() {
        @Override
        public Set<String> caseTypeIds() {
          return Set.of(CASE_TYPE);
        }

        @Override
        public void configureDecentralised(DecentralisedConfigBuilder<TestCase, TestState, TestRole> builder) {
          builder.caseType(CASE_TYPE, CASE_TYPE, CASE_TYPE);
          builder.jurisdiction("TEST", "Test", "Test");
          builder.decentralisedEvent("readOnly", payload -> SubmitResponse.defaultResponse()).forAllStates();
          builder.decentralisedEvent("reject", payload -> SubmitResponse.<TestState>builder()
              .errors(List.of("invalid")).build()).forAllStates();
          builder.decentralisedEvent("openOnly", payload -> SubmitResponse.defaultResponse())
              .forState(TestState.Open);
          builder.decentralisedEvent("metadata", payload -> SubmitResponse.<TestState>builder()
              .warnings(List.of("check"))
              .confirmationHeader("Done")
              .confirmationBody("Saved")
              .state(TestState.Closed)
              .caseSecurityClassification(Classification.PRIVATE)
              .build()).forAllStates();
          builder.event("legacy").forAllStates().aboutToSubmitCallback((details, before) ->
              AboutToStartOrSubmitResponse.<TestCase, TestState>builder()
                  .data(new TestCase("from callback"))
                  .build());
          builder.event("create").initialState(TestState.Open).aboutToSubmitCallback((details, before) ->
              AboutToStartOrSubmitResponse.<TestCase, TestState>builder()
                  .data(new TestCase("created"))
                  .build());
          builder.event("createReject").initialState(TestState.Open).aboutToSubmitCallback((details, before) ->
              AboutToStartOrSubmitResponse.<TestCase, TestState>builder()
                  .errors(List.of("cannot create"))
                  .build());
        }
      };
    }

    @Bean
    ResolvedConfigRegistry resolvedConfigRegistry(List<CCDConfig<?, ?, ?>> configs) {
      return new ResolvedConfigRegistry(new CCDDefinitionGenerator(configs, null).loadConfigs());
    }

    @Bean
    CaseView<TestCase, TestState> caseView() {
      return new CaseView<>() {
        @Override
        public Set<String> caseTypeIds() {
          return Set.of(CASE_TYPE);
        }

        @Override
        public TestCase getCase(CaseViewRequest<TestState> request, TestCase blobCase) {
          return blobCase;
        }
      };
    }
  }
}
