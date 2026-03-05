package uk.gov.hmcts.ccd.sdk.impl;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.CallbackHandlerBean;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.Retries;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CallbackDispatchServiceTest {

  @Test
  void dispatchToHandlersAboutToSubmitReturnsHandlerResponse() {
    CallbackResponse<?> expected = mock(CallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("EVENT_ID", expected, null)
    ));

    CallbackResponse<?> response = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));

    assertThat(response).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersSubmittedReturnsHandlerResponse() {
    SubmittedCallbackResponse expected = mock(SubmittedCallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("EVENT_ID", null, expected)
    ));

    SubmittedCallbackResponse response = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    assertThat(response).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersReturnsNullWhenNoHandlerMatches() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("DIFFERENT_EVENT", mock(CallbackResponse.class), mock(SubmittedCallbackResponse.class))
    ));

    assertThat(service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"))).isNull();
    assertThat(service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"))).isNull();
  }

  @Test
  void resolveSubmittedRetriesReturnsDefaultWhenAnnotationMissing() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("EVENT_ID", null, null)
    ));

    assertThat(service.resolveSubmittedRetries("EVENT_ID")).isEqualTo(1);
  }

  @Test
  void resolveSubmittedRetriesReturnsAnnotatedValueWhenPresent() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new RetriesHandler("EVENT_ID")
    ));

    assertThat(service.resolveSubmittedRetries("EVENT_ID")).isEqualTo(3);
  }

  @Test
  void resolveSubmittedRetriesThrowsWhenRetriesAnnotationPlacedOnWrongMethod() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new InvalidRetriesPlacementHandler("EVENT_ID")
    ));

    assertThatThrownBy(() -> service.resolveSubmittedRetries("EVENT_ID"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@Retries is only supported on CallbackHandlerBean#submitted implementations");
  }

  @Test
  void resolveSubmittedRetriesThrowsWhenAnnotationValueIsNotPositive() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new InvalidRetriesValueHandler("EVENT_ID")
    ));

    assertThatThrownBy(() -> service.resolveSubmittedRetries("EVENT_ID"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("@Retries value must be greater than 0");
  }

  private static CallbackRequest buildRequest(String eventId) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder().id(123L).caseTypeId("CASE_TYPE").build())
        .build();
  }

  private static final class TestHandler implements CallbackHandlerBean {

    private final String eventId;
    private final CallbackResponse<?> aboutToSubmitResponse;
    private final SubmittedCallbackResponse submittedResponse;

    private TestHandler(String eventId, CallbackResponse<?> aboutToSubmitResponse,
                        SubmittedCallbackResponse submittedResponse) {
      this.eventId = eventId;
      this.aboutToSubmitResponse = aboutToSubmitResponse;
      this.submittedResponse = submittedResponse;
    }

    @Override
    public String getHandledEventId() {
      return eventId;
    }

    @Override
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest) {
      return aboutToSubmitResponse;
    }

    @Override
    public SubmittedCallbackResponse submitted(CallbackRequest callbackRequest) {
      return submittedResponse;
    }
  }

  private static final class RetriesHandler implements CallbackHandlerBean {

    private final String eventId;

    private RetriesHandler(String eventId) {
      this.eventId = eventId;
    }

    @Override
    public String getHandledEventId() {
      return eventId;
    }

    @Override
    @Retries(3)
    public SubmittedCallbackResponse submitted(CallbackRequest callbackRequest) {
      return null;
    }
  }

  private static final class InvalidRetriesPlacementHandler implements CallbackHandlerBean {

    private final String eventId;

    private InvalidRetriesPlacementHandler(String eventId) {
      this.eventId = eventId;
    }

    @Override
    public String getHandledEventId() {
      return eventId;
    }

    @Override
    @Retries(2)
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest) {
      return null;
    }
  }

  private static final class InvalidRetriesValueHandler implements CallbackHandlerBean {

    private final String eventId;

    private InvalidRetriesValueHandler(String eventId) {
      this.eventId = eventId;
    }

    @Override
    public String getHandledEventId() {
      return eventId;
    }

    @Override
    @Retries(0)
    public SubmittedCallbackResponse submitted(CallbackRequest callbackRequest) {
      return null;
    }
  }
}
