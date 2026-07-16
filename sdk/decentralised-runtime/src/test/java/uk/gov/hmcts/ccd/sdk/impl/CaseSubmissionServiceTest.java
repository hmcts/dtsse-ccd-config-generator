package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
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

  @Test
  void restoresIncomingTtlWhenLegacyCallbackOmitsIt() {
    DecentralisedCaseEvent event = event();
    event.getCaseDetails().setData(dataWithTtl("2030-01-01"));
    prepareSubmission(event, false);
    when(legacyHandler.apply(eq(event), eq("Bearer raw-token"))).thenAnswer(invocation -> {
      event.getCaseDetails().setData(Map.of());
      return handlerResult();
    });

    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    assertThat(event.getCaseDetails().getData().get("TTL").get("SystemTTL").asText())
        .isEqualTo("2030-01-01");
    verify(caseDataRepository).upsertCase(event, Optional.empty());
  }

  @Test
  void ignoresLegacyCallbackChangesToIncomingTtl() {
    DecentralisedCaseEvent event = event();
    event.getCaseDetails().setData(dataWithTtl("2030-01-01"));
    final JsonNode authoritativeTtl = event.getCaseDetails().getData().get("TTL");
    prepareSubmission(event, false);
    when(legacyHandler.apply(eq(event), eq("Bearer raw-token"))).thenAnswer(invocation -> {
      event.getCaseDetails().setData(dataWithTtl("2031-01-01"));
      return handlerResult();
    });

    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    assertThat(event.getCaseDetails().getData().get("TTL").get("SystemTTL").asText())
        .isEqualTo("2030-01-01");
    assertThat(event.getCaseDetails().getData().get("TTL")).isSameAs(authoritativeTtl);
    verify(caseDataRepository).upsertCase(event, Optional.empty());
  }

  @Test
  void restoresIncomingTtlAfterSubmitHandler() {
    DecentralisedCaseEvent event = event();
    event.getCaseDetails().setData(dataWithTtl("2030-01-01"));
    prepareSubmission(event, true);
    when(submitHandler.apply(eq(event), eq("Bearer raw-token"))).thenAnswer(invocation -> {
      event.getCaseDetails().setData(dataWithTtl("2031-01-01"));
      return handlerResult();
    });

    service.submit(event, "raw-token", IDEMPOTENCY_KEY);

    assertThat(event.getCaseDetails().getData().get("TTL").get("SystemTTL").asText())
        .isEqualTo("2030-01-01");
    verify(caseDataRepository).upsertCase(event, Optional.empty());
  }

  private void prepareSubmission(DecentralisedCaseEvent event, boolean useSubmitHandler) {
    Event<?, ?, ?> eventConfig = mock(Event.class);
    doReturn(eventConfig).when(resolvedConfigRegistry).getRequiredEvent("TestCase", "submit");
    doReturn(useSubmitHandler ? mock(Submit.class) : null).when(eventConfig).getSubmitHandler();
    when(idam.retrieveUser("raw-token")).thenReturn(new IdamService.User(
        "Bearer raw-token",
        new UserInfo("sub", "uid", "name", "given", "family", List.of("caseworker"))
    ));
    when(idempotencyEnforcer.lockCaseAndGetExistingEvent(IDEMPOTENCY_KEY, 123456789L))
        .thenReturn(Optional.empty());
    when(caseProjectionService.load(123456789L)).thenReturn(savedCaseDetails());
    when(transactionTemplate.execute(any())).thenAnswer(invocation ->
        invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null)
    );
  }

  private Map<String, JsonNode> dataWithTtl(String systemTtl) {
    var ttl = new ObjectMapper().createObjectNode();
    ttl.put("SystemTTL", systemTtl);
    return Map.of("TTL", ttl);
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
