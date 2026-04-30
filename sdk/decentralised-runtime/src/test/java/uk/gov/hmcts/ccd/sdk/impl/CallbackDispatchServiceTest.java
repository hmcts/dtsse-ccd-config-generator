package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

class CallbackDispatchServiceTest {

  private static final String AUTHORIZATION = "Bearer token";

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void dispatchesThroughSpringMvcAndNormalisesLegacyPojoResponses() {
    EtStyleController controller = new EtStyleController();
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/et-style/aboutToSubmit?source=definition",
            "${BASE_URL}/et-style/submitted",
            null
        ))),
        controller
    );

    var aboutToSubmit = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"), AUTHORIZATION);
    var submitted = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"), AUTHORIZATION);

    Assertions.assertThat(aboutToSubmit.handled()).isTrue();
    Assertions.assertThat(aboutToSubmit.response().getData()).isInstanceOf(Map.class);
    Assertions.assertThat(((Map<?, ?>) aboutToSubmit.response().getData()).get("value"))
        .isEqualTo("EVENT_ID-definition");
    Assertions.assertThat(aboutToSubmit.response().getState()).isEqualTo("Validated");
    Assertions.assertThat(aboutToSubmit.response().getSecurityClassification()).isEqualTo("PUBLIC");
    Assertions.assertThat(aboutToSubmit.response().getErrors()).containsExactly("error-message");
    Assertions.assertThat(aboutToSubmit.response().getWarnings()).containsExactly("warning-message");

    Assertions.assertThat(submitted.handled()).isTrue();
    Assertions.assertThat(submitted.response().getConfirmationHeader()).isEqualTo("done");
    Assertions.assertThat(submitted.response().getConfirmationBody()).isEqualTo("body");
    Assertions.assertThat(controller.lastAboutToSubmitToken).isEqualTo(AUTHORIZATION);
    Assertions.assertThat(controller.lastSubmittedToken).isEqualTo(AUTHORIZATION);
  }

  @Test
  void dispatchToHandlersReturnsNoHandlerWhenNoDefinitionBindingMatches() {
    EtStyleController controller = new EtStyleController();
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "DIFFERENT_EVENT",
            "${BASE_URL}/et-style/aboutToSubmit",
            "${BASE_URL}/et-style/submitted",
            null
        ))),
        controller
    );

    var aboutToSubmit = service.dispatchToHandlersAboutToSubmit(buildRequest("EVENT_ID"), AUTHORIZATION);
    var submitted = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"), AUTHORIZATION);

    Assertions.assertThat(aboutToSubmit.handled()).isFalse();
    Assertions.assertThat(aboutToSubmit.response()).isNull();
    Assertions.assertThat(submitted.handled()).isFalse();
    Assertions.assertThat(submitted.response()).isNull();
  }

  @Test
  void dispatchToHandlersSubmittedRetriesUntilSuccess() {
    RetryController controller = new RetryController(2);
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/retry/submitted",
            List.of(1, 2)
        ))),
        controller
    );

    var result = service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"), AUTHORIZATION);

    Assertions.assertThat(result.handled()).isTrue();
    Assertions.assertThat(result.response().getConfirmationHeader()).isEqualTo("retry-done");
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(3);
  }

  @Test
  void dispatchToHandlersSubmittedThrowsWhenRetriesExhausted() {
    RetryController controller = new RetryController(Integer.MAX_VALUE);
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            null,
            "${BASE_URL}/retry/submitted",
            List.of(1, 2)
        ))),
        controller
    );

    Assertions.assertThatThrownBy(() -> service.dispatchToHandlersSubmitted(buildRequest("EVENT_ID"), AUTHORIZATION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Submitted callback failed after 3 attempt(s)")
        .hasMessageContaining("caseType=CASE_TYPE")
        .hasMessageContaining("eventId=EVENT_ID");
    Assertions.assertThat(controller.submittedCalls.get()).isEqualTo(3);
  }

  @Test
  void dispatchFailsWhenSpringMvcReturnsNonSuccessStatus() {
    NonSuccessController controller = new NonSuccessController();
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/non-success/aboutToSubmit",
            null,
            null
        ))),
        controller
    );

    Assertions.assertThatThrownBy(() -> service.dispatchToHandlersAboutToSubmit(
        buildRequest("EVENT_ID"),
        AUTHORIZATION
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-success status 403");
  }

  @Test
  void dispatchFailsWhenDefinitionPathHasNoSpringMvcMapping() {
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(event(
            "EVENT_ID",
            "${BASE_URL}/missing/aboutToSubmit",
            null,
            null
        ))),
        new EtStyleController()
    );

    Assertions.assertThatThrownBy(() -> service.dispatchToHandlersAboutToSubmit(
        buildRequest("EVENT_ID"),
        AUTHORIZATION
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-success status 404");
  }

  @Test
  void createDispatchMapFailsFastOnDuplicateBindings() {
    Assertions.assertThatThrownBy(() -> createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(
            event("EVENT_ID", "${BASE_URL}/et-style/aboutToSubmit", null, null),
            event("EVENT_ID", "${BASE_URL}/et-style/otherAboutToSubmit", null, null)
        )),
        new EtStyleController()
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Multiple Bindings found")
        .hasMessageContaining("callback type aboutToSubmit");
  }

  @Test
  void createDispatchMapIgnoresExternalCallbackBindingsWhenLocalBaseUrlConfigured() {
    CallbackDispatchService service = createInitialisedService(
        Map.of("CASE_TYPE", definitionForEvents(
            event("LOCAL_EVENT", "${BASE_URL}/et-style/aboutToSubmit?source=definition", null, null),
            event("EXTERNAL_EVENT", "http://localhost:4454/noc/check-noc-approval", null, null)
        )),
        "http://localhost:8081",
        new EtStyleController()
    );

    var localResult = service.dispatchToHandlersAboutToSubmit(buildRequest("LOCAL_EVENT"), AUTHORIZATION);
    var externalResult = service.dispatchToHandlersAboutToSubmit(buildRequest("EXTERNAL_EVENT"), AUTHORIZATION);

    Assertions.assertThat(localResult.handled()).isTrue();
    Assertions.assertThat(externalResult.handled()).isFalse();
  }

  private CallbackDispatchService createInitialisedService(
      Map<String, CaseTypeDefinition> definitions,
      Object... controllers
  ) {
    return createInitialisedService(definitions, "", controllers);
  }

  private CallbackDispatchService createInitialisedService(
      Map<String, CaseTypeDefinition> definitions,
      String localCallbackBaseUrls,
      Object... controllers
  ) {
    DefinitionRegistry definitionRegistry = Mockito.mock(DefinitionRegistry.class);
    Mockito.when(definitionRegistry.loadDefinitions()).thenReturn(definitions);

    CallbackDispatchService service = new CallbackDispatchService(
        definitionRegistry,
        dispatcherServlet(controllers),
        new ObjectMapper()
    );
    ReflectionTestUtils.setField(service, "localCallbackBaseUrls", localCallbackBaseUrls);
    service.initialiseHandlerMaps();

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
        new MockHttpServletRequest("POST", "/ccd-persistence/cases"),
        new MockHttpServletResponse()
    ));
    return service;
  }

  private DispatcherServlet dispatcherServlet(Object... controllers) {
    GenericWebApplicationContext context = new GenericWebApplicationContext(new MockServletContext());
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "spring.mvc.hiddenmethod.filter.enabled=false");
    new AnnotatedBeanDefinitionReader(context).register(WebConfig.class);
    for (int i = 0; i < controllers.length; i++) {
      registerController(context, "controller-" + i, controllers[i]);
    }
    context.refresh();

    DispatcherServlet dispatcherServlet = new DispatcherServlet(context);
    try {
      dispatcherServlet.init(new MockServletConfig(context.getServletContext()));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialise test dispatcher servlet", ex);
    }
    return dispatcherServlet;
  }

  @SuppressWarnings("unchecked")
  private static void registerController(GenericWebApplicationContext context, String name, Object controller) {
    context.registerBean(name, (Class<Object>) controller.getClass(), () -> controller);
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

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class WebConfig {
  }

  @RestController
  @RequestMapping("/et-style")
  private static final class EtStyleController {
    private String lastAboutToSubmitToken;
    private String lastSubmittedToken;

    @PostMapping("/aboutToSubmit")
    public ResponseEntity<EtStyleCallbackResponse> aboutToSubmit(
        @RequestBody CCDRequest request,
        @RequestHeader("Authorization") String authToken,
        @RequestParam("source") String source
    ) {
      lastAboutToSubmitToken = authToken;
      return ResponseEntity.ok(new EtStyleCallbackResponse(
          new EtStyleCaseData(request.getEventId() + "-" + source),
          List.of("error-message"),
          List.of("warning-message"),
          "Validated",
          "PUBLIC",
          null,
          null
      ));
    }

    @PostMapping("/submitted")
    public ResponseEntity<EtStyleCallbackResponse> submitted(
        @RequestBody CCDRequest request,
        @RequestHeader("Authorization") String authToken
    ) {
      lastSubmittedToken = authToken;
      return ResponseEntity.ok(new EtStyleCallbackResponse(null, null, null, null, null, "done", "body"));
    }
  }

  @RestController
  @RequestMapping("/retry")
  private static final class RetryController {
    private final AtomicInteger submittedFailuresRemaining;
    private final AtomicInteger submittedCalls = new AtomicInteger();

    private RetryController(int submittedFailuresBeforeSuccess) {
      this.submittedFailuresRemaining = new AtomicInteger(submittedFailuresBeforeSuccess);
    }

    @PostMapping("/submitted")
    public ResponseEntity<EtStyleCallbackResponse> submitted(@RequestBody CCDRequest request) {
      submittedCalls.incrementAndGet();
      if (submittedFailuresRemaining.getAndDecrement() > 0) {
        throw new RuntimeException("submitted callback failure");
      }
      return ResponseEntity.ok(new EtStyleCallbackResponse(null, null, null, null, null, "retry-done", "body"));
    }
  }

  @RestController
  @RequestMapping("/non-success")
  private static final class NonSuccessController {

    @PostMapping("/aboutToSubmit")
    public ResponseEntity<EtStyleCallbackResponse> aboutToSubmit(@RequestBody CCDRequest request) {
      return ResponseEntity.status(403).build();
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

  private static final class EtStyleCaseData {
    private final String value;

    private EtStyleCaseData(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  private static final class EtStyleCallbackResponse {
    private final EtStyleCaseData data;
    private final List<String> errors;
    private final List<String> warnings;
    private final String state;
    private final String securityClassification;
    private final String confirmationHeader;
    private final String confirmationBody;

    private EtStyleCallbackResponse(
        EtStyleCaseData data,
        List<String> errors,
        List<String> warnings,
        String state,
        String securityClassification,
        String confirmationHeader,
        String confirmationBody
    ) {
      this.data = data;
      this.errors = errors;
      this.warnings = warnings;
      this.state = state;
      this.securityClassification = securityClassification;
      this.confirmationHeader = confirmationHeader;
      this.confirmationBody = confirmationBody;
    }

    public EtStyleCaseData getData() {
      return data;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    public String getState() {
      return state;
    }

    @JsonProperty("security_classification")
    public String getSecurityClassification() {
      return securityClassification;
    }

    @JsonProperty("confirmation_header")
    public String getConfirmationHeader() {
      return confirmationHeader;
    }

    @JsonProperty("confirmation_body")
    public String getConfirmationBody() {
      return confirmationBody;
    }
  }
}
