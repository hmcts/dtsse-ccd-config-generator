package uk.gov.hmcts.ccd.sdk.runtime.noc;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestionsResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.reform.authorisation.exceptions.InvalidTokenException;
import uk.gov.hmcts.reform.authorisation.exceptions.ServiceException;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

@RestController
@ConditionalOnBean(NocEndpoint.class)
@RequestMapping("/noc")
public class NocController {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";

  private final NocEndpoint endpoint;
  private final AuthTokenValidator serviceAuthTokenValidator;

  public NocController(NocEndpoint endpoint, AuthTokenValidator serviceAuthTokenValidator) {
    this.endpoint = endpoint;
    this.serviceAuthTokenValidator = serviceAuthTokenValidator;
  }

  @GetMapping("/noc-questions")
  public ResponseEntity<NocQuestionsResponse> getQuestions(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestParam("case_id") long caseId
  ) {
    verifyServiceAuthorisation(serviceAuthorisation);
    return ResponseEntity.ok(endpoint.getQuestions(caseId));
  }

  @PostMapping("/verify-noc-answers")
  public ResponseEntity<Boolean> verifyAnswers(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestBody NocAnswersRequest request
  ) {
    verifyServiceAuthorisation(serviceAuthorisation);
    return ResponseEntity.ok(endpoint.verifyAnswers(request));
  }

  @PostMapping("/noc-requests")
  public ResponseEntity<NocSubmissionResponse> submit(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestHeader(AUTHORIZATION) String authorisation,
      @RequestBody NocAnswersRequest request
  ) {
    verifyServiceAuthorisation(serviceAuthorisation);
    return ResponseEntity.ok(endpoint.submit(authorisation, request));
  }

  private void verifyServiceAuthorisation(String serviceAuthorisation) {
    if (serviceAuthorisation == null || serviceAuthorisation.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ServiceAuthorization token is missing");
    }

    String serviceName = getServiceName(serviceAuthorisation);
    if (!endpoint.isAuthorisedService(serviceName)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NoC caller service is not authorised");
    }
  }

  private String getServiceName(String serviceAuthorisation) {
    try {
      return serviceAuthTokenValidator.getServiceName(asBearerToken(serviceAuthorisation));
    } catch (InvalidTokenException | ServiceException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ServiceAuthorization token", ex);
    }
  }

  private String asBearerToken(String token) {
    return token.startsWith("Bearer") ? token : "Bearer " + token;
  }
}
