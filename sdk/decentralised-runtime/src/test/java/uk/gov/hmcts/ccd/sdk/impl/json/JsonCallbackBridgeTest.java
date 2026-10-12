package uk.gov.hmcts.ccd.sdk.impl.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.ClientContextPassthrough;
import uk.gov.hmcts.ccd.sdk.json.JsonCallbackHandler;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

class JsonCallbackBridgeTest {

  private static final String FROM_THE_FRONTEND = encoded("{\"selectedPartyId\":\"party-1\"}");

  private final ObjectMapper mapper = new CcdCaseDataMapperConfiguration()
      .ccdCaseDataObjectMapper(new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY));

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void invokesConfiguredPlaceholderCallbackLocally() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "ET_COS_URL"),
        new LocalCallbackController()
    );

    Object response = bridge.invoke(
        "${ET_COS_URL}/callbacks/about-to-submit",
        Map.of("event_id", "local")
    );

    assertThat(response)
        .isInstanceOf(Map.class)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("source", "local");
  }

  @Test
  void failsFastWhenCallbackUsesUnknownPlaceholder() {
    JsonCallbackBridge bridge = bridgeWith(new MockEnvironment());

    assertThatThrownBy(() -> bridge.invoke("${MISSING_URL}/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No external callback base URL configured for JSON callback placeholder MISSING_URL");
  }

  @Test
  void acceptsConfiguredExternalPlaceholderAtValidationTime() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty(
            "decentralisation.external-callback-base-urls[CCD_DEF_AAC_URL]",
            "http://aac.example"
        )
    );

    bridge.validate("${CCD_DEF_AAC_URL}/noc/check-noc-approval");
  }

  @Test
  void preservesEmptyNestedCaseDataObjectsInLocalCallbackResponse() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "ET_COS_URL"),
        new LocalCallbackController()
    );
    CaseDetails<Object, Object> caseDetails = CaseDetails.builder()
        .data(new NocCaseData(null))
        .build();

    AboutToStartOrSubmitResponse response = bridge.aboutToSubmit(
        "${ET_COS_URL}/callbacks/noc-about-to-submit",
        "local"
    ).handle(caseDetails, null);

    NocCaseData responseData = (NocCaseData) response.getData();
    assertThat(responseData.changeOrganisationRequestField().OrganisationToAdd()).isNotNull();
  }

  @Test
  void mapsSignificantItemFromLocalCallbackResponse() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "ET_COS_URL"),
        new LocalCallbackController()
    );
    CaseDetails<Object, Object> caseDetails = CaseDetails.builder()
        .data(Map.of())
        .build();

    AboutToStartOrSubmitResponse response = bridge.aboutToSubmit(
        "${ET_COS_URL}/callbacks/significant-item",
        "local"
    ).handle(caseDetails, null);

    assertThat(response.getSignificantItem().getType()).isEqualTo("DOCUMENT");
    assertThat(response.getSignificantItem().getDescription()).isEqualTo("Generated document");
    assertThat(response.getSignificantItem().getUrl()).isEqualTo("http://dm-store/documents/123");
  }

  @Test
  void serviceCallbackHandlerRunsTemplatedLocalCallbackWithSubmissionHeaders() {
    MockHttpServletRequest submission = new MockHttpServletRequest();
    submission.addHeader("Authorization", "Bearer user-token");
    submission.addHeader("ServiceAuthorization", "s2s-token");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(submission));
    RecordingCallbackHandler handler = new RecordingCallbackHandler("/cases/callbacks/");
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "CIVIL_URL"),
        handler
    );
    CaseDetails<Object, Object> caseDetails = CaseDetails.builder()
        .id(1234L)
        .caseTypeId("CIVIL")
        .data(Map.of("claimant", "Alice"))
        .build();

    bridge.validate("${CIVIL_URL}/cases/callbacks/version/V_1/about-to-submit");
    AboutToStartOrSubmitResponse response = bridge.aboutToSubmit(
        "${CIVIL_URL}/cases/callbacks/version/V_1/about-to-submit",
        "CREATE_CLAIM"
    ).handle(caseDetails, null);

    assertThat(handler.path).isEqualTo("/cases/callbacks/version/V_1/about-to-submit");
    assertThat(handler.request.getEventId()).isEqualTo("CREATE_CLAIM");
    assertThat(handler.request.getCaseDetails().getId()).isEqualTo(1234L);
    assertThat(handler.request.getCaseDetails().getCaseTypeId()).isEqualTo("CIVIL");
    assertThat(handler.request.getCaseDetails().getData()).containsEntry("claimant", "Alice");
    assertThat(handler.headers.getFirst("Authorization")).isEqualTo("Bearer user-token");
    assertThat(handler.headers.getFirst("ServiceAuthorization")).isEqualTo("s2s-token");
    assertThat(response.getState()).isEqualTo("AWAITING_RESPONDENT_ACKNOWLEDGEMENT");
    assertThat(response.getErrors()).containsExactly("handled by the service");
  }

  @Test
  void serviceCallbackHandlerTakesPrecedenceOverControllerMappedToTheSamePath() {
    RecordingCallbackHandler handler = new RecordingCallbackHandler("/callbacks/about-to-submit");
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "ET_COS_URL"),
        new LocalCallbackController(),
        handler
    );

    bridge.invoke("${ET_COS_URL}/callbacks/about-to-submit", Map.of("event_id", "local"));

    assertThat(handler.path).isEqualTo("/callbacks/about-to-submit");
  }

  @Test
  void carriesAndMergesTheClientContextThroughServiceCallbacksAsCcdDoes() {
    MockHttpServletRequest submission = new MockHttpServletRequest();
    submission.addHeader("Client-Context", FROM_THE_FRONTEND);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(submission));
    RecordingCallbackHandler handler = new RecordingCallbackHandler("/cases/callbacks/");
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "CIVIL_URL"),
        handler
    );

    bridge.invoke("${CIVIL_URL}/cases/callbacks/about-to-submit", Map.of("event_id", "SEND_AND_REPLY"));
    assertThat(handler.headers.getFirst("Client-Context")).isEqualTo(FROM_THE_FRONTEND);
    assertThat(ClientContextPassthrough.returned()).isEmpty();

    handler.returnClientContext = "[" + encoded("{\"client_context\":{\"user_task\":{\"complete_task\":true}}}") + "]";
    bridge.invoke("${CIVIL_URL}/cases/callbacks/submitted", Map.of("event_id", "SEND_AND_REPLY"));
    String merged = encoded(
        "{\"selectedPartyId\":\"party-1\",\"client_context\":{\"user_task\":{\"complete_task\":true}}}");
    assertThat(ClientContextPassthrough.returned()).contains(merged);

    handler.returnClientContext = null;
    bridge.invoke("${CIVIL_URL}/cases/callbacks/submitted", Map.of("event_id", "SEND_AND_REPLY"));
    assertThat(handler.headers.getFirst("Client-Context")).isEqualTo(merged);
    assertThat(ClientContextPassthrough.returned()).contains(merged);
  }

  @Test
  void keepsTheCurrentClientContextWhenACallbackReturnsOneThatIsNotJson() {
    MockHttpServletRequest submission = new MockHttpServletRequest();
    submission.addHeader("Client-Context", FROM_THE_FRONTEND);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(submission));
    RecordingCallbackHandler handler = new RecordingCallbackHandler("/cases/callbacks/");
    handler.returnClientContext = "not-a-context";

    bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "CIVIL_URL"),
        handler
    ).invoke("${CIVIL_URL}/cases/callbacks/submitted", Map.of("event_id", "SEND_AND_REPLY"));

    assertThat(ClientContextPassthrough.returned()).contains(FROM_THE_FRONTEND);
  }

  @Test
  void carriesTheClientContextThroughExternalCallbacks() throws Exception {
    MockHttpServletRequest submission = new MockHttpServletRequest();
    submission.addHeader("Client-Context", FROM_THE_FRONTEND);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(submission));
    String returned = encoded("{\"client_context\":{\"user_task\":{\"complete_task\":true}}}");
    AtomicReference<String> sent = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/callback", exchange -> {
      sent.set(exchange.getRequestHeaders().getFirst("Client-Context"));
      exchange.getResponseHeaders().add("Client-Context", returned);
      byte[] body = "{}".getBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      bridgeWith(new MockEnvironment()).invoke(
          "http://localhost:%s/callback".formatted(server.getAddress().getPort()),
          Map.of()
      );
    } finally {
      server.stop(0);
    }

    assertThat(sent.get()).isEqualTo(FROM_THE_FRONTEND);
    assertThat(ClientContextPassthrough.returned()).contains(encoded(
        "{\"selectedPartyId\":\"party-1\",\"client_context\":{\"user_task\":{\"complete_task\":true}}}"));
  }

  @Test
  void failsWhenMoreThanOneServiceCallbackHandlerHandlesACallback() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "CIVIL_URL"),
        new RecordingCallbackHandler("/cases/callbacks/"),
        new RecordingCallbackHandler("/cases/")
    );

    assertThatThrownBy(() -> bridge.validate("${CIVIL_URL}/cases/callbacks/submitted"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Multiple JsonCallbackHandlers handle JSON callback ${CIVIL_URL}/cases/callbacks/submitted");
  }

  @Test
  void failsFastWhenCallbackIsNeitherLocalNorAbsoluteExternalUrl() {
    JsonCallbackBridge bridge = bridgeWith(new MockEnvironment());

    assertThatThrownBy(() -> bridge.invoke("/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No absolute external URL found for JSON callback /callback");
  }

  @Test
  void timesOutSlowExternalCallbackUsingConfiguredTimeout() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/callback", exchange -> {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.sendResponseHeaders(200, 0);
      exchange.close();
    });
    server.start();
    try {
      JsonCallbackBridge bridge = bridgeWith(
          new MockEnvironment().withProperty("decentralisation.external-callback-timeout", "100ms")
      );

      assertThatThrownBy(() -> bridge.invoke(
          "http://localhost:%s/callback".formatted(server.getAddress().getPort()),
          Map.of()
      ))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("External JSON callback timed out")
          .hasCauseInstanceOf(HttpTimeoutException.class);
    } finally {
      server.stop(0);
    }
  }

  private JsonCallbackBridge bridgeWith(MockEnvironment environment, Object... controllers) {
    try {
      StaticApplicationContext applicationContext = new StaticApplicationContext();
      for (int i = 0; i < controllers.length; i++) {
        applicationContext.getBeanFactory().registerSingleton("controller-" + i, controllers[i]);
      }
      applicationContext.refresh();

      RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
      handlerMapping.setApplicationContext(applicationContext);
      handlerMapping.afterPropertiesSet();

      return new JsonCallbackBridge(
          applicationContext,
          mapper,
          handlerMapping,
          environment
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encoded(String json) {
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  @RestController
  @RequestMapping("/callbacks")
  private static class LocalCallbackController {

    @PostMapping("/about-to-submit")
    Map<String, Object> aboutToSubmit(@RequestBody Map<String, Object> request) {
      return Map.of("source", "local", "event_id", request.get("event_id"));
    }

    @PostMapping("/noc-about-to-submit")
    NocCallbackResponse nocAboutToSubmit(@RequestBody Map<String, Object> request) {
      return new NocCallbackResponse(new NocCaseData(
          new ChangeOrganisationRequestField(new OrganisationToAdd(null))
      ));
    }

    @PostMapping("/significant-item")
    Map<String, Object> significantItem(@RequestBody Map<String, Object> request) {
      return Map.of(
          "data", Map.of(),
          "significant_item", Map.of(
              "type", "DOCUMENT",
              "description", "Generated document",
              "url", "http://dm-store/documents/123"
          )
      );
    }
  }

  private static class RecordingCallbackHandler implements JsonCallbackHandler {

    private final String pathPrefix;
    private String path;
    private CallbackRequest request;
    private HttpHeaders headers;
    private String returnClientContext;

    private RecordingCallbackHandler(String pathPrefix) {
      this.pathPrefix = pathPrefix;
    }

    @Override
    public boolean handles(String callbackPath) {
      return callbackPath.startsWith(pathPrefix);
    }

    @Override
    public Object handle(String callbackPath, CallbackRequest request, HttpHeaders headers) {
      this.path = callbackPath;
      this.request = request;
      this.headers = headers;
      Map<String, Object> body = Map.of(
          "data", Map.of(),
          "state", "AWAITING_RESPONDENT_ACKNOWLEDGEMENT",
          "errors", List.of("handled by the service")
      );
      return returnClientContext == null
          ? body
          : ResponseEntity.ok().header("Client-Context", returnClientContext).body(body);
    }
  }

  private record NocCallbackResponse(NocCaseData data) {
  }

  private record NocCaseData(ChangeOrganisationRequestField changeOrganisationRequestField) {
  }

  private record ChangeOrganisationRequestField(OrganisationToAdd OrganisationToAdd) {
  }

  private record OrganisationToAdd(@JsonProperty("OrganisationID") String organisationId) {
  }
}
