package uk.gov.hmcts.ccd.sdk.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class CaseSubmissionServiceTest {

  private static final UUID IDEMPOTENCY_KEY = UUID.randomUUID();

  private final ResolvedConfigRegistry resolvedConfigRegistry = mock(ResolvedConfigRegistry.class);
  private final DecentralisedSubmissionHandler submitHandler = mock(DecentralisedSubmissionHandler.class);
  private final LegacyCallbackSubmissionHandler legacyHandler = mock(LegacyCallbackSubmissionHandler.class);
  private final IdamService idam = mock(IdamService.class);
  private final IdempotencyEnforcer idempotencyEnforcer = mock(IdempotencyEnforcer.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final CaseProjectionService caseProjectionService = mock(CaseProjectionService.class);
  private final CaseEventTransactionCoordinator transactionCoordinator = new CaseEventTransactionCoordinator(
      idempotencyEnforcer,
      auditEventService,
      caseDataRepository,
      caseProjectionService
  );

  private final CaseSubmissionService service = new CaseSubmissionService(
      resolvedConfigRegistry,
      submitHandler,
      legacyHandler,
      idam,
      transactionCoordinator,
      caseDataRepository
  );

  @Test
  void passesNormalisedUserTokenToSubmissionHandler() {
    DecentralisedCaseEvent event = event();
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "submit");
    when(eventConfig.getSubmitHandler()).thenReturn(null);
    when(idam.retrieveUser("raw-token")).thenReturn(new IdamService.User(
        "Bearer raw-token",
        new UserInfo("sub", "uid", "name", "given", "family", List.of("caseworker"))
    ));
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(IDEMPOTENCY_KEY, 123456789L))
        .thenReturn(Optional.empty());
    when(auditEventService.reserveCaseEventId()).thenReturn(42L);
    when(legacyHandler.apply(eq(event), eq("Bearer raw-token"))).thenReturn(handlerResult());
    when(caseProjectionService.load(123456789L)).thenReturn(savedCaseDetails());
    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    verify(legacyHandler).apply(event, "Bearer raw-token");
    verify(auditEventService).saveAuditRecord(
        eq(42L),
        eq(event),
        any(IdamService.User.class),
        any(CaseDetails.class),
        eq(IDEMPOTENCY_KEY),
        eq(Optional.empty())
    );
  }

  @Test
  void idempotentReplayDoesNotReserveAnotherEventId() {
    DecentralisedCaseEvent event = event();
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "submit");
    when(eventConfig.getSubmitHandler()).thenReturn(null);
    when(idam.retrieveUser("raw-token")).thenReturn(new IdamService.User(
        "Bearer raw-token",
        new UserInfo("sub", "uid", "name", "given", "family", List.of("caseworker"))
    ));
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(IDEMPOTENCY_KEY, 123456789L))
        .thenReturn(Optional.of(99L));
    when(caseDataRepository.caseDetailsAtEvent(123456789L, 99L)).thenReturn(savedCaseDetails());
    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    verifyNoInteractions(auditEventService, legacyHandler);
  }

  private DecentralisedCaseEvent event() {
    var caseDetails = new CaseDetails();
    caseDetails.setReference(123456789L);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId("TestCase");
    caseDetails.setState("Submitted");
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);

    return DecentralisedCaseEvent.builder()
        .caseDetails(caseDetails)
        .eventDetails(DecentralisedEventDetails.builder()
            .caseType("TestCase")
            .eventId("submit")
            .build())
        .build();
  }

  private CaseSubmissionHandler.CaseSubmissionHandlerResult handlerResult() {
    return new CaseSubmissionHandler.CaseSubmissionHandlerResult(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        () -> SubmitResponse.builder().build()
    );
  }

  private DecentralisedCaseDetails savedCaseDetails() {
    var caseDetails = new CaseDetails();
    caseDetails.setReference(123456789L);

    var savedCaseDetails = new DecentralisedCaseDetails();
    savedCaseDetails.setCaseDetails(caseDetails);
    return savedCaseDetails;
  }
}
