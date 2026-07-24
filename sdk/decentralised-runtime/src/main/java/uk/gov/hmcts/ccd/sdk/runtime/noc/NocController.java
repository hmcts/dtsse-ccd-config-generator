package uk.gov.hmcts.ccd.sdk.runtime.noc;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.NoticeOfChange;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmitContext;
import uk.gov.hmcts.ccd.sdk.impl.IdamService;
import uk.gov.hmcts.reform.authorisation.exceptions.InvalidTokenException;
import uk.gov.hmcts.reform.authorisation.exceptions.ServiceException;
import uk.gov.hmcts.reform.authorisation.validators.AuthTokenValidator;

@RestController
@ConditionalOnBean({ResolvedConfigRegistry.class, AuthTokenValidator.class, IdamService.class})
@RequestMapping("/noc")
public class NocController {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";

  private final ResolvedConfigRegistry registry;
  private final AuthTokenValidator serviceAuthTokenValidator;
  private final IdamService idamService;

  public NocController(
      ResolvedConfigRegistry registry,
      AuthTokenValidator serviceAuthTokenValidator,
      IdamService idamService
  ) {
    this.registry = registry;
    this.serviceAuthTokenValidator = serviceAuthTokenValidator;
    this.idamService = idamService;
  }

  @PostMapping("/verify-noc-answers")
  public ResponseEntity<NocAnswersResponse> verifyAnswers(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestHeader(value = AUTHORIZATION, required = false) String authorisation,
      @RequestBody NocAnswersRequest request
  ) {
    NocEndpoint endpoint = endpoint();
    verifyServiceAuthorisation(serviceAuthorisation, endpoint);
    NocAnswersResponse response = endpoint.validate(authenticateUser(authorisation), request);
    return response.isValid()
        ? ResponseEntity.ok(response)
        : ResponseEntity.badRequest().body(response);
  }

  @PostMapping("/noc-requests")
  public ResponseEntity<NocSubmissionResponse> submit(
      @RequestHeader(value = SERVICE_AUTHORIZATION, required = false) String serviceAuthorisation,
      @RequestHeader(value = AUTHORIZATION, required = false) String authorisation,
      @RequestBody NocAnswersRequest request
  ) {
    NocEndpoint endpoint = endpoint();
    verifyServiceAuthorisation(serviceAuthorisation, endpoint);
    NocSubmissionResponse response = endpoint.submit(authenticateUser(authorisation), request);
    return response.isAccepted()
        ? ResponseEntity.status(HttpStatus.CREATED).body(response)
        : ResponseEntity.badRequest().body(response);
  }

  private NocEndpoint endpoint() {
    List<NocEndpoint> endpoints = registry.getAll().stream()
        .map(ResolvedCCDConfig::getNoticeOfChange)
        .filter(noc -> noc != null)
        .map(NoticeOfChange::getEndpoint)
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

  private NocSubmitContext authenticateUser(String authorisation) {
    if (authorisation == null || authorisation.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization token is missing");
    }

    try {
      IdamService.User user = idamService.retrieveUser(authorisation);
      var userDetails = user.userDetails();
      return new NocSubmitContext(
          user.authToken(),
          userDetails.getUid(),
          userDetails.getSub(),
          userDetails.getName(),
          userDetails.getGivenName(),
          userDetails.getFamilyName(),
          userDetails.getRoles()
      );
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization token", ex);
    }
  }

  private String asBearerToken(String token) {
    String trimmedToken = token.trim();
    String tokenValue = trimmedToken.regionMatches(true, 0, "Bearer", 0, "Bearer".length())
        ? trimmedToken.substring("Bearer".length()).trim()
        : trimmedToken;
    return "Bearer " + tokenValue;
  }
}
