package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
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
import uk.gov.hmcts.reform.authorisation.exceptions.InvalidTokenException;
import uk.gov.hmcts.reform.authorisation.filters.ServiceAuthFilter;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

@SpringBootTest(classes = CcdEventTestSupportIntegrationTest.TestApplication.class)
@CcdSdkPostgresTest
class CcdEventTestSupportIntegrationTest {

  private static final String CASE_TYPE = "TestCase";

  @Autowired
  private CcdEventTestSupport<TestCase, TestState> events;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private AuthTokenValidator serviceTokens;

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
  void submissionPassesThroughTheApplicationsFiltersAsTheActor() {
    long reference = events.seed(TestState.Open, new TestCase("original"));
    var actor = events.registerActor(new ActorDetails(
        "actor-456", "judge@example.com", "Example", "Judge", List.of("caseworker")));

    var result = events.event(reference, "whoAmI", new TestCase("submitted"))
        .as(actor).submitExpectingSuccess();

    assertThat(result.confirmationHeader()).isEqualTo(actor.authorisation());
  }

  @Test
  void startRunsTheStartHandlerAsTheActor() {
    long reference = events.seed(TestState.Open, new TestCase("original"));
    var actor = events.registerActor(new ActorDetails(
        "actor-789", "judge@example.com", "Example", "Judge", List.of("caseworker")));

    var started = events.start(reference, "whoAmI").as(actor).startExpectingSuccess();

    assertThat(started.caseData().value()).isEqualTo("original for " + actor.authorisation());
    assertThat(events.snapshot(reference).caseRevision()).isZero();
  }

  @Test
  void startedEventSubmitsAsTheSameActor() {
    long reference = events.seed(TestState.Open, new TestCase("original"));
    var actor = events.registerActor("Example", "Judge", "caseworker");

    var result = events.start(reference, "whoAmI").as(actor).startExpectingSuccess()
        .edit(unchanged -> { })
        .submitExpectingSuccess();

    assertThat(result.confirmationHeader()).isEqualTo(actor.authorisation());
    assertThat(jdbc.queryForObject("select user_id from ccd.case_event where id = ?",
        String.class, result.audit().id())).isEqualTo(actor.uid());
    assertThat(actor.details().email()).isEqualTo("example.judge@example.com");
  }

  @Test
  void startedEventSubmitsFromTheRevisionItWasStartedAt() {
    long reference = events.seed(TestState.Open, new TestCase("original"));
    var started = events.start(reference, "serial").startExpectingSuccess();
    events.event(reference, "readOnly", new TestCase("meanwhile")).submitExpectingSuccess();

    var failed = started.submitting(new TestCase("edited")).submitExpectingFailure(409);

    assertThat(failed.snapshot().caseRevision()).isEqualTo(1);
    assertThat(events.start(reference, "serial").startExpectingSuccess()
        .submitting(new TestCase("edited")).submitExpectingSuccess().audit().revision()).isEqualTo(2);
  }

  @Test
  void applicationErrorStatusIsAFailedSubmission() {
    long reference = events.seed(TestState.Open, new TestCase("original"));

    var failed = events.event(reference, "conflict", new TestCase("submitted")).submitExpectingFailure(409);

    assertThat(failed.body()).contains("already being changed");
    assertThat(failed.snapshot().caseRevision()).isZero();
    assertThatThrownBy(() -> events.event(reference, "conflict", new TestCase("submitted"))
        .submitExpectingSuccess())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("returned HTTP 409");
  }

  @Test
  void acceptedResultListsTheRowsTheEventChanged() {
    jdbc.execute("create table if not exists public.audited_rows (id serial primary key, value text)");
    jdbc.execute("drop trigger if exists ccd_audit_row_changes on public.audited_rows");
    jdbc.execute("call ccd.attach_case_event_auditing_v1('public.audited_rows')");
    long reference = events.seed(TestState.Open, new TestCase("original"));

    var result = events.event(reference, "writeRow", new TestCase("written")).submitExpectingSuccess();

    assertThat(result.changes("audited_rows")).singleElement().satisfies(change -> {
      assertThat(change.operation()).isEqualTo(CcdEventTestSupport.RowChange.Operation.INSERT);
      assertThat(change.oldValues()).isNull();
      assertThat(change.newValues().path("value").asText()).isEqualTo("written");
    });
    assertThat(result.changes()).extracting(CcdEventTestSupport.RowChange::table)
        .containsExactly("audited_rows");
  }

