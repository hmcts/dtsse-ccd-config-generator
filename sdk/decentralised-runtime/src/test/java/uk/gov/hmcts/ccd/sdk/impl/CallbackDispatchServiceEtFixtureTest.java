package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ListableBeanFactory;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackDispatchServiceEtFixtureTest {

  private static final String FIXTURE_PATH = "/fixtures/et-callback-caseevents-subset.json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final Map<String, String> EXPECTED_ABOUT_TO_SUBMIT_LABELS = Map.ofEntries(
      Map.entry("tseAdmReply", "tseAdmReply.aboutToSubmit"),
      Map.entry("tseAdmin", "tseAdmin.aboutToSubmit"),
      Map.entry("tseAdminCloseAnApplication", "tseAdmin.aboutToSubmitCloseApplication"),
      Map.entry("initiateCase", "caseActions.postDefaultValues"),
      Map.entry("et1Vetting", "caseActions.et1VettingAboutToSubmit"),
      Map.entry("et3ResponseEmploymentDetails", "et3Response.submitSection"),
      Map.entry("et3ResponseDetails", "et3Response.submitSection"),
      Map.entry("downloadDraftEt3", "et3Response.downloadDraft.aboutToSubmit"),
      Map.entry("sendNotification", "sendNotification.aboutToSubmit"),
      Map.entry("respondNotification", "respondNotification.aboutToSubmit")
  );

  private static final Map<String, String> EXPECTED_SUBMITTED_LABELS = Map.ofEntries(
      Map.entry("tseAdmReply", "tseAdmReply.submitted"),
      Map.entry("tseAdmin", "tseAdmin.submitted"),
      Map.entry("tseAdminCloseAnApplication", "tseAdmin.submittedCloseApplication"),
      Map.entry("initiateCase", "caseActions.addServiceId"),
      Map.entry("et1Vetting", "caseActions.finishEt1Vetting"),
      Map.entry("et3ResponseEmploymentDetails", "et3Response.sectionComplete"),
      Map.entry("et3ResponseDetails", "et3Response.sectionComplete"),
      Map.entry("downloadDraftEt3", "et3Response.downloadDraft.submitted"),
      Map.entry("sendNotification", "sendNotification.submitted"),
      Map.entry("respondNotification", "respondNotification.submitted")
  );

  private static final Map<String, Integer> EXPECTED_CALL_COUNTS = Map.ofEntries(
      Map.entry("tseAdmReply.aboutToSubmit", 1),
      Map.entry("tseAdmin.aboutToSubmit", 1),
      Map.entry("tseAdmin.aboutToSubmitCloseApplication", 1),
      Map.entry("caseActions.postDefaultValues", 1),
      Map.entry("caseActions.et1VettingAboutToSubmit", 1),
      Map.entry("et3Response.submitSection", 2),
      Map.entry("et3Response.downloadDraft.aboutToSubmit", 1),
      Map.entry("sendNotification.aboutToSubmit", 1),
      Map.entry("respondNotification.aboutToSubmit", 1),
      Map.entry("tseAdmReply.submitted", 1),
      Map.entry("tseAdmin.submitted", 1),
      Map.entry("tseAdmin.submittedCloseApplication", 1),
      Map.entry("caseActions.addServiceId", 1),
      Map.entry("caseActions.finishEt1Vetting", 1),
      Map.entry("et3Response.sectionComplete", 2),
      Map.entry("et3Response.downloadDraft.submitted", 1),
      Map.entry("sendNotification.submitted", 1),
      Map.entry("respondNotification.submitted", 1)
  );

  @Test
  void createDispatchMapBindsEtFixtureEventsAcrossMultipleControllers() throws IOException {
    List<FixtureEvent> fixtureEvents = loadFixtureEvents();
    EtStubControllerSet controllers = new EtStubControllerSet();
    CallbackDispatchService service = createInitialisedService(
        definitionsFromFixtureEvents(fixtureEvents),
        controllers.beans()
    );

    for (FixtureEvent fixtureEvent : fixtureEvents) {
      var aboutToSubmitResult = service.dispatchToHandlersAboutToSubmit(
          buildRequest(fixtureEvent.caseTypeId(), fixtureEvent.eventId())
      );
      assertThat(aboutToSubmitResult.handled()).isTrue();
      assertThat(((LabelledResponse) aboutToSubmitResult.response()).label())
          .isEqualTo(EXPECTED_ABOUT_TO_SUBMIT_LABELS.get(fixtureEvent.eventId()));

      var submittedResult = service.dispatchToHandlersSubmitted(
          buildRequest(fixtureEvent.caseTypeId(), fixtureEvent.eventId())
      );
      assertThat(submittedResult.handled()).isTrue();
      assertThat(((LabelledResponse) submittedResult.response()).label())
          .isEqualTo(EXPECTED_SUBMITTED_LABELS.get(fixtureEvent.eventId()));
    }

    assertThat(controllers.callCountsByLabel()).isEqualTo(EXPECTED_CALL_COUNTS);
  }

  private static List<FixtureEvent> loadFixtureEvents() throws IOException {
    try (InputStream fixtureStream = CallbackDispatchServiceEtFixtureTest.class.getResourceAsStream(FIXTURE_PATH)) {
      if (fixtureStream == null) {
        throw new IllegalStateException("Fixture file not found: " + FIXTURE_PATH);
      }

      JsonNode eventsNode = OBJECT_MAPPER.readTree(fixtureStream);
      List<FixtureEvent> events = new ArrayList<>();
      for (JsonNode eventNode : eventsNode) {
        events.add(new FixtureEvent(
            eventNode.path("CaseTypeID").asText(),
            eventNode.path("ID").asText(),
            nullIfBlank(eventNode.path("CallBackURLAboutToSubmitEvent").asText()),
            nullIfBlank(eventNode.path("CallBackURLSubmittedEvent").asText())
        ));
      }
      return events;
    }
  }

  private static Map<String, CaseTypeDefinition> definitionsFromFixtureEvents(List<FixtureEvent> fixtureEvents) {
    Map<String, CaseTypeDefinition> definitions = new LinkedHashMap<>();

    for (FixtureEvent fixtureEvent : fixtureEvents) {
      CaseTypeDefinition caseTypeDefinition = definitions.computeIfAbsent(fixtureEvent.caseTypeId(), ignored -> {
        CaseTypeDefinition definition = new CaseTypeDefinition();
        definition.setEvents(new ArrayList<>());
        return definition;
      });

      CaseEventDefinition event = new CaseEventDefinition();
      event.setId(fixtureEvent.eventId());
      event.setCallBackURLAboutToSubmitEvent(fixtureEvent.aboutToSubmitUrl());
      event.setCallBackURLSubmittedEvent(fixtureEvent.submittedUrl());
      caseTypeDefinition.getEvents().add(event);
    }

    return definitions;
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
      restControllers.put("et-controller-" + i, controllers[i]);
    }

    Mockito.when(beanFactory.getBeansWithAnnotation(RestController.class)).thenReturn(restControllers);
    Mockito.when(beanFactory.getBeansWithAnnotation(Controller.class)).thenReturn(Map.of());

    CallbackDispatchService service = new CallbackDispatchService(definitionRegistry, beanFactory, new ObjectMapper());
    service.initialiseHandlerMaps();
    return service;
  }

  private static CallbackRequest buildRequest(String caseTypeId, String eventId) {
    return CallbackRequest.builder()
        .eventId(eventId)
        .caseDetails(CaseDetails.builder().id(12345L).caseTypeId(caseTypeId).build())
        .build();
  }

  private static String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record FixtureEvent(String caseTypeId, String eventId, String aboutToSubmitUrl, String submittedUrl) {
  }

  private static final class EtStubControllerSet {

    private final Map<String, AtomicInteger> callCounts = new LinkedHashMap<>();
    private final CaseActionsForCaseWorkerControllerStub caseActionsController =
        new CaseActionsForCaseWorkerControllerStub(callCounts);
    private final Et3ResponseControllerStub et3ResponseController = new Et3ResponseControllerStub(callCounts);
    private final TseAdminControllerStub tseAdminController = new TseAdminControllerStub(callCounts);
    private final TseAdmReplyControllerStub tseAdmReplyController = new TseAdmReplyControllerStub(callCounts);
    private final SendNotificationControllerStub sendNotificationController =
        new SendNotificationControllerStub(callCounts);
    private final RespondNotificationControllerStub respondNotificationController =
        new RespondNotificationControllerStub(callCounts);

    private Object[] beans() {
      return new Object[] {
          caseActionsController,
          et3ResponseController,
          tseAdminController,
          tseAdmReplyController,
          sendNotificationController,
          respondNotificationController
      };
    }

    private Map<String, Integer> callCountsByLabel() {
      Map<String, Integer> counts = new LinkedHashMap<>();
      callCounts.forEach((key, value) -> counts.put(key, value.get()));
      return counts;
    }
  }

  private abstract static class StubCallbackController {
    private final Map<String, AtomicInteger> callCounts;

    private StubCallbackController(Map<String, AtomicInteger> callCounts) {
      this.callCounts = callCounts;
    }

    protected CallbackResponse<?> aboutToSubmit(String label) {
      increment(label);
      return new LabelledCallbackResponse(label);
    }

    protected CallbackResponse<?> submitted(String label) {
      increment(label);
      return new LabelledSubmittedCallbackResponse(label);
    }

    private void increment(String label) {
      callCounts.computeIfAbsent(label, ignored -> new AtomicInteger()).incrementAndGet();
    }
  }

  @RestController
  private static final class CaseActionsForCaseWorkerControllerStub extends StubCallbackController {

    private CaseActionsForCaseWorkerControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/postDefaultValues")
    public CallbackResponse<?> postDefaultValues(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("caseActions.postDefaultValues");
    }

    @PostMapping("/addServiceId")
    public CallbackResponse<?> addServiceId(CallbackRequest callbackRequest, String authToken) {
      return submitted("caseActions.addServiceId");
    }

    @PostMapping("/et1VettingAboutToSubmit")
    public CallbackResponse<?> et1VettingAboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("caseActions.et1VettingAboutToSubmit");
    }

    @PostMapping("/finishEt1Vetting")
    public CallbackResponse<?> finishEt1Vetting(CallbackRequest callbackRequest, String authToken) {
      return submitted("caseActions.finishEt1Vetting");
    }
  }

  @RestController
  @RequestMapping("/et3Response")
  private static final class Et3ResponseControllerStub extends StubCallbackController {

    private Et3ResponseControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/submitSection")
    public CallbackResponse<?> submitSection(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("et3Response.submitSection");
    }

    @PostMapping("/sectionComplete")
    public CallbackResponse<?> sectionComplete(CallbackRequest callbackRequest, String authToken) {
      return submitted("et3Response.sectionComplete");
    }

    @PostMapping("/downloadDraft/aboutToSubmit")
    public CallbackResponse<?> downloadDraftAboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("et3Response.downloadDraft.aboutToSubmit");
    }

    @PostMapping("/downloadDraft/submitted")
    public CallbackResponse<?> downloadDraftSubmitted(CallbackRequest callbackRequest, String authToken) {
      return submitted("et3Response.downloadDraft.submitted");
    }
  }

  @RestController
  @RequestMapping("/tseAdmin")
  private static final class TseAdminControllerStub extends StubCallbackController {

    private TseAdminControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/aboutToSubmit")
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("tseAdmin.aboutToSubmit");
    }

    @PostMapping("/submitted")
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      return submitted("tseAdmin.submitted");
    }

    @PostMapping("/aboutToSubmitCloseApplication")
    public CallbackResponse<?> aboutToSubmitCloseApplication(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("tseAdmin.aboutToSubmitCloseApplication");
    }

    @PostMapping("/submittedCloseApplication")
    public CallbackResponse<?> submittedCloseApplication(CallbackRequest callbackRequest, String authToken) {
      return submitted("tseAdmin.submittedCloseApplication");
    }
  }

  @RestController
  @RequestMapping("/tseAdmReply")
  private static final class TseAdmReplyControllerStub extends StubCallbackController {

    private TseAdmReplyControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/aboutToSubmit")
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("tseAdmReply.aboutToSubmit");
    }

    @PostMapping("/submitted")
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      return submitted("tseAdmReply.submitted");
    }
  }

  @RestController
  @RequestMapping("/sendNotification")
  private static final class SendNotificationControllerStub extends StubCallbackController {

    private SendNotificationControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/aboutToSubmit")
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("sendNotification.aboutToSubmit");
    }

    @PostMapping("/submitted")
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      return submitted("sendNotification.submitted");
    }
  }

  @RestController
  @RequestMapping("/respondNotification")
  private static final class RespondNotificationControllerStub extends StubCallbackController {

    private RespondNotificationControllerStub(Map<String, AtomicInteger> callCounts) {
      super(callCounts);
    }

    @PostMapping("/aboutToSubmit")
    public CallbackResponse<?> aboutToSubmit(CallbackRequest callbackRequest, String authToken) {
      return aboutToSubmit("respondNotification.aboutToSubmit");
    }

    @PostMapping("/submitted")
    public CallbackResponse<?> submitted(CallbackRequest callbackRequest, String authToken) {
      return submitted("respondNotification.submitted");
    }
  }

  private interface LabelledResponse {
    String label();
  }

  private static class LabelledCallbackResponse implements CallbackResponse<Object>, LabelledResponse {
    private final String label;

    private LabelledCallbackResponse(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

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

  private static final class LabelledSubmittedCallbackResponse extends LabelledCallbackResponse
      implements SubmittedCallbackResponse {

    private LabelledSubmittedCallbackResponse(String label) {
      super(label);
    }

    @Override
    public String getConfirmationHeader() {
      return "header";
    }

    @Override
    public String getConfirmationBody() {
      return "body";
    }
  }
}
