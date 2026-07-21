package uk.gov.hmcts.ccd.sdk.retention;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import feign.FeignException;
import java.time.Duration;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;

class CoreCaseDataRetainAndDisposeClient implements CcdRetainAndDisposeClient {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CACHE_KEY = "system-user";

  private final CoreCaseDataApi coreCaseDataApi;
  private final AuthTokenGenerator authTokenGenerator;
  private final IdamClient idamClient;
  private final String username;
  private final String password;
  private final Cache<String, SystemUser> systemUserCache = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(30))
      .maximumSize(1)
      .build();

  CoreCaseDataRetainAndDisposeClient(
      CoreCaseDataApi coreCaseDataApi,
      AuthTokenGenerator authTokenGenerator,
      IdamClient idamClient,
      String username,
      String password
  ) {
    this.coreCaseDataApi = coreCaseDataApi;
    this.authTokenGenerator = authTokenGenerator;
    this.idamClient = idamClient;
    this.username = username;
    this.password = password;
  }

  @Override
  public void moveToTerminalState(RetainAndDisposeCase disposalCase, String eventId, String terminalState) {
    SystemUser systemUser = systemUser();
    String serviceAuthorization = authTokenGenerator.generate();
    String caseReference = String.valueOf(disposalCase.reference());

    StartEventResponse startEvent = coreCaseDataApi.startEventForCaseWorker(
        systemUser.authorization(),
        serviceAuthorization,
        systemUser.userId(),
        disposalCase.jurisdiction(),
        disposalCase.caseTypeId(),
        caseReference,
        eventId
    );
    if (startEvent == null || startEvent.getCaseDetails() == null
        || startEvent.getToken() == null || startEvent.getToken().isBlank()) {
      throw new IllegalStateException("CCD returned an invalid start-event response for case " + caseReference);
    }

    CaseDataContent content = CaseDataContent.builder()
        .event(Event.builder().id(eventId).build())
        .data(startEvent.getCaseDetails().getData())
        .eventToken(startEvent.getToken())
        .ignoreWarning(false)
        .build();
    CaseDetails result = coreCaseDataApi.submitEventForCaseWorker(
        systemUser.authorization(),
        serviceAuthorization,
        systemUser.userId(),
        disposalCase.jurisdiction(),
        disposalCase.caseTypeId(),
        caseReference,
        false,
        content
    );
    if (result == null || !terminalState.equals(result.getState())) {
      String actualState = result == null ? null : result.getState();
      throw new IllegalStateException(
          "CCD event " + eventId + " left case " + caseReference + " in state " + actualState
              + " instead of " + terminalState
      );
    }
  }

  @Override
  public boolean exists(RetainAndDisposeCase disposalCase) {
    SystemUser systemUser = systemUser();
    try {
      coreCaseDataApi.readForCaseWorker(
          systemUser.authorization(),
          authTokenGenerator.generate(),
          systemUser.userId(),
          disposalCase.jurisdiction(),
          disposalCase.caseTypeId(),
          String.valueOf(disposalCase.reference())
      );
      return true;
    } catch (FeignException.NotFound exception) {
      return false;
    }
  }

  private SystemUser systemUser() {
    return systemUserCache.get(CACHE_KEY, ignored -> loadSystemUser());
  }

  private SystemUser loadSystemUser() {
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Retain and dispose system user credentials must be configured with "
              + "ccd.decentralised-runtime.retain-and-dispose.system-user.username and password"
      );
    }

    String authorization = bearer(idamClient.getAccessToken(username, password));
    String userId = idamClient.getUserInfo(authorization).getUid();
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException("Retain and dispose system user has no IDAM user ID");
    }
    return new SystemUser(authorization, userId);
  }

  private String bearer(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("IDAM returned an empty retain and dispose system user token");
    }
    if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return BEARER_PREFIX + token.substring(BEARER_PREFIX.length());
    }
    return BEARER_PREFIX + token;
  }

  private record SystemUser(String authorization, String userId) {
  }
}
