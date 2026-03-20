package uk.gov.hmcts.ccd.sdk.impl;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.CallbackHandler;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CallbackDispatchServiceTest {

  @Test
  void dispatchToHandlersAboutToSubmitReturnsHandlerResponse() {
    CallbackResponse<?> expected = mock(CallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", expected, null, true, false, false, 0)
    ));

    var result = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));

    assertThat(result.handled()).isTrue();
    assertThat(result.response()).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersSubmittedReturnsHandlerResponse() {
    SubmittedCallbackResponse expected = mock(SubmittedCallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", null, expected, false, true, false, 0)
    ));

    var result = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    assertThat(result.handled()).isTrue();
    assertThat(result.response()).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersReturnsNoHandlerWhenNoBindingMatches() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("OTHER_CASE_TYPE", "DIFFERENT_EVENT",
            mock(CallbackResponse.class), mock(SubmittedCallbackResponse.class),
            true, true, false, 0)
    ));

    var aboutToSubmit = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));
    var submitted = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));
    assertThat(aboutToSubmit.handled()).isFalse();
    assertThat(aboutToSubmit.response()).isNull();
    assertThat(submitted.handled()).isFalse();
    assertThat(submitted.response()).isNull();
  }

  @Test
  void dispatchToHandlersAboutToSubmitMatchesAllConfiguredBindingCombinations() {
    CallbackResponse<?> expected = mock(CallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler(
            List.of("CASE_TYPE_A", "CASE_TYPE_B"),
            List.of("EVENT_A", "EVENT_B"),
            expected,
            null,
            true,
            false,
            false,
            0
        )
    ));

    var result = service.dispatchToHandlersAboutToSubmit(buildRequest("CASE_TYPE_B", "EVENT_A"));

    assertThat(result.handled()).isTrue();
    assertThat(result.response()).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersReturnsNoHandlerWhenHandlerDoesNotAcceptCallbacks() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", mock(CallbackResponse.class), mock(SubmittedCallbackResponse.class),
            false, false, false, 0)
    ));

    assertThat(service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID")).handled()).isFalse();
    assertThat(service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID")).handled()).isFalse();
  }

  @Test
  void dispatchToHandlersSubmittedRetriesUntilSuccess() {
    SubmittedCallbackResponse expected = mock(SubmittedCallbackResponse.class);
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", null, expected, false, true, true, 2)
    ));

    var result = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));
    assertThat(result.handled()).isTrue();
    assertThat(result.response()).isSameAs(expected);
  }

  @Test
  void dispatchToHandlersSubmittedThrowsWhenRetriesExhausted() {
    CallbackDispatchService service = new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", null, mock(SubmittedCallbackResponse.class),
            false, true, true, Integer.MAX_VALUE)
    ));

    assertThatThrownBy(() -> service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Submitted callback failed after 3 attempt(s)")
        .hasMessageContaining("caseType=CASE_TYPE")
        .hasMessageContaining("eventId=EVENT_ID")
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void constructorFailsFastOnDuplicateBindings() {
    assertThatThrownBy(() -> new CallbackDispatchService(List.of(
        new TestHandler("CASE_TYPE", "EVENT_ID", mock(CallbackResponse.class), null, true, false, false, 0),
        new TestHandler("CASE_TYPE", "EVENT_ID", mock(CallbackResponse.class), null, true, false, false, 0)
    )).dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate aboutToSubmit callback binding");
  }

  private static CallbackRequest buildRequest(String eventId) {
    return buildRequest("CASE_TYPE", eventId);
  }

  private static CallbackRequest buildRequest(String caseTypeId, String eventId) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder().id(123L).caseTypeId(caseTypeId).build())
        .build();
  }

  private static final class TestHandler implements CallbackHandler {

    private final List<String> caseTypeIds;
    private final List<String> eventIds;
    private final CallbackResponse<?> aboutToSubmitResponse;
    private final SubmittedCallbackResponse submittedResponse;
    private final boolean acceptsAboutToSubmit;
    private final boolean acceptsSubmitted;
    private final boolean retrySubmitted;
    private final int submittedFailuresBeforeSuccess;
    private final AtomicInteger submittedAttempts = new AtomicInteger(0);

    private TestHandler(String caseTypeId,
                        String eventId,
                        CallbackResponse<?> aboutToSubmitResponse,
                        SubmittedCallbackResponse submittedResponse,
                        boolean acceptsAboutToSubmit,
                        boolean acceptsSubmitted,
                        boolean retrySubmitted,
                        int submittedFailuresBeforeSuccess) {
      this(
          List.of(caseTypeId),
          List.of(eventId),
          aboutToSubmitResponse,
          submittedResponse,
          acceptsAboutToSubmit,
          acceptsSubmitted,
          retrySubmitted,
          submittedFailuresBeforeSuccess
      );
    }

    private TestHandler(List<String> caseTypeIds,
                        List<String> eventIds,
                        CallbackResponse<?> aboutToSubmitResponse,
                        SubmittedCallbackResponse submittedResponse,
                        boolean acceptsAboutToSubmit,
                        boolean acceptsSubmitted,
                        boolean retrySubmitted,
                        int submittedFailuresBeforeSuccess) {
      this.caseTypeIds = caseTypeIds;
      this.eventIds = eventIds;
      this.aboutToSubmitResponse = aboutToSubmitResponse;
      this.submittedResponse = submittedResponse;
      this.acceptsAboutToSubmit = acceptsAboutToSubmit;
      this.acceptsSubmitted = acceptsSubmitted;
      this.retrySubmitted = retrySubmitted;
      this.submittedFailuresBeforeSuccess = submittedFailuresBeforeSuccess;
    }

    @Override
    public List<String> getHandledCaseTypeIds() {
      return caseTypeIds;
    }

    @Override
    public List<String> getHandledEventIds() {
      return eventIds;
    }

    @Override
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest) {
      return aboutToSubmitResponse;
    }

    @Override
    public SubmittedCallbackResponse submitted(CallbackRequest callbackRequest) {
      int attempt = submittedAttempts.incrementAndGet();
      if (attempt <= submittedFailuresBeforeSuccess) {
        throw new RuntimeException("submitted callback failure");
      }
      return submittedResponse;
    }

    @Override
    public boolean acceptsAboutToSubmit() {
      return acceptsAboutToSubmit;
    }

    @Override
    public boolean acceptsSubmitted() {
      return acceptsSubmitted;
    }

    @Override
    public boolean shouldRetrySubmitted() {
      return retrySubmitted;
    }
  }
}
