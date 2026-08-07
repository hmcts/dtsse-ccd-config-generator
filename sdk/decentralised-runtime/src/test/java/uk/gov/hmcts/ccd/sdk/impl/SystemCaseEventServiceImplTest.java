package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseDetails;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.SystemCaseEvent;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventActor;
import uk.gov.hmcts.ccd.sdk.SystemCaseEventOutcome;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class SystemCaseEventServiceImplTest {

  private static final long CASE_REFERENCE = 1234567890123456L;
  private static final UUID IDEMPOTENCY_KEY = UUID.randomUUID();

  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final IdempotencyEnforcer idempotencyEnforcer = mock(IdempotencyEnforcer.class);
  private final CaseDataRepository caseDataRepository = mock(CaseDataRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final ResolvedConfigRegistry configRegistry = mock(ResolvedConfigRegistry.class);
  private final ResolvedCCDConfig<?, ?, ?> config = mock(ResolvedCCDConfig.class);

  private final SystemCaseEventServiceImpl service = new SystemCaseEventServiceImpl(
      "pcs-api",
      transactionTemplate,
      idempotencyEnforcer,
      provider(caseDataRepository),
      provider(auditEventService),
      provider(configRegistry),
      new ObjectMapper()
  );

  @BeforeEach
  void setUp() {
    when(transactionTemplate.execute(any())).thenAnswer(invocation ->
        invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null)
    );
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void recordsSystemEventWithTypedRawCaseContext() {
    givenNewEvent();

    var result = service.submit(
        CASE_REFERENCE,
        new SystemCaseEvent("documentGenerated", "Document generated"),
        IDEMPOTENCY_KEY,
        context -> {
          assertThat(context.caseReference()).isEqualTo(CASE_REFERENCE);
          assertThat(context.caseData()).isEqualTo(new TestCaseData("raw value"));
          assertThat(context.state()).isEqualTo(TestState.SUBMITTED);
          return new SystemCaseEventOutcome<>(
              Optional.of(TestState.COMPLETE),
              Optional.of("Generated"),
              Optional.of("The document was attached")
          );
        }
    );

    assertThat(result.caseReference()).isEqualTo(CASE_REFERENCE);
    assertThat(result.eventInstanceId()).isEqualTo(42L);
    assertThat(result.replayed()).isFalse();

    ArgumentCaptor<DecentralisedCaseEvent> eventCaptor = ArgumentCaptor.forClass(DecentralisedCaseEvent.class);
    ArgumentCaptor<IdamService.User> userCaptor = ArgumentCaptor.forClass(IdamService.User.class);
    verify(auditEventService).saveSystemAuditRecord(
        eq(42L),
        eventCaptor.capture(),
        userCaptor.capture(),
        any(CaseDetails.class),
        eq(IDEMPOTENCY_KEY)
    );

    DecentralisedCaseEvent savedEvent = eventCaptor.getValue();
    assertThat(savedEvent.getCaseDetailsBefore().getState()).isEqualTo("SUBMITTED");
    assertThat(savedEvent.getCaseDetails().getState()).isEqualTo("COMPLETE");
    assertThat(savedEvent.getEventDetails().getEventId()).isEqualTo("documentGenerated");
    assertThat(savedEvent.getEventDetails().getSummary()).isEqualTo("Generated");
    assertThat(savedEvent.getEventDetails().getDescription()).isEqualTo("The document was attached");
    assertThat(userCaptor.getValue().userDetails().getUid()).isEqualTo("system:pcs-api");
    assertThat(userCaptor.getValue().userDetails().getGivenName()).isEqualTo("System");
    assertThat(userCaptor.getValue().userDetails().getFamilyName()).isEqualTo("pcs-api");
  }

  @Test
  void attributesOnBehalfOfEventToIdamUserAndProxiesItBySystemService() {
    givenNewEvent();

    service.submitOnBehalfOf(
        CASE_REFERENCE,
        new SystemCaseEvent("partyLinked", "Party linked"),
        IDEMPOTENCY_KEY,
        new SystemCaseEventActor.IdamUser(
            new UserInfo("subject", "citizen-123", "Ada Lovelace", "Ada", "Lovelace", List.of("citizen"))
        ),
        context -> SystemCaseEventOutcome.noStateChange()
    );

    ArgumentCaptor<DecentralisedCaseEvent> eventCaptor = ArgumentCaptor.forClass(DecentralisedCaseEvent.class);
    verify(auditEventService).saveSystemAuditRecord(
        eq(42L),
        eventCaptor.capture(),
        any(IdamService.User.class),
        any(CaseDetails.class),
        eq(IDEMPOTENCY_KEY)
    );
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedBy()).isEqualTo("citizen-123");
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByFirstName()).isEqualTo("Ada");
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByLastName()).isEqualTo("Lovelace");
  }

  @Test
  void attributesOnBehalfOfEventToCallingServiceAndProxiesItBySystemService() {
    givenNewEvent();

    service.submitOnBehalfOf(
        CASE_REFERENCE,
        new SystemCaseEvent("paymentUpdated", "Payment updated"),
        IDEMPOTENCY_KEY,
        new SystemCaseEventActor.Service("payment_app"),
        context -> SystemCaseEventOutcome.noStateChange()
    );

    ArgumentCaptor<DecentralisedCaseEvent> eventCaptor = ArgumentCaptor.forClass(DecentralisedCaseEvent.class);
    verify(auditEventService).saveSystemAuditRecord(
        eq(42L),
        eventCaptor.capture(),
        any(IdamService.User.class),
        any(CaseDetails.class),
        eq(IDEMPOTENCY_KEY)
    );
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedBy()).isEqualTo("system:payment_app");
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByFirstName()).isEqualTo("System");
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByLastName()).isEqualTo("payment_app");
  }

  @Test
  void fallsBackToIdamDisplayNameWhenStructuredNamesAreMissing() {
    givenNewEvent();

    service.submitOnBehalfOf(
        CASE_REFERENCE,
        new SystemCaseEvent("partyLinked", "Party linked"),
        IDEMPOTENCY_KEY,
        new SystemCaseEventActor.IdamUser(
            new UserInfo("subject", "citizen-123", "Ada Lovelace", null, null, List.of("citizen"))
        ),
        context -> SystemCaseEventOutcome.noStateChange()
    );

    ArgumentCaptor<DecentralisedCaseEvent> eventCaptor = ArgumentCaptor.forClass(DecentralisedCaseEvent.class);
    verify(auditEventService).saveSystemAuditRecord(
        eq(42L),
        eventCaptor.capture(),
        any(IdamService.User.class),
        any(CaseDetails.class),
        eq(IDEMPOTENCY_KEY)
    );
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByFirstName()).isEqualTo("Ada Lovelace");
    assertThat(eventCaptor.getValue().getEventDetails().getProxiedByLastName()).isEqualTo("Ada Lovelace");
  }

  @Test
  void rejectsIdamActorWithoutAUserId() {
    var actor = new SystemCaseEventActor.IdamUser(
        new UserInfo("subject", null, "Ada Lovelace", "Ada", "Lovelace", List.of("citizen"))
    );

    assertThatThrownBy(() -> service.submitOnBehalfOf(
        CASE_REFERENCE,
        new SystemCaseEvent("partyLinked", "Party linked"),
        IDEMPOTENCY_KEY,
        actor,
        context -> SystemCaseEventOutcome.noStateChange()
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("On-behalf-of IDAM user ID is required");

    verify(transactionTemplate, never()).execute(any());
  }

  @Test
  void rejectsServiceActorWithoutAServiceId() {
    assertThatThrownBy(() -> service.submitOnBehalfOf(
        CASE_REFERENCE,
        new SystemCaseEvent("paymentUpdated", "Payment updated"),
        IDEMPOTENCY_KEY,
        new SystemCaseEventActor.Service(" "),
        context -> SystemCaseEventOutcome.noStateChange()
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("On-behalf-of service ID is required");

    verify(transactionTemplate, never()).execute(any());
  }

  @Test
  void returnsReplayWithoutExecutingActionOrWritingAgain() {
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(IDEMPOTENCY_KEY, CASE_REFERENCE))
        .thenReturn(Optional.of(99L));

    var result = service.submit(
        CASE_REFERENCE,
        new SystemCaseEvent("documentGenerated", "Document generated"),
        IDEMPOTENCY_KEY,
        context -> {
          throw new AssertionError("action must not run on replay");
        }
    );

    assertThat(result).isEqualTo(new uk.gov.hmcts.ccd.sdk.SystemCaseEventResult(CASE_REFERENCE, 99L, true));
    verifyNoInteractions(caseDataRepository, auditEventService, configRegistry);
  }

  @Test
  void rejectsSubmissionFromExistingTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.submit(
        CASE_REFERENCE,
        new SystemCaseEvent("documentGenerated", "Document generated"),
        IDEMPOTENCY_KEY,
        context -> SystemCaseEventOutcome.noStateChange()
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("System case events cannot be submitted from an existing transaction");

    verify(transactionTemplate, never()).execute(any());
  }

  @Test
  void rollsBackWhenActionDoesNotReturnAnOutcome() {
    givenNewEvent();

    assertThatThrownBy(() -> service.submit(
        CASE_REFERENCE,
        new SystemCaseEvent("documentGenerated", "Document generated"),
        IDEMPOTENCY_KEY,
        context -> null
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("System case event action must return an outcome");

    verify(caseDataRepository, never()).upsertCase(any(), any());
    verify(auditEventService).reserveCaseEventId();
    verify(auditEventService, never()).saveSystemAuditRecord(anyLong(), any(), any(), any(), any());
  }

  private void givenNewEvent() {
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(IDEMPOTENCY_KEY, CASE_REFERENCE))
        .thenReturn(Optional.empty());
    when(caseDataRepository.getCase(CASE_REFERENCE)).thenReturn(caseDetails());
    doReturn(config).when(configRegistry).getRequired("TestCase");
    when(config.getCaseClass()).thenReturn((Class) TestCaseData.class);
    when(config.getStateClass()).thenReturn((Class) TestState.class);
    when(auditEventService.reserveCaseEventId()).thenReturn(42L);
  }

  private DecentralisedCaseDetails caseDetails() {
    var caseDetails = new CaseDetails();
    caseDetails.setId("123");
    caseDetails.setReference(CASE_REFERENCE);
    caseDetails.setJurisdiction("TEST");
    caseDetails.setCaseTypeId("TestCase");
    caseDetails.setState("SUBMITTED");
    caseDetails.setVersion(3);
    caseDetails.setRevision(2L);
    caseDetails.setSecurityClassification(SecurityClassification.PUBLIC);
    caseDetails.setData(new ObjectMapper().convertValue(new TestCaseData("raw value"), Map.class));

    var result = new DecentralisedCaseDetails();
    result.setCaseDetails(caseDetails);
    return result;
  }

  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(value);
    return provider;
  }

  private record TestCaseData(String value) {
  }

  private enum TestState {
    SUBMITTED,
    COMPLETE
  }
}
