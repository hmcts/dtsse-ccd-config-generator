package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedEventDetails;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;

class DecentralisedSubmissionHandlerTest {

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void applyPassesQueryParamsToSubmitHandlerPayload() {
    var registry = mock(ResolvedConfigRegistry.class);
    var httpRequest = mock(HttpServletRequest.class);
    var handler = new DecentralisedSubmissionHandler(registry, new ObjectMapper(), httpRequest);

    var event = mock(DecentralisedCaseEvent.class);
    var eventDetails = mock(DecentralisedEventDetails.class);
    var caseDetails = mock(CaseDetails.class);
    when(event.getEventDetails()).thenReturn(eventDetails);
    when(event.getCaseDetails()).thenReturn(caseDetails);
    when(eventDetails.getCaseType()).thenReturn("TestCaseType");
    when(eventDetails.getEventId()).thenReturn("submitEvent");
    when(caseDetails.getReference()).thenReturn(1234567890123456L);
    when(caseDetails.getData()).thenReturn(Map.of("field", JsonNodeFactory.instance.textNode("value")));

    when(httpRequest.getParameterMap()).thenReturn(Map.of(
        "user", new String[]{"alice"},
        "roles", new String[]{"caseworker", "citizen"}
    ));

    var eventConfig = mock(Event.class);
    var resolvedConfig = mock(ResolvedCCDConfig.class);
    var submitHandler = mock(Submit.class);

    when(registry.getRequiredEvent("TestCaseType", "submitEvent")).thenReturn(eventConfig);
    when(registry.getRequired("TestCaseType")).thenReturn(resolvedConfig);
    when(resolvedConfig.getCaseClass()).thenReturn(Map.class);
    when(eventConfig.getSubmitHandler()).thenReturn(submitHandler);
    when(submitHandler.submit(any())).thenReturn(SubmitResponse.defaultResponse());

    handler.apply(event);

    var payloadCaptor = ArgumentCaptor.forClass(EventPayload.class);
    verify(submitHandler).submit(payloadCaptor.capture());

    EventPayload<?, ?> payload = payloadCaptor.getValue();
    assertThat(payload.urlParams().get("user")).isEqualTo(java.util.List.of("alice"));
    assertThat(payload.urlParams().get("roles")).isEqualTo(java.util.List.of("caseworker", "citizen"));
    assertThat(payload.caseData()).isEqualTo(Map.of("field", "value"));
    assertThat(payload.caseReference()).isEqualTo(1234567890123456L);
  }
}
