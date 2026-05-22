package uk.gov.hmcts.divorce.cftlib;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.SneakyThrows;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.jsonlegacy.JsonLegacyCallbackController;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.jms.servicebus.enabled=false",
    "spring.autoconfigure.exclude="
        + "com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration"
})
class JsonLegacyCallbackDispatchWithCCDTest extends CftlibTest {

  private static final String BASE_URL = "http://localhost:4452";
  private static final String ACCEPT_CREATE_CASE =
      "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-case.v2+json;charset=UTF-8";
  private static final String ACCEPT_CREATE_EVENT =
      "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-event.v2+json;charset=UTF-8";
  private static final String USER = "TEST_CASE_WORKER_USER@mailinator.com";
  private static final String EVENT_ID = "json-legacy-dispatch";
  private static final String SERVICE_BASE_URL = "http://localhost:4013";

  @Autowired
  private IdamClient idam;

  @Autowired
  private ObjectMapper mapper;

  @Autowired
  private CoreCaseDataApi ccdApi;

  @Autowired
  private NamedParameterJdbcTemplate db;

  private long caseRef;

  @BeforeAll
  void createCase() throws Exception {
    var start = ccdApi.startCase(getAuthorisation(USER), getServiceAuth(), NoFaultDivorce.getCaseType(),
        "create-test-application");
    var body = Map.of(
        "data", Map.of(
            "applicationType", "soleApplication",
            "applicant1SolicitorRepresented", "No",
            "applicant2SolicitorRepresented", "No",
            "testDocument", Map.of(
                "document_url", "http://localhost/documents/" + UUID.randomUUID(),
                "document_binary_url", "http://localhost/documents/binary",
                "document_filename", "test.pdf"
            )
        ),
        "event", Map.of("id", "create-test-application", "summary", "", "description", ""),
        "event_token", start.getToken(),
        "ignore_warning", false
    );

    var request = buildRequest(USER, BASE_URL + "/data/case-types/E2E/cases?ignore-warning=false", HttpPost::new);
    withCcdAccept(request, ACCEPT_CREATE_CASE);
    request.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

    var response = HttpClientBuilder.create().build().execute(request);
    assertEquals(201, response.getStatusLine().getStatusCode());
    Map<String, Object> result = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});
    caseRef = Long.parseLong((String) result.get("id"));
  }

  @BeforeEach
  void resetCallbacks() {
    JsonLegacyCallbackController.reset();
  }

  @Test
  void dispatchesJsonDefinitionCallbacksToSpringController() throws Exception {
    var response = submitEvent(Map.of("note", "json-legacy-normal"));
    assertEquals(201, response.statusCode());

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.body().get("data");
    assertEquals(JsonLegacyCallbackController.MARKER, data.get("setInAboutToSubmit"));

    @SuppressWarnings("unchecked")
    Map<String, Object> afterSubmit =
        (Map<String, Object>) response.body().get("after_submit_callback_response");
    assertEquals(JsonLegacyCallbackController.CONFIRMATION_HEADER, afterSubmit.get("confirmation_header"));
    assertEquals(JsonLegacyCallbackController.CONFIRMATION_BODY, afterSubmit.get("confirmation_body"));

    assertEquals(1, JsonLegacyCallbackController.aboutToSubmitAttempts.get());
    assertTrue(JsonLegacyCallbackController.aboutToSubmitSawAuthorisation.get());
    assertEquals(1, JsonLegacyCallbackController.submittedAttempts.get());
    assertTrue(JsonLegacyCallbackController.submittedSawCommittedData.get());
  }

  @Test
  void aboutToSubmitErrorsRollbackJsonLegacySubmission() throws Exception {
    String before = storedData();
    var response = submitEvent(Map.of("note", "json-legacy-error"));

    assertEquals(422, response.statusCode());
    @SuppressWarnings("unchecked")
    List<String> callbackErrors = (List<String>) response.body().get("callbackErrors");
    assertEquals(List.of("JSON legacy validation error"), callbackErrors);
    assertEquals(before, storedData());
    assertEquals(1, JsonLegacyCallbackController.aboutToSubmitAttempts.get());
    assertEquals(0, JsonLegacyCallbackController.submittedAttempts.get());
  }

  @Test
  void submittedRetriesAndDuplicateSubmissionDoesNotReRun() throws Exception {
    var startEvent = ccdApi.startEvent(getAuthorisation(USER), getServiceAuth(), String.valueOf(caseRef), EVENT_ID);
    Map<String, Object> data = new LinkedHashMap<>(
        mapper.convertValue(startEvent.getCaseDetails().getData(), new TypeReference<Map<String, Object>>() {})
    );
    data.put("note", "json-legacy-retry");

    var request = eventRequestWithToken(data, startEvent.getToken());
    var firstResponse = HttpClientBuilder.create().build().execute(request);
    assertEquals(201, firstResponse.getStatusLine().getStatusCode());
    assertEquals(3, JsonLegacyCallbackController.submittedAttempts.get());

    var duplicateRequest = eventRequestWithToken(data, startEvent.getToken());
    var duplicateResponse = HttpClientBuilder.create().build().execute(duplicateRequest);
    assertEquals(201, duplicateResponse.getStatusLine().getStatusCode());
    assertEquals(3, JsonLegacyCallbackController.submittedAttempts.get());
  }

  @Test
  void syntheticDocumentUpdatedEventUsesExistingLegacySubmissionFlow() throws Exception {
    var caseDetails = ccdApi.getCase(getAuthorisation(USER), getServiceAuth(), String.valueOf(caseRef));
    Map<String, Object> data = new LinkedHashMap<>(caseDetails.getData());
    data.put("applicationType", "jointApplication");

    Map<String, Object> row = db.queryForMap(
        "select id, version from ccd.case_data where reference = :reference",
        Map.of("reference", caseRef)
    );
    Map<String, Object> decentralisedCaseDetails = Map.of(
        "id", caseRef,
        "jurisdiction", caseDetails.getJurisdiction(),
        "case_type_id", caseDetails.getCaseTypeId(),
        "state", caseDetails.getState(),
        "case_data", data,
        "security_classification", "PUBLIC",
        "version", row.get("version")
    );
    Map<String, Object> payload = Map.of(
        "internal_case_id", row.get("id"),
        "case_details_before", Map.of(
            "id", caseRef,
            "jurisdiction", caseDetails.getJurisdiction(),
            "case_type_id", caseDetails.getCaseTypeId(),
            "state", caseDetails.getState(),
            "case_data", caseDetails.getData(),
            "security_classification", "PUBLIC",
            "version", row.get("version")
        ),
        "case_details", decentralisedCaseDetails,
        "event_details", Map.of(
            "case_type", caseDetails.getCaseTypeId(),
            "event_id", "DocumentUpdated",
            "event_name", "Document updated",
            "summary", "document updated",
            "description", "document updated"
        )
    );

    var request = new HttpPost(SERVICE_BASE_URL + "/ccd-persistence/cases");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("Authorization", getAuthorisation(USER));
    request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
    request.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

    var response = HttpClientBuilder.create().build().execute(request);
    assertEquals(200, response.getStatusLine().getStatusCode());
    Map<String, Object> body = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> returnedCaseDetails = (Map<String, Object>) body.get("case_details");
    @SuppressWarnings("unchecked")
    Map<String, Object> returnedData = (Map<String, Object>) returnedCaseDetails.get("case_data");
    assertEquals("jointApplication", returnedData.get("applicationType"));
  }

  private EventResponse submitEvent(Map<String, ?> data) throws Exception {
    var startEvent = ccdApi.startEvent(getAuthorisation(USER), getServiceAuth(), String.valueOf(caseRef), EVENT_ID);
    Map<String, Object> submissionData = new LinkedHashMap<>(
        mapper.convertValue(startEvent.getCaseDetails().getData(), new TypeReference<Map<String, Object>>() {})
    );
    submissionData.putAll(data);

    var response = HttpClientBuilder.create().build().execute(
        eventRequestWithToken(submissionData, startEvent.getToken())
    );
    Map<String, Object> body = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});
    return new EventResponse(response.getStatusLine().getStatusCode(), body);
  }

  @SneakyThrows
  private HttpPost eventRequestWithToken(Map<String, ?> data, String token) {
    var body = Map.of(
        "data", data,
        "event", Map.of("id", EVENT_ID, "summary", "summary", "description", "description"),
        "event_token", token,
        "ignore_warning", false
    );
    var request = buildRequest(USER, BASE_URL + "/cases/" + caseRef + "/events", HttpPost::new);
    withCcdAccept(request, ACCEPT_CREATE_EVENT);
    request.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
    return request;
  }

  private String storedData() {
    return db.queryForObject(
        "select data::text from ccd.case_data where reference = :reference",
        Map.of("reference", caseRef),
        String.class
    );
  }

  private String getAuthorisation(String user) {
    return idam.getAccessToken(user, "");
  }

  private String getServiceAuth() {
    return cftlib().generateDummyS2SToken("ccd_gw");
  }

  private <T extends HttpRequestBase> T buildRequest(String user, String url, Function<String, T> ctor) {
    var request = ctor.apply(url);
    request.addHeader("Content-Type", "application/json");
    request.addHeader("ServiceAuthorization", getServiceAuth());
    request.addHeader("Authorization", getAuthorisation(user));
    return request;
  }

  private void withCcdAccept(HttpRequestBase request, String accept) {
    request.addHeader("experimental", "true");
    request.addHeader("Accept", accept);
  }

  private record EventResponse(int statusCode, Map<String, Object> body) {
  }
}
