package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

class JsonDefinitionSubmissionHandlerTest {

  private static final String AUTHORIZATION = "Bearer token";
  private final CallbackDispatchService callbackDispatchService = mock(CallbackDispatchService.class);

  @Test
  void applyThrowsCallbackValidationExceptionWhenAboutToSubmitReturnsErrors() {
    JsonDefinitionSubmissionHandler handler = newHandler();
    DecentralisedCaseEvent event = buildEvent();
    CallbackResponse<?> callbackResponse = mock(CallbackResponse.class);

    when(callbackResponse.getData()).thenReturn(Map.of("updated", "value"));
    when(callbackResponse.getState()).thenReturn("Submitted");
    when(callbackResponse.getSecurityClassification()).thenReturn("PUBLIC");
    when(callbackResponse.getErrors()).thenReturn(List.of("validation-error"));
    when(callbackResponse.getWarnings()).thenReturn(List.of("warning"));
    doReturn(new CallbackDispatchService.DispatchResult<>(true, callbackResponse))
        .when(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));

    assertThatThrownBy(() -> handler.apply(event, AUTHORIZATION))
        .isInstanceOf(CallbackValidationException.class)
        .satisfies(ex -> {
          CallbackValidationException callbackEx = (CallbackValidationException) ex;
          assertThat(callbackEx.getErrors()).containsExactly("validation-error");
          assertThat(callbackEx.getWarnings()).containsExactly("warning");
        });

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));
    verifyNoMoreInteractions(callbackDispatchService);
  }

  @Test
  void applyBuildsResponseFromCallbackAndSubmittedConfirmation() {
    JsonDefinitionSubmissionHandler handler = newHandler();
    DecentralisedCaseEvent event = buildEvent();
    CallbackResponse<?> callbackResponse = mock(CallbackResponse.class);
    SubmittedCallbackResponse submittedCallbackResponse = mock(SubmittedCallbackResponse.class);

    when(callbackResponse.getData()).thenReturn(Map.of("updatedField", "new-value"));
    when(callbackResponse.getState()).thenReturn("Validated");
    when(callbackResponse.getSecurityClassification()).thenReturn("PUBLIC");
    when(callbackResponse.getErrors()).thenReturn(List.of());
    when(callbackResponse.getWarnings()).thenReturn(List.of("warning-message"));
    doReturn(new CallbackDispatchService.DispatchResult<>(true, callbackResponse))
        .when(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));
    when(callbackDispatchService.dispatchToHandlersSubmitted(any(), eq(AUTHORIZATION)))
        .thenReturn(new CallbackDispatchService.DispatchResult<>(true, submittedCallbackResponse));
    when(submittedCallbackResponse.getConfirmationHeader()).thenReturn("Header");
    when(submittedCallbackResponse.getConfirmationBody()).thenReturn("Body");

    CaseSubmissionHandler.CaseSubmissionHandlerResult result = handler.apply(event, AUTHORIZATION);
    var submitResponse = result.responseSupplier().get();

    assertThat(event.getCaseDetails().getState()).isEqualTo("Validated");
    assertThat(event.getCaseDetails().getSecurityClassification()).isEqualTo(SecurityClassification.PUBLIC);
    assertThat(event.getCaseDetails().getData().get("updatedField")).isEqualTo(TextNode.valueOf("new-value"));
    assertThat(result.state()).contains("Validated");
    assertThat(result.securityClassification()).contains(Classification.PUBLIC);
    assertThat(submitResponse.getWarnings()).containsExactly("warning-message");
    assertThat(submitResponse.getConfirmationHeader()).isEqualTo("Header");
    assertThat(submitResponse.getConfirmationBody()).isEqualTo("Body");
    assertThat(submitResponse.getCaseSecurityClassification()).isEqualTo(Classification.PUBLIC);

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));
    verify(callbackDispatchService).dispatchToHandlersSubmitted(any(), eq(AUTHORIZATION));
  }

  @Test
  void applyPropagatesSubmittedCallbackFailure() {
    JsonDefinitionSubmissionHandler handler = newHandler();
    DecentralisedCaseEvent event = buildEvent();

    when(callbackDispatchService.dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION)))
        .thenReturn(new CallbackDispatchService.DispatchResult<>(false, null));
    when(callbackDispatchService.dispatchToHandlersSubmitted(any(), eq(AUTHORIZATION)))
        .thenThrow(new RuntimeException("submitted callback failure"));

    CaseSubmissionHandler.CaseSubmissionHandlerResult result = handler.apply(event, AUTHORIZATION);

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));
    assertThatThrownBy(() -> result.responseSupplier().get())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("submitted callback failure");
    verify(callbackDispatchService).dispatchToHandlersSubmitted(any(), eq(AUTHORIZATION));
  }

  @Test
  void applyPropagatesErrorsWithoutSecurityClassification() {
    JsonDefinitionSubmissionHandler handler = newHandler();
    DecentralisedCaseEvent event = buildEvent();
    CallbackResponse<?> callbackResponse = mock(CallbackResponse.class);

    when(callbackResponse.getData()).thenReturn(Map.of("updated", "value"));
    when(callbackResponse.getState()).thenReturn("Submitted");
    when(callbackResponse.getSecurityClassification()).thenReturn(null);
    when(callbackResponse.getErrors()).thenReturn(List.of("validation-error"));
    when(callbackResponse.getWarnings()).thenReturn(List.of("warning"));
    doReturn(new CallbackDispatchService.DispatchResult<>(true, callbackResponse))
        .when(callbackDispatchService).dispatchToHandlersAboutToSubmit(any(), eq(AUTHORIZATION));

    assertThatThrownBy(() -> handler.apply(event, AUTHORIZATION))
        .isInstanceOf(CallbackValidationException.class)
        .satisfies(ex -> {
          CallbackValidationException callbackEx = (CallbackValidationException) ex;
          assertThat(callbackEx.getErrors()).containsExactly("validation-error");
          assertThat(callbackEx.getWarnings()).containsExactly("warning");
        });
  }

  private JsonDefinitionSubmissionHandler newHandler() {
    return new JsonDefinitionSubmissionHandler(callbackDispatchService, new ObjectMapper());
  }

  private static DecentralisedCaseEvent buildEvent() {
    var caseDetails = new uk.gov.hmcts.ccd.domain.model.definition.CaseDetails();
    caseDetails.setReference(1234567890123456L);
    caseDetails.setCaseTypeId("CASE_TYPE");
    caseDetails.setJurisdiction("TEST");
    caseDetails.setState("Draft");
    caseDetails.setVersion(1);
    caseDetails.setSecurityClassification(SecurityClassification.PRIVATE);
    caseDetails.setData(Map.of("existingField", TextNode.valueOf("existing-value")));

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
