package uk.gov.hmcts.ccd.sdk.runtime.noc;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import java.util.List;
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
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocQuestionsResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.reform.authorisation.exceptions.InvalidTokenException;
import uk.gov.hmcts.reform.authorisation.exceptions.ServiceException;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

@RestController
@ConditionalOnBean(ResolvedConfigRegistry.class)
@RequestMapping("/noc")
public class NocController {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";

  private final ResolvedConfigRegistry registry;
  private final AuthTokenValidator serviceAuthTokenValidator;

  public NocController(ResolvedConfigRegistry registry, AuthTokenValidator serviceAuthTokenValidator) {
    this.registry = registry;
    this.serviceAuthTokenValidator = serviceAuthTokenValidator;
  }

  @GetMapping("/noc-questions")
  public ResponseEntity<NocQuestionsResponse> getQuestions(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestParam("case_id") long caseId
  ) {
    NocEndpoint endpoint = endpoint();
    verifyServiceAuthorisation(serviceAuthorisation, endpoint);
    return ResponseEntity.ok(endpoint.getQuestions(caseId));
  }

  @PostMapping("/verify-noc-answers")
  public ResponseEntity<NocAnswersResponse> verifyAnswers(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestBody NocAnswersRequest request
  ) {
    NocEndpoint endpoint = endpoint();
    verifyServiceAuthorisation(serviceAuthorisation, endpoint);
    NocAnswersResponse response = endpoint.verifyAnswers(request);
    return response.isValid()
        ? ResponseEntity.ok(response)
        : ResponseEntity.badRequest().body(response);
  }

  @PostMapping("/noc-requests")
  public ResponseEntity<NocSubmissionResponse> submit(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestHeader(AUTHORIZATION) String authorisation,
      @RequestBody NocAnswersRequest request
  ) {
    NocEndpoint endpoint = endpoint();
    verifyServiceAuthorisation(serviceAuthorisation, endpoint);
    NocSubmissionResponse response = endpoint.submit(authorisation, request);
    return response.isApproved()
        ? ResponseEntity.ok(response)
        : ResponseEntity.badRequest().body(response);
  }

  private NocEndpoint endpoint() {
    List<NocEndpoint> endpoints = registry.getAll().stream()
        .map(ResolvedCCDConfig::getNocEndpoint)
        .filter(endpoint -> endpoint != null)
        .toList();

    if (endpoints.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No NoC endpoint is configured");
    }

    if (endpoints.size() > 1) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Multiple NoC endpoints are configured");
    }

    return endpoints.getFirst();
  }

  private void verifyServiceAuthorisation(String serviceAuthorisation, NocEndpoint endpoint) {
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
