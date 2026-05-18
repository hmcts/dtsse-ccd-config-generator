package uk.gov.hmcts.ccd.sdk.runtime.noc;

import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswer;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestion;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestionsResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NocControllerTest {

  private final NocController controller = new NocController(endpoint(new Handler()), new TestAuthTokenValidator());

  @Test
  public void shouldDelegateQuestionsToEndpoint() {
    ResponseEntity<NocQuestionsResponse> response = controller.getQuestions("xui-service-token", 1234567890123456L);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().questions()).hasSize(1);
    assertThat(response.getBody().questions().getFirst().questionId()).isEqualTo("party-name");
  }

  @Test
  public void shouldDelegateVerificationToEndpoint() {
    ResponseEntity<Boolean> response = controller.verifyAnswers(
        "xui-service-token",
        new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("party-name", "Alex")))
    );

    assertThat(response.getBody()).isTrue();
  }

  @Test
  public void shouldDelegateSubmissionToEndpoint() {
    ResponseEntity<NocSubmissionResponse> response = controller.submit(
        "xui-service-token",
        "Bearer token",
        new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("party-name", "Alex")))
    );

    assertThat(response.getBody()).isEqualTo(NocSubmissionResponse.approved());
  }

  @Test
  public void shouldRejectNonXuiService() {
    assertThatThrownBy(() -> controller.getQuestions("pcs-service-token", 1234567890123456L))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  public void shouldRejectMissingServiceAuthorisation() {
    assertThatThrownBy(() -> controller.getQuestions(null, 1234567890123456L))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
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

    boolean verify(NocAnswersRequest request) {
      return true;
    }

    NocSubmissionResponse submit(String authorisation, NocAnswersRequest request) {
      return NocSubmissionResponse.approved();
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
}
