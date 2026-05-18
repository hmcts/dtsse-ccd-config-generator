package uk.gov.hmcts.ccd.sdk.runtime.noc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswer;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestion;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestionsResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

public class NocControllerTest {

  private final NocController controller = new NocController(
      registry(endpoint(new Handler())),
      new TestAuthTokenValidator()
  );

  @Test
  void shouldDelegateQuestionsToEndpoint() {
    ResponseEntity<NocQuestionsResponse> response = controller.getQuestions("xui-service-token", 1234567890123456L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().questions()).hasSize(1);
    assertThat(response.getBody().questions().getFirst().questionId()).isEqualTo("party-name");
  }

  @Test
  void shouldDelegateVerificationToEndpoint() {
    ResponseEntity<NocAnswersResponse> response = controller.verifyAnswers(
        "xui-service-token",
        new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("party-name", "Alex")))
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().isValid()).isTrue();
  }

  @Test
  void shouldReturnBadRequestForInvalidVerificationResponse() {
    NocController controller = new NocController(
        registry(endpoint(new InvalidVerificationHandler())),
        new TestAuthTokenValidator()
    );

    ResponseEntity<NocAnswersResponse> response = controller.verifyAnswers(
        "xui-service-token",
        new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("party-name", "Alex")))
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("answers-not-matched-any-litigant");
  }

  @Test
  void shouldDelegateSubmissionToEndpoint() {
    ResponseEntity<NocSubmissionResponse> response = controller.submit(
        "xui-service-token",
        "Bearer token",
        new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("party-name", "Alex")))
    );

    assertThat(response.getBody()).isEqualTo(NocSubmissionResponse.approved());
  }

  @Test
  void shouldRejectNonXuiService() {
    assertThatThrownBy(() -> controller.getQuestions("pcs-service-token", 1234567890123456L))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void shouldRejectMissingServiceAuthorisation() {
    assertThatThrownBy(() -> controller.getQuestions(null, 1234567890123456L))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void shouldRejectWhenNoEndpointIsConfigured() {
    NocController controller = new NocController(registry(null), new TestAuthTokenValidator());

    assertThatThrownBy(() -> controller.getQuestions("xui-service-token", 1234567890123456L))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static ResolvedConfigRegistry registry(NocEndpoint endpoint) {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> config = new ResolvedCCDConfig<>(
        TestCaseData.class,
        TestState.class,
        TestRole.class,
        Map.of(),
        ImmutableSet.of(TestState.SUBMITTED)
    );
    setField(config, "caseType", "TEST");
    setField(config, "nocEndpoint", endpoint);
    return new ResolvedConfigRegistry(List.of(config));
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static NocEndpoint endpoint(Handler handler) {
    return NocEndpoint.builder()
        .questions(handler::questions)
        .verifyAnswers(handler::verify)
        .submit(handler::submit)
        .build();
  }

  private static class Handler {

    NocQuestionsResponse questions(long caseId) {
      return new NocQuestionsResponse(List.of(
          NocQuestion.text("TEST", "1", "What is your name?", "NoC", "party-name")
      ));
    }

    NocAnswersResponse verify(NocAnswersRequest request) {
      return NocAnswersResponse.verified();
    }

    NocSubmissionResponse submit(String authorisation, NocAnswersRequest request) {
      return NocSubmissionResponse.approved();
    }
  }

  private static class InvalidVerificationHandler extends Handler {

    @Override
    NocAnswersResponse verify(NocAnswersRequest request) {
      return NocAnswersResponse.invalid(
          "answers-not-matched-any-litigant",
          "The answers did not match those for any litigant"
      );
    }
  }

  private static class TestAuthTokenValidator implements AuthTokenValidator {

    @Override
    public void validate(String token) {
    }

    @Override
    public void validate(String token, List<String> services) {
    }

    @Override
    public String getServiceName(String token) {
      if ("Bearer xui-service-token".equals(token)) {
        return NocEndpoint.XUI_WEBAPP_SERVICE;
      }
      return "pcs_api";
    }
  }

  private enum TestState {
    SUBMITTED
  }

  private static class TestCaseData {
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
