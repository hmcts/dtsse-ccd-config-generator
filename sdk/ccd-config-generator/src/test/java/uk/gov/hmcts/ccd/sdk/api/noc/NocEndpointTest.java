package uk.gov.hmcts.ccd.sdk.api.noc;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NocEndpointTest {

  @Test
  public void shouldBuildEndpointFromMethodReferences() {
    Handler handler = new Handler();
    NocEndpoint endpoint = NocEndpoint.builder()
        .questions(handler::questions)
        .verifyAnswers(handler::verify)
        .submit(handler::submit)
        .build();

    NocQuestionsResponse questions = endpoint.getQuestions(1234567890123456L);
    boolean verified = endpoint.verifyAnswers(new NocAnswersRequest(1234567890123456L, List.of()));
    NocSubmissionResponse submission = endpoint.submit(
        "Bearer token",
        new NocAnswersRequest(1234567890123456L, List.of())
    );

    assertThat(questions.questions()).hasSize(1);
    assertThat(verified).isTrue();
    assertThat(submission.approvalStatus()).isEqualTo("APPROVED");
    assertThat(endpoint.isAuthorisedService(NocEndpoint.XUI_WEBAPP_SERVICE)).isTrue();
    assertThat(endpoint.isAuthorisedService("pcs_api")).isFalse();
  }

  @Test
  public void shouldRequireAllHandlers() {
    assertThatThrownBy(() -> NocEndpoint.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessage("questionHandler");
  }

  private static class Handler {

    NocQuestionsResponse questions(long caseId) {
      return new NocQuestionsResponse(List.of(
          NocQuestion.text("TEST", "1", "What is your name?", "NoC", "name")
      ));
    }

    boolean verify(NocAnswersRequest request) {
      return true;
    }

    NocSubmissionResponse submit(String authorisation, NocAnswersRequest request) {
      return NocSubmissionResponse.approved();
    }
  }
}
