package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CaseSubmissionServiceTest {

  private final ResolvedConfigRegistry resolvedConfigRegistry = mock(ResolvedConfigRegistry.class);
  private final DecentralisedSubmissionHandler submitHandler = mock(DecentralisedSubmissionHandler.class);
  private final LegacyCallbackSubmissionHandler legacyHandler = mock(LegacyCallbackSubmissionHandler.class);
  private final JsonDefinitionSubmissionHandler jsonDefinitionHandler = mock(JsonDefinitionSubmissionHandler.class);
  private final IdamService idamService = mock(IdamService.class);
  private final IdempotencyEnforcer idempotencyEnforcer = mock(IdempotencyEnforcer.class);
  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final CaseProjectionService caseProjectionService = mock(CaseProjectionService.class);

  private final CaseSubmissionService service = new CaseSubmissionService(
      resolvedConfigRegistry,
      submitHandler,
      legacyHandler,
      Optional.of(jsonDefinitionHandler),
      idamService,
      idempotencyEnforcer,
      transactionTemplate,
      auditEventService,
      caseDataRepository,
      caseProjectionService
  );

  private final CaseSubmissionService serviceWithoutJsonHandler = new CaseSubmissionService(
      resolvedConfigRegistry,
      submitHandler,
      legacyHandler,
      Optional.empty(),
      idamService,
      idempotencyEnforcer,
      transactionTemplate,
      auditEventService,
      caseDataRepository,
      caseProjectionService
  );

  @Test
  void submitUsesJsonDefinitionHandlerWhenLegacyJsonServiceIsEnabled() {
    setupTransactionExecution();
    final DecentralisedCaseEvent event = buildEvent();
    final UUID idempotencyKey = UUID.randomUUID();
    final Event eventConfig = mock(Event.class);
    final var user = new IdamService.User("Bearer token", null);

    ReflectionTestUtils.setField(service, "isLegacyJsonDefinition", true);
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("CASE_TYPE", "EVENT_ID");
    when(idamService.retrieveUser("Bearer token")).thenReturn(user);
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(idempotencyKey, 1234567890123456L))
        .thenReturn(Optional.empty());
    when(jsonDefinitionHandler.apply(event)).thenReturn(defaultResult());
    when(caseProjectionService.load(1234567890123456L)).thenReturn(buildSavedCaseDetails());

    service.submit(event, "Bearer token", idempotencyKey);

    verify(jsonDefinitionHandler).apply(event);
    verifyNoInteractions(submitHandler, legacyHandler);
  }

  @Test
  void submitUsesDecentralisedSubmitHandlerWhenConfigured() {
    setupTransactionExecution();
    final DecentralisedCaseEvent event = buildEvent();
    final UUID idempotencyKey = UUID.randomUUID();
    final Event eventConfig = mock(Event.class);
    final var user = new IdamService.User("Bearer token", null);

    ReflectionTestUtils.setField(service, "isLegacyJsonDefinition", false);
    when(eventConfig.getSubmitHandler()).thenReturn(mock(uk.gov.hmcts.ccd.sdk.api.callback.Submit.class));
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("CASE_TYPE", "EVENT_ID");
    when(idamService.retrieveUser("Bearer token")).thenReturn(user);
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(idempotencyKey, 1234567890123456L))
        .thenReturn(Optional.empty());
    when(submitHandler.apply(event)).thenReturn(defaultResult());
    when(caseProjectionService.load(1234567890123456L)).thenReturn(buildSavedCaseDetails());

    service.submit(event, "Bearer token", idempotencyKey);

    verify(submitHandler).apply(event);
    verifyNoInteractions(legacyHandler, jsonDefinitionHandler);
  }

  @Test
  void submitUsesLegacyHandlerWhenSubmitHandlerIsNotConfigured() {
    setupTransactionExecution();
    final DecentralisedCaseEvent event = buildEvent();
    final UUID idempotencyKey = UUID.randomUUID();
    final Event eventConfig = mock(Event.class);
    final var user = new IdamService.User("Bearer token", null);

    ReflectionTestUtils.setField(service, "isLegacyJsonDefinition", false);
    when(eventConfig.getSubmitHandler()).thenReturn(null);
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("CASE_TYPE", "EVENT_ID");
    when(idamService.retrieveUser("Bearer token")).thenReturn(user);
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(idempotencyKey, 1234567890123456L))
        .thenReturn(Optional.empty());
    when(legacyHandler.apply(event)).thenReturn(defaultResult());
    when(caseProjectionService.load(1234567890123456L)).thenReturn(buildSavedCaseDetails());

    service.submit(event, "Bearer token", idempotencyKey);

    verify(legacyHandler).apply(event);
    verifyNoInteractions(submitHandler, jsonDefinitionHandler);
  }

  @Test
  void submitFailsWhenLegacyJsonServiceEnabledButJsonHandlerMissing() {
    final DecentralisedCaseEvent event = buildEvent();
    final UUID idempotencyKey = UUID.randomUUID();

    ReflectionTestUtils.setField(serviceWithoutJsonHandler, "isLegacyJsonDefinition", true);
    when(idamService.retrieveUser("Bearer token")).thenReturn(new IdamService.User("Bearer token", null));
    doReturn(mock(Event.class)).when(resolvedConfigRegistry).getRequiredEvent("CASE_TYPE", "EVENT_ID");

    assertThrows(IllegalStateException.class,
        () -> serviceWithoutJsonHandler.submit(event, "Bearer token", idempotencyKey));

    verifyNoInteractions(submitHandler, legacyHandler, jsonDefinitionHandler);
  }

  private void setupTransactionExecution() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    when(auditEventService.saveAuditRecord(any(), any(), any(), any())).thenReturn(111L);
  }

  private static CaseSubmissionHandler.CaseSubmissionHandlerResult defaultResult() {
    return new CaseSubmissionHandler.CaseSubmissionHandlerResult(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        () -> SubmitResponse.builder().build()
    );
  }

  private static DecentralisedCaseDetails buildSavedCaseDetails() {
    var saved = new DecentralisedCaseDetails();
    saved.setCaseDetails(new uk.gov.hmcts.ccd.domain.model.definition.CaseDetails());
    return saved;
  }

  private static DecentralisedCaseEvent buildEvent() {
    var caseDetails = new uk.gov.hmcts.ccd.domain.model.definition.CaseDetails();
    caseDetails.setReference(1234567890123456L);
    caseDetails.setCaseTypeId("CASE_TYPE");
    caseDetails.setJurisdiction("TEST");
    caseDetails.setState("Draft");
    caseDetails.setVersion(1);
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);
    caseDetails.setData(Map.of("field", TextNode.valueOf("value")));

    var eventDetails = DecentralisedEventDetails.builder()
        .caseType("CASE_TYPE")
        .eventId("EVENT_ID")
        .build();

    return DecentralisedCaseEvent.builder()
        .internalCaseId(111L)
        .caseDetails(caseDetails)
        .eventDetails(eventDetails)
        .build();
  }
}
