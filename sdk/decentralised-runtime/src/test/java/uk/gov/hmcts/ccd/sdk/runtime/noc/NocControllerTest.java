package uk.gov.hmcts.ccd.sdk.runtime.noc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswer;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocOrganisation;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.ccd.sdk.impl.IdamService;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class NocControllerTest {

  private static final String AUTHORISATION = "Bearer user-token";
  private static final String SERVICE_AUTHORISATION = "Bearer service-token";
  private static final String CASE_ROLE = "[DEFENDANTSOLICITOR]";

  private AuthTokenValidator authTokenValidator;
  private NocController controller;

  @BeforeEach
  void setUp() {
    authTokenValidator = mock(AuthTokenValidator.class);
    IdamService idamService = new IdamService(new StubIdamClient(), 10, 60);
    controller = new NocController(registry(), authTokenValidator, idamService);
  }

  @Test
  void shouldValidateAnswers() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("xui_webapp");

    var response = controller.verifyAnswers(SERVICE_AUTHORISATION, AUTHORISATION, request("Sam"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().organisation().organisationId()).isEqualTo("ORG1");
    assertThat(response.getBody().statusMessage()).isEqualTo("Notice of Change answers verified successfully");
  }

  @Test
  void shouldReturnBadRequestForInvalidAnswers() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("xui_webapp");

    var response = controller.verifyAnswers(SERVICE_AUTHORISATION, AUTHORISATION, request("Wrong"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("answers-not-matched-any-litigant");
  }

  @Test
  void shouldSubmitNoticeOfChange() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("xui_webapp");

    var response = controller.submit(SERVICE_AUTHORISATION, AUTHORISATION, request("Sam"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().approvalStatus()).isEqualTo("APPROVED");
    assertThat(response.getBody().caseRole()).isEqualTo(CASE_ROLE);
  }

  @Test
  void shouldRejectUnauthorisedService() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("ccd_data");

    assertThatThrownBy(() -> controller.verifyAnswers(SERVICE_AUTHORISATION, AUTHORISATION, request("Sam")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void shouldRequireAuthorizationToken() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("xui_webapp");

    assertThatThrownBy(() -> controller.submit(SERVICE_AUTHORISATION, "", request("Sam")))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void shouldNormaliseServiceAuthorizationBearerPrefix() {
    when(authTokenValidator.getServiceName(SERVICE_AUTHORISATION)).thenReturn("xui_webapp");

    var response = controller.verifyAnswers("bearerservice-token", AUTHORISATION, request("Sam"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(authTokenValidator).getServiceName(SERVICE_AUTHORISATION);
  }

  private static NocAnswersRequest request(String firstName) {
    return new NocAnswersRequest(1234567890123456L, List.of(new NocAnswer("first-name", firstName)));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ResolvedConfigRegistry registry() {
    NocEndpoint endpoint = NocEndpoint.builder()
        .caseTypeId("PCS")
        .validate((context, request) -> "Sam".equals(request.answers().getFirst().value())
            ? NocAnswersResponse.verified(new NocOrganisation("ORG1", "Org 1"))
            : NocAnswersResponse.answersNotMatchedAnyLitigant())
        .submit((context, request) -> NocSubmissionResponse.approved(CASE_ROLE))
        .build();
    NoticeOfChange noticeOfChange = new NoticeOfChange();
    noticeOfChange.setEndpoint(endpoint);
    ResolvedCCDConfig config = mock(ResolvedCCDConfig.class);
    when(config.getCaseType()).thenReturn("PCS");
    when(config.getNoticeOfChange()).thenReturn(noticeOfChange);
    return new ResolvedConfigRegistry(List.of(config));
  }

  private static class StubIdamClient extends IdamClient {
    StubIdamClient() {
      super(null, null);
    }

    @Override
    public UserInfo getUserInfo(String authorisation) {
      return new UserInfo("user@example.com", "user-id", "Name", "Given", "Family", List.of("caseworker"));
    }
  }
}