  @Test
  void startWithoutAnActorUsesTheDefaultUser() {
    long reference = events.seed(TestState.Open, new TestCase("original"));

    var started = events.start(reference, "whoAmI").startExpectingSuccess();

    assertThat(started.caseData().value())
        .isEqualTo("original for " + CcdEventTestSupport.DEFAULT_AUTHORISATION);
  }

  @Test
  void onlyTheHelpersServiceTokenBypassesTheApplicationsValidator() {
    assertThat(serviceTokens.getServiceName(CcdEventTestSupport.SERVICE_AUTHORISATION)).isEqualTo("ccd_data");
    assertThatThrownBy(() -> serviceTokens.getServiceName("Bearer another-service"))
        .isInstanceOf(InvalidTokenException.class);
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

  /** Stands in for an application filter that authenticates the caller from its token. */
  static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

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
      DecentralisedFlywayAutoConfiguration.class,
      HttpMessageConvertersAutoConfiguration.class,
      DispatcherServletAutoConfiguration.class,
      WebMvcAutoConfiguration.class
  })
  static class TestApplication {

    /** Rejects every token, so only test support's shim lets its requests through. */
    @Bean
    AuthTokenValidator serviceTokenValidator() {
      return new AuthTokenValidator() {
        @Override
        public void validate(String token) {
          throw new InvalidTokenException("unknown service");
        }

        @Override
        public void validate(String token, List<String> roles) {
          throw new InvalidTokenException("unknown service");
        }

        @Override
        public String getServiceName(String token) {
          throw new InvalidTokenException("unknown service");
        }
      };
    }

    @Bean
    ServiceAuthFilter serviceAuthFilter(AuthTokenValidator validator) {
      return new ServiceAuthFilter(validator, List.of("ccd_data"));
    }

    @Bean
    OncePerRequestFilter currentUserFilter() {
      return new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws IOException, ServletException {
          CURRENT_USER.set(request.getHeader(HttpHeaders.AUTHORIZATION));
          try {
            chain.doFilter(request, response);
          } finally {
            CURRENT_USER.remove();
          }
        }
      };
    }

    @Bean({"objectMapper", "ccd_mapper", "ccdCaseDataObjectMapper"})
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    ConflictAdvice conflictAdvice() {
      return new ConflictAdvice();
    }

    @RestControllerAdvice
    static class ConflictAdvice {
      @ExceptionHandler(IllegalStateException.class)
      ResponseEntity<String> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
      }
    }

    @Bean
    CCDConfig<TestCase, TestState, TestRole> config(JdbcTemplate jdbc) {
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
          builder.decentralisedEvent("whoAmI",
              payload -> SubmitResponse.<TestState>builder().confirmationHeader(CURRENT_USER.get()).build(),
              payload -> new TestCase(payload.caseData().value() + " for " + CURRENT_USER.get()))
              .forAllStates();
          builder.decentralisedEvent("reject", payload -> SubmitResponse.<TestState>builder()
              .errors(List.of("invalid")).build()).forAllStates();
          builder.decentralisedEvent("openOnly", payload -> SubmitResponse.defaultResponse())
              .forState(TestState.Open);
          builder.decentralisedEvent("serial", payload -> SubmitResponse.defaultResponse(),
              payload -> payload.caseData()).forAllStates().nonConcurrent();
          builder.decentralisedEvent("conflict", payload -> {
            throw new IllegalStateException("The case is already being changed");
          }).forAllStates();
          builder.decentralisedEvent("writeRow", payload -> {
            jdbc.update("insert into public.audited_rows (value) values (?)", payload.caseData().value());
            return SubmitResponse.defaultResponse();
          }).forAllStates();
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
