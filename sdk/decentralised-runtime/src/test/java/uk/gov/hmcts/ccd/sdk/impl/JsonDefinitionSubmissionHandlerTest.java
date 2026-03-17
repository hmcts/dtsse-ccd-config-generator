package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.data.casedetails.SecurityClassification;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.Classification;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JsonDefinitionSubmissionHandlerTest {

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
    doReturn(callbackResponse).when(callbackDispatchService).dispatchToHandlersAboutToSubmit(any());

    assertThatThrownBy(() -> handler.apply(event))
        .isInstanceOf(CallbackValidationException.class)
        .satisfies(ex -> {
          CallbackValidationException callbackEx = (CallbackValidationException) ex;
          assertThat(callbackEx.getErrors()).containsExactly("validation-error");
          assertThat(callbackEx.getWarnings()).containsExactly("warning");
        });

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any());
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
    doReturn(callbackResponse).when(callbackDispatchService).dispatchToHandlersAboutToSubmit(any());
    when(callbackDispatchService.dispatchToHandlersSubmitted(any())).thenReturn(submittedCallbackResponse);
    when(submittedCallbackResponse.getConfirmationHeader()).thenReturn("Header");
    when(submittedCallbackResponse.getConfirmationBody()).thenReturn("Body");

    CaseSubmissionHandler.CaseSubmissionHandlerResult result = handler.apply(event);
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

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any());
    verify(callbackDispatchService).dispatchToHandlersSubmitted(any());
  }

  @Test
  void applyPropagatesSubmittedCallbackFailure() {
    JsonDefinitionSubmissionHandler handler = newHandler();
    DecentralisedCaseEvent event = buildEvent();

    when(callbackDispatchService.dispatchToHandlersSubmitted(any()))
        .thenThrow(new RuntimeException("submitted callback failure"));

    CaseSubmissionHandler.CaseSubmissionHandlerResult result = handler.apply(event);

    verify(callbackDispatchService).dispatchToHandlersAboutToSubmit(any());
    assertThatThrownBy(() -> result.responseSupplier().get())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("submitted callback failure");
    verify(callbackDispatchService).dispatchToHandlersSubmitted(any());
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
