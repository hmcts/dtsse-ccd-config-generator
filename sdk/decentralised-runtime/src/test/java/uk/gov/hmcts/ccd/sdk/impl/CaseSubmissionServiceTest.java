package uk.gov.hmcts.ccd.sdk.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
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
  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final CaseProjectionService caseProjectionService = mock(CaseProjectionService.class);

  private final CaseSubmissionService service = new CaseSubmissionService(
      resolvedConfigRegistry,
      submitHandler,
      legacyHandler,
      idam,
      idempotencyEnforcer,
      transactionTemplate,
      auditEventService,
      caseDataRepository,
      caseProjectionService
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
    when(legacyHandler.apply(eq(event), eq("Bearer raw-token"))).thenReturn(handlerResult());
    when(caseProjectionService.load(123456789L)).thenReturn(savedCaseDetails());
    when(transactionTemplate.execute(any())).thenAnswer(invocation ->
        invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null)
    );

    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    verify(legacyHandler).apply(event, "Bearer raw-token");
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
