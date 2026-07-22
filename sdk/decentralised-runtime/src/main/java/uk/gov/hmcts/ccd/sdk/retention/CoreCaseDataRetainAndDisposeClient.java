package uk.gov.hmcts.ccd.sdk.retention;

import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.CONFIRM_DISPOSAL_EVENT_ID;
import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_EVENT_ID;
import static uk.gov.hmcts.ccd.sdk.RetainAndDisposePolicy.DISPOSAL_STATE_ID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import feign.FeignException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@RequiredArgsConstructor
class CoreCaseDataRetainAndDisposeClient {

  private final CoreCaseDataApi coreCaseDataApi;
  private final AuthTokenGenerator authTokenGenerator;
  private final IdamClient idamClient;
  private final String username;
  private final String password;
  private final Cache<String, SystemUser> systemUsers = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(30))
      .maximumSize(1)
      .build();

  void markForDisposal(RetainAndDisposeCase disposalCase) {
    triggerEvent(disposalCase, DISPOSAL_EVENT_ID);
  }

  void confirmDisposal(RetainAndDisposeCase disposalCase) {
    triggerEvent(disposalCase, CONFIRM_DISPOSAL_EVENT_ID);
  }

  void verifyReadable(RetainAndDisposeCase disposalCase) {
    try {
      read(disposalCase);
    } catch (FeignException.NotFound exception) {
      throw new IllegalStateException(
          "System user cannot read unconfirmed pending disposal case " + disposalCase.reference()
              + "; refusing to set its disposal TTL",
          exception
      );
    }
  }

  boolean exists(RetainAndDisposeCase disposalCase) {
    try {
      read(disposalCase);
      return true;
    } catch (FeignException.NotFound exception) {
      return false;
    }
  }

  private void triggerEvent(RetainAndDisposeCase disposalCase, String eventId) {
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
    if (result == null || !DISPOSAL_STATE_ID.equals(result.getState())) {
      String actualState = result == null ? null : result.getState();
      throw new IllegalStateException(
          "CCD event " + eventId + " left case " + caseReference + " in state " + actualState
              + " instead of " + DISPOSAL_STATE_ID
      );
    }
  }

  private void read(RetainAndDisposeCase disposalCase) {
    SystemUser systemUser = systemUser();
    coreCaseDataApi.readForCaseWorker(
        systemUser.authorization(),
        authTokenGenerator.generate(),
        systemUser.userId(),
        disposalCase.jurisdiction(),
        disposalCase.caseTypeId(),
        String.valueOf(disposalCase.reference())
    );
  }

  private SystemUser systemUser() {
    return systemUsers.get(username, ignored -> loadSystemUser());
  }

  private SystemUser loadSystemUser() {
    String authorization = idamClient.getAccessToken(username, password);
    if (authorization == null || authorization.isBlank()) {
      throw new IllegalStateException("IDAM returned an empty retain and dispose system user token");
    }
    String userId = idamClient.getUserInfo(authorization).getUid();
    if (userId == null || userId.isBlank()) {
      throw new IllegalStateException("Retain and dispose system user has no IDAM user ID");
    }
    return new SystemUser(authorization, userId);
  }

  private record SystemUser(String authorization, String userId) {
  }
}
