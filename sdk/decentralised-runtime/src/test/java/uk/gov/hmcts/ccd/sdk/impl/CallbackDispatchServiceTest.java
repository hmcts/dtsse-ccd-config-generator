package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class CallbackDispatchServiceTest {

  @Test
  void dispatchToHandlersAboutToSubmitReturnsHandlerResponse() {
    TestCallbackResponse expected = new TestCallbackResponse();
    TestDispatchController controller = new TestDispatchController(expected, new TestSubmittedCallbackResponse(), 0);

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/callbacks/aboutToSubmit",
            null,
            null
        ))),
        controller
    );

    var result = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));

    Assertions.assertThat(result.handled()).isTrue();
    Assertions.assertThat(result.response()).isSameAs(expected);
    Assertions.assertThat(controller.aboutToSubmitCalls.get()).isEqualTo(1);
  }

  @Test
  void dispatchToHandlersSubmittedReturnsHandlerResponse() {
    TestSubmittedCallbackResponse expected = new TestSubmittedCallbackResponse();
    TestDispatchController controller = new TestDispatchController(new TestCallbackResponse(), expected, 0);

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/callbacks/submitted",
            null
        ))),
        controller
    );

    var result = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    Assertions.assertThat(result.handled()).isTrue();
    Assertions.assertThat(result.response()).isSameAs(expected);
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(1);
  }

  @Test
  void dispatchToHandlersReturnsNoHandlerWhenNoBindingMatches() {
    TestDispatchController controller = new TestDispatchController(
        new TestCallbackResponse(),
        new TestSubmittedCallbackResponse(),
        0
    );

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "DIFFERENT_EVENT",
            "${BASE_URL}/callbacks/aboutToSubmit",
            "${BASE_URL}/callbacks/submitted",
            null
        ))),
        controller
    );

    var aboutToSubmit = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));
    var submitted = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    Assertions.assertThat(aboutToSubmit.handled()).isFalse();
    Assertions.assertThat(aboutToSubmit.response()).isNull();
    Assertions.assertThat(submitted.handled()).isFalse();
    Assertions.assertThat(submitted.response()).isNull();
  }

  @Test
  void dispatchToHandlersSubmittedRetriesUntilSuccess() {
    TestSubmittedCallbackResponse expected = new TestSubmittedCallbackResponse();
    TestDispatchController controller = new TestDispatchController(new TestCallbackResponse(), expected, 2);

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/callbacks/submitted",
            List.of(1, 2)
        ))),
        controller
    );

    var result = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    Assertions.assertThat(result.handled()).isTrue();
    Assertions.assertThat(result.response()).isSameAs(expected);
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(3);
  }

  @Test
  void dispatchToHandlersSubmittedThrowsWhenRetriesExhausted() {
    TestDispatchController controller = new TestDispatchController(
        new TestCallbackResponse(),
        new TestSubmittedCallbackResponse(),
        Integer.MAX_VALUE
    );

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/callbacks/submitted",
            List.of(1, 2)
        ))),
        controller
    );

    Assertions.assertThatThrownBy(() -> service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Submitted callback failed after 3 attempt(s)")
        .hasMessageContaining("caseType=CASE_TYPE")
        .hasMessageContaining("eventId=EVENT_ID")
        .hasCauseInstanceOf(RuntimeException.class);
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(3);
  }

  @Test
  void dispatchToHandlersSubmittedDoesNotRetryWhenSingleRetryTimeoutConfigured() {
    TestDispatchController controller = new TestDispatchController(
        new TestCallbackResponse(),
        new TestSubmittedCallbackResponse(),
        Integer.MAX_VALUE
    );

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/callbacks/submitted",
            List.of(1)
        ))),
        controller
    );

    Assertions.assertThatThrownBy(() -> service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Submitted callback failed after 1 attempt(s)");
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(1);
  }

  @Test
  void createDispatchMapResolvesEveryBindingUsingClassAndMethodPaths() {
    TestCallbackResponse aboutResponse = new TestCallbackResponse();
    TestSubmittedCallbackResponse submittedResponse = new TestSubmittedCallbackResponse();
    MultiPathDispatchController controller = new MultiPathDispatchController(aboutResponse, submittedResponse);

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${CCD_DEF_CASE_SERVICE_BASE_URL}/callbacks/downloadDraft/aboutToSubmit?eventId=EVENT_ID",
            "${CCD_DEF_CASE_SERVICE_BASE_URL}/legacy/callbacks/downloadDraft/submitted",
            null
        ))),
        controller
    );

    var aboutToSubmitResult = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));
    var submittedResult = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    Assertions.assertThat(aboutToSubmitResult.handled()).isTrue();
    Assertions.assertThat(aboutToSubmitResult.response()).isSameAs(aboutResponse);
    Assertions.assertThat(submittedResult.handled()).isTrue();
    Assertions.assertThat(submittedResult.response()).isSameAs(submittedResponse);
    Assertions.assertThat(controller.aboutToSubmitCalls.get()).isEqualTo(1);
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(1);
    Assertions.assertThat(controller.lastAboutToSubmitToken).isNull();
    Assertions.assertThat(controller.lastSubmittedToken).isNull();
  }

  @Test
  void createDispatchMapFailsWhenNoControllerEndpointMatchesDefinitionUrl() {
    TestDispatchController controller = new TestDispatchController(
        new TestCallbackResponse(),
        new TestSubmittedCallbackResponse(),
        0
    );

    Assertions.assertThatThrownBy(() -> createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/missing/about-to-submit",
            null,
            null
        ))),
        controller
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No callback controller method found")
        .hasMessageContaining("CASE_TYPE")
        .hasMessageContaining("EVENT_ID")
        .hasMessageContaining("/missing/about-to-submit");
  }

  @Test
  void createDispatchMapFailsFastOnDuplicateBindings() {
    TestDispatchController controller = new TestDispatchController(
        new TestCallbackResponse(),
        new TestSubmittedCallbackResponse(),
        0
    );

    Assertions.assertThatThrownBy(() -> createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(
            event("EVENT_ID", "${BASE_URL}/callbacks/aboutToSubmit", null, null),
            event("EVENT_ID", "${BASE_URL}/callbacks/altAboutToSubmit", null, null)
        )),
        controller
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Multiple Bindings found")
        .hasMessageContaining("callback type aboutToSubmit");
  }

  @Test
  void dispatchSupportsLegacyCcdRequestControllersReturningResponseEntity() {
    LegacyDispatchController controller = new LegacyDispatchController();

    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/legacy/aboutToSubmit",
            "${BASE_URL}/legacy/submitted",
            null
        ))),
        controller
    );

    var aboutToSubmit = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"));
    var submitted = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"));

    Assertions.assertThat(aboutToSubmit.handled()).isTrue();
    Assertions.assertThat(((LegacyCallbackResponse) aboutToSubmit.response()).getData().getValue())
        .isEqualTo("EVENT_ID");
    Assertions.assertThat(submitted.handled()).isTrue();
    Assertions.assertThat(((LegacyCallbackResponse) submitted.response()).getConfirmationHeader())
        .isEqualTo("done");
  }

  private CallbackDispatchService createInitialisedService(
      Map<String, CaseTypeDefinition> definitions,
      Object... controllers
  ) {
    DefinitionRegistry definitionRegistry = Mockito.mock(DefinitionRegistry.class);
    Mockito.when(definitionRegistry.loadDefinitions()).thenReturn(definitions);

    ListableBeanFactory beanFactory = Mockito.mock(ListableBeanFactory.class);
    Map<String, Object> restControllers = new LinkedHashMap<>();
    for (int i = 0; i < controllers.length; i++) {
      restControllers.put("controller-" + i, controllers[i]);
    }

    Mockito.when(beanFactory.getBeansWithAnnotation(RestController.class)).thenReturn(restControllers);
    Mockito.when(beanFactory.getBeansWithAnnotation(Controller.class)).thenReturn(Map.of());

    CallbackDispatchService service = new CallbackDispatchService(definitionRegistry, beanFactory, new ObjectMapper());
    service.initialiseHandlerMaps();
    return service;
  }

  private static CaseTypeDefinition definitionForEvents(CaseEventDefinition... events) {
    CaseTypeDefinition definition = new CaseTypeDefinition();
    definition.setEvents(List.of(events));
    return definition;
  }

  private static CaseEventDefinition event(
      String eventId,
      String aboutToSubmitUrl,
      String submittedUrl,
      List<Integer> submittedRetries
  ) {
    CaseEventDefinition event = new CaseEventDefinition();
    event.setId(eventId);
    event.setCallBackURLAboutToSubmitEvent(aboutToSubmitUrl);
    event.setCallBackURLSubmittedEvent(submittedUrl);
    if (submittedRetries != null) {
      event.setRetriesTimeoutURLSubmittedEvent(submittedRetries);
    }
    return event;
  }

  private static CallbackRequest buildRequest(String eventId) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder().id(123L).caseTypeId("CASE_TYPE").build())
        .build();
  }

  @RestController
  @RequestMapping("/callbacks")
  private static final class TestDispatchController {
    private final CallbackResponse<?> aboutToSubmitResponse;
    private final TestSubmittedCallbackResponse submittedResponse;
    private final AtomicInteger submittedFailuresRemaining;
    private final AtomicInteger aboutToSubmitCalls = new AtomicInteger();
    private final AtomicInteger submittedCalls = new AtomicInteger();

    private TestDispatchController(
        CallbackResponse<?> aboutToSubmitResponse,
        TestSubmittedCallbackResponse submittedResponse,
        int submittedFailuresBeforeSuccess
    ) {
      this.aboutToSubmitResponse = aboutToSubmitResponse;
      this.submittedResponse = submittedResponse;
      this.submittedFailuresRemaining = new AtomicInteger(submittedFailuresBeforeSuccess);
    }

    @PostMapping("/aboutToSubmit")
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      aboutToSubmitCalls.incrementAndGet();
      return aboutToSubmitResponse;
    }

    @PostMapping("/submitted")
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      submittedCalls.incrementAndGet();
      if (submittedFailuresRemaining.getAndDecrement() > 0) {
        throw new RuntimeException("submitted callback failure");
      }
      return submittedResponse;
    }
  }

  @RestController
  @RequestMapping(path = {"/callbacks", "/legacy/callbacks"})
  private static final class MultiPathDispatchController {
    private final CallbackResponse<?> aboutToSubmitResponse;
    private final CallbackResponse<?> submittedResponse;
    private final AtomicInteger aboutToSubmitCalls = new AtomicInteger();
    private final AtomicInteger submittedCalls = new AtomicInteger();
    private String lastAboutToSubmitToken;
    private String lastSubmittedToken;

    private MultiPathDispatchController(
        CallbackResponse<?> aboutToSubmitResponse,
        CallbackResponse<?> submittedResponse
    ) {
      this.aboutToSubmitResponse = aboutToSubmitResponse;
      this.submittedResponse = submittedResponse;
    }

    @PostMapping(path = {"/downloadDraft/aboutToSubmit", "/aboutToSubmit"})
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      aboutToSubmitCalls.incrementAndGet();
      lastAboutToSubmitToken = authToken;
      return aboutToSubmitResponse;
    }

    @PostMapping(path = {"/downloadDraft/submitted", "/submitted"})
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      submittedCalls.incrementAndGet();
      lastSubmittedToken = authToken;
      return submittedResponse;
    }
  }

  private static class TestCallbackResponse implements CallbackResponse<Object> {

    @Override
    public Object getData() {
      return null;
    }

    @Override
    public List<String> getErrors() {
      return null;
    }

    @Override
    public List<String> getWarnings() {
      return null;
    }

    @Override
    public String getState() {
      return null;
    }

    @Override
    public Map<String, Object> getDataClassification() {
      return null;
    }

    @Override
    public String getSecurityClassification() {
      return null;
    }

    @Override
    public String getErrorMessageOverride() {
      return null;
    }
  }

  private static final class TestSubmittedCallbackResponse extends TestCallbackResponse
      implements SubmittedCallbackResponse {

    @Override
    public String getConfirmationHeader() {
      return "header";
    }

    @Override
    public String getConfirmationBody() {
      return "body";
    }
  }

  @RestController
  @RequestMapping("/legacy")
  private static final class LegacyDispatchController {

    @PostMapping("/aboutToSubmit")
    public ResponseEntity<LegacyCallbackResponse> aboutToSubmit(CCDRequest request, String authToken) {
      return ResponseEntity.ok(new LegacyCallbackResponse(new LegacyCaseData(request.getEventId()), null, null));
    }

    @PostMapping("/submitted")
    public ResponseEntity<LegacyCallbackResponse> submitted(CCDRequest request, String authToken) {
      return ResponseEntity.ok(new LegacyCallbackResponse(null, "done", "body"));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class CCDRequest {
    private String eventId;

    @JsonProperty("event_id")
    public String getEventId() {
      return eventId;
    }

    @JsonProperty("event_id")
    public void setEventId(String eventId) {
      this.eventId = eventId;
    }
  }

  private static final class LegacyCaseData {
    private final String value;

    private LegacyCaseData(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  private static final class LegacyCallbackResponse implements CallbackResponse<LegacyCaseData>, SubmittedCallbackResponse {
    private final LegacyCaseData data;
    private final String confirmationHeader;
    private final String confirmationBody;

    private LegacyCallbackResponse(LegacyCaseData data, String confirmationHeader, String confirmationBody) {
      this.data = data;
      this.confirmationHeader = confirmationHeader;
      this.confirmationBody = confirmationBody;
    }

    @Override
    public LegacyCaseData getData() {
      return data;
    }

    @Override
    public List<String> getErrors() {
      return null;
    }

    @Override
    public List<String> getWarnings() {
      return null;
    }

    @Override
    public String getState() {
      return null;
    }

    @Override
    public Map<String, Object> getDataClassification() {
      return Map.of();
    }

    @Override
    public String getSecurityClassification() {
      return null;
    }

    @Override
    public String getErrorMessageOverride() {
      return null;
    }

    @Override
    public String getConfirmationHeader() {
      return confirmationHeader;
    }

    @Override
    public String getConfirmationBody() {
      return confirmationBody;
    }
  }
}
