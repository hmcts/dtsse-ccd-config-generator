package uk.gov.hmcts.divorce.cftlib;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.HashMap;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.mockito.ArgumentCaptor;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerAddNote;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerMaintainCaseLink;
import uk.gov.hmcts.divorce.sow014.nfd.DecentralisedCaseworkerAddNote;
import uk.gov.hmcts.divorce.sow014.nfd.FailingSubmittedCallback;
import uk.gov.hmcts.divorce.sow014.nfd.PublishedEvent;
import uk.gov.hmcts.divorce.sow014.nfd.ReturnErrorWhenCreateTestCase;
import uk.gov.hmcts.divorce.sow014.nfd.SubmittedConfirmationCallback;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.Database;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "spring.jms.servicebus.enabled=true",
    "ccd.servicebus.destination=ccd-case-events-test",
    "ccd.servicebus.scheduler-enabled=true",
    "ccd.servicebus.schedule=*/1 * * * * *",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration"
})
@Slf4j
public class TestWithCCD extends CftlibTest {

    @Autowired
    private IdamClient idam;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CoreCaseDataApi ccdApi;

    @Autowired
    NamedParameterJdbcTemplate db;

    @Autowired
    private JmsTemplate jmsTemplate;

    private long firstEventId;
    private static final String BASE_URL = "http://localhost:4452";
    private static final String ACCEPT_CREATE_CASE =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-case.v2+json;charset=UTF-8";
    private static final String ACCEPT_CREATE_EVENT =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-event.v2+json;charset=UTF-8";
    private static final String ACCEPT_CASE =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.case.v2+json;charset=UTF-8";
    private static final String ACCEPT_CASE_EVENTS =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.case-events.v2+json;charset=UTF-8";
    private static final String ACCEPT_UI_CASE_VIEW =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.ui-case-view.v2+json;charset=UTF-8";
    private static final String ACCEPT_UI_EVENT_VIEW =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.ui-event-view.v2+json;charset=UTF-8";
    private static final String ACCEPT_UI_START_EVENT =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.ui-start-event-trigger.v2+json;charset=UTF-8";

    @TestConfiguration
    static class ServiceBusTestConfiguration {

        @Bean
        JmsTemplate jmsTemplate() {
            return mock(JmsTemplate.class);
        }

        @Bean
        ConnectionFactory connectionFactory() {
            return mock(ConnectionFactory.class);
        }
    }

    @Order(1)
    @Test
    public void caseCreation() throws Exception {
        var start = ccdApi.startCase(getAuthorisation("TEST_SOLICITOR@mailinator.com"),
            getServiceAuth(),
            NoFaultDivorce.getCaseType(),
            "create-test-application");

        var startData = mapper.readValue(mapper.writeValueAsString(start.getCaseDetails().getData()), CaseData.class);
        assertThat(startData.getSetInAboutToStart(), equalTo("My custom value"));

        start.getCaseDetails();
        var token = start.getToken();

        var body = Map.of(
            "data", Map.of(
                "applicationType", "soleApplication",
                "applicant1SolicitorRepresented", "No",
                "applicant2SolicitorRepresented", "No"
                // applicant2@gmail.com  =  6e508b49-1fa8-3d3c-8b53-ec466637315b
            ),
            "event", Map.of(
                "id", "create-test-application",
                "summary", "",
                "description", ""
            ),
            "event_token", token,
            "ignore_warning", false,
            "supplementary_data_request", Map.of(
            "$set", Map.of(
                "orgs_assigned_users.organisationA", 22,
                    "baz", "qux"
                ),
            "$inc", Map.of(
                "orgs_assigned_users.organisationB", -4,
                    "foo", 5
                )
            )
        );

        var createCase = buildRequest(
            "TEST_SOLICITOR@mailinator.com",
            BASE_URL + "/data/case-types/" + NoFaultDivorce.getCaseType() + "/cases?ignore-warning=false",
            HttpPost::new);
        withCcdAccept(createCase, ACCEPT_CREATE_CASE);

        createCase.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(createCase);
        var r = new Gson().fromJson(EntityUtils.toString(response.getEntity()), Map.class);
        caseRef = Long.parseLong((String) r.get("id"));
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        assertThat(r.get("state"), equalTo("Submitted"));

        // Check we can load the case
        var c = ccdApi.getCase(getAuthorisation("TEST_SOLICITOR@mailinator.com"), getServiceAuth(), String.valueOf(caseRef));
        assertThat(c.getState(), equalTo("Submitted"));
        assertThat(c.getLastModified(), greaterThan(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)));
        var caseData = mapper.readValue(mapper.writeValueAsString(c.getData()), CaseData.class);
        assertThat(caseData.getApplicant1().getFirstName(), equalTo("app1_first_name"));
        assertThat(caseData.getApplicant2().getFirstName(), equalTo("app2_first_name"));
    }

    private long caseRef;
    @Order(2)
    @Test
    public void addNotes() throws Exception {

        addNote();
        addNote();

        var get = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef,
            HttpGet::new);
        withCcdAccept(get, ACCEPT_CASE);

        var response = HttpClientBuilder.create().build().execute(get);
        var result = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        var data = (Map) result.get("data");
        var caseData = mapper.readValue(mapper.writeValueAsString(data), CaseData.class);
        assertThat(caseData.getNotes().size(), equalTo(2));
        // Notes are ordered by creation desc
        assertThat(caseData.getNotes().get(0).getValue().getNote(), equalTo("Test note 1"));
    }

    @Order(3)
    @Test
    public void getEventHistory() throws Exception {
        var get = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpGet::new);
        withCcdAccept(get, ACCEPT_CASE_EVENTS);

        var response = HttpClientBuilder.create().build().execute(get);
        System.out.println(response.getEntity().getContent());
        Map<String, Object> result
                = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        var auditEvents = (List) result.get("auditEvents");
        assertThat(auditEvents.size(), equalTo(3));
        var eventData = ((Map)auditEvents.get(0)).get("data");
        var caseData = mapper.readValue(mapper.writeValueAsString(eventData), CaseData.class);
        assertThat(caseData.getNotes().size(), equalTo(2));
        var firstEvent = (Map) auditEvents.getLast();
        // First event should be in the 'Holding' state
        assertThat(firstEvent.get("state_id"), equalTo("Submitted"));
        assertThat(firstEvent.get("state_name"), equalTo("Submitted"));

        // Dates should be in UTC time so check it is within 2 mins of now
        String createdDateString = (String) firstEvent.get("created_date");

        LocalDateTime createdLocalDateTime = LocalDateTime.parse(createdDateString);

        Instant actualCreatedInstant = createdLocalDateTime.atZone(ZoneOffset.UTC).toInstant();

        Instant nowUtc = Instant.now();
        Duration maxAllowedDeviation = Duration.ofMinutes(2);
        Duration timeDifference = Duration.between(actualCreatedInstant, nowUtc).abs();
        assertThat(timeDifference, is(lessThanOrEqualTo(maxAllowedDeviation)));
    }

    @Order(4)
    @Test
    public void testAddNoteRunsConcurrently() throws Exception {
        var firstEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), "caseworker-add-note").getToken();

        var data = Map.of("note", "Test note 3");

        // Concurrent change to case notes should be allowed without raising a conflict
        addNote();

        var e = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-add-note",
            data,
            firstEvent);
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
    }

    @Order(5)
    @Test
    public void testOptimisticLockOnJsonBlob() throws Exception {
        var firstEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), "caseworker-update-due-date").getToken();

        var data = Map.of("dueDate", "2020-01-01");

        // Concurrent change to json blob should be rejected
        updateDueDate();

        var e = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-update-due-date",
            data,
            firstEvent);
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(409));
    }

    @Order(6)
    @SneakyThrows
    @Test
    void searchCases() {
        // Give some time to index the case created by the previous test
        await()
            .timeout(Duration.ofSeconds(10))
            .ignoreExceptions()
            .until(this::caseAppearsInSearch);
    }

    @Test
    @Order(7)
    void shouldCreateSupplementaryDataWhenNotExists() throws Exception {
        final String url = BASE_URL + "/cases/" + caseRef + "/supplementary-data";
        var body = """
            {
              "supplementary_data_updates": {
                "$set": {
                  "orgs_assigned_users.organisationA": 22,
                  "baz": "qux"
                },
                "$inc": {
                  "orgs_assigned_users.organisationB": -4,
                  "foo": 5
                }
              }
            }""";

        var request = buildRequest("TEST_CASE_WORKER_USER@mailinator.com",
            url,
            HttpPost::new);

        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(request);

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));

        var result = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        var data = (Map) result.get("supplementary_data");
        assertThat(data.get("orgs_assigned_users.organisationA"), equalTo(22));
        assertThat(data.get("foo"), equalTo(10));
        assertThat(data.get("orgs_assigned_users.organisationB"), equalTo(-8));
        assertThat(data.get("baz"), equalTo("qux"));
    }

    @Test
    @Order(8)
    void shouldUpdateSupplementaryData() throws Exception {
        final String url = BASE_URL + "/cases/" + caseRef + "/supplementary-data";
        var body = """
            {
              "supplementary_data_updates": {
                "$set": {
                  "orgs_assigned_users.organisationA": 21,
                  "foo": 8
                },
                "$inc": {
                  "orgs_assigned_users.organisationB": -4
                }
              }
            }""";

        var request = buildRequest("TEST_CASE_WORKER_USER@mailinator.com",
            url,
            HttpPost::new);

        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(request);

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));

        var result = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        var data = (Map) result.get("supplementary_data");
        assertThat(data.get("orgs_assigned_users.organisationA"), equalTo(21));
        assertThat(data.get("foo"), equalTo(8));
        assertThat(data.get("orgs_assigned_users.organisationB"), equalTo(-12));
    }

    @Test
    @Order(9)
    @SneakyThrows
    void fetchesSupplementaryData() {
        final String url = BASE_URL + "/internal/cases/" + caseRef + "/event-triggers/caseworker-add-note";

        var request = buildRequest("TEST_CASE_WORKER_USER@mailinator.com",
            url,
            HttpGet::new);

        withCcdAccept(request, ACCEPT_UI_START_EVENT);

        var response = HttpClientBuilder.create().build().execute(request);

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));

        var result = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        var supplementaryData = (Map) result.get("supplementary_data");

        assertNotNull(supplementaryData, "Supplementary data should not be null");

        var orgsAssignedUsers = (Map) supplementaryData.get("orgs_assigned_users");
        assertThat(orgsAssignedUsers.get("organisationA"), equalTo(21));
        // Should have been incremented by -4 three times.
        assertThat(orgsAssignedUsers.get("organisationB"), equalTo(-12));
        assertThat(supplementaryData.get("foo"), equalTo(8));
        assertThat(supplementaryData.get("baz"), equalTo("qux"));
    }

    @Test
    @Order(10)
    public void storedCaseDataFiltersExternalFields() throws Exception {
        String sql = "select data::text from ccd.case_data where reference = :ref";
        String storedJson = db.queryForObject(sql, Map.of("ref", caseRef), String.class);

        assertThat("Stored case data should not be null", storedJson, is(notNullValue()));

        var dataNode = mapper.readTree(storedJson);
        assertThat("External field 'note' must be filtered out", dataNode.has("note"), is(false));
        assertThat("External field 'notes' must be filtered out", dataNode.has("notes"), is(false));
        assertThat("Non-external field 'applicationType' should remain",
            dataNode.path("applicationType").asText(), equalTo("soleApplication"));
    }

    @Test
    @Order(11)
    @SneakyThrows
    void testUpdateFailsForUnsupportedOperator() {
        log.info("Testing failure for an unsupported operator on case {}", caseRef);
        final String url = BASE_URL + "/cases/" + caseRef + "/supplementary-data";
        // "$push" is not a supported operator according to the LLD.
        var body = """
            {
              "supplementary_data_updates": {
                "$push": {
                  "someArray": "value"
                }
              }
            }""";

        var request = buildRequest("TEST_CASE_WORKER_USER@mailinator.com", url, HttpPost::new);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(request);

        assertThat("Response code should be 400 Bad Request for unsupported operator",
            response.getStatusLine().getStatusCode(), equalTo(400));
    }

    @SneakyThrows
    @Order(12)
    @Test
    public void submittedCallbackResponseIsPropagated() {
        var token = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            SubmittedConfirmationCallback.EVENT_ID).getToken();

        var body = Map.of(
            "data", Collections.<String, Object>emptyMap(),
            "event", Map.of(
                "id", SubmittedConfirmationCallback.EVENT_ID,
                "summary", "confirmation header test",
                "description", "confirmation header test"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var request = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(request, ACCEPT_CREATE_EVENT);

        request.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        var responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> result = mapper.readValue(responseBody, new TypeReference<>() {});
        assertThat("after_submit_callback_response should be present",
            result.containsKey("after_submit_callback_response"), is(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> afterSubmit = (Map<String, Object>) result.get("after_submit_callback_response");

        assertThat(afterSubmit.get("confirmation_header"),
            equalTo(SubmittedConfirmationCallback.CONFIRMATION_HEADER));
        assertThat(afterSubmit.get("confirmation_body"),
            equalTo(SubmittedConfirmationCallback.CONFIRMATION_BODY));
        assertThat(result.get("callback_response_status_code"), equalTo(200));
        assertThat(result.get("callback_response_status"), equalTo("CALLBACK_COMPLETED"));
    }

    @Order(13)
    @Test
    public void testEventSubmissionIsIdempotent() throws Exception {
        var firstEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), "caseworker-add-note").getToken();

        var data = Map.of("note", "Test idempotence note should only appear once");
        var e = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-add-note",
            data,
            firstEvent);
        String sql = "SELECT count(*) FROM case_notes";
        Integer initialCount = db.queryForObject(sql, Map.of(), Integer.class);
        var response = HttpClientBuilder.create().build().execute(e);
        // Resubmit the same event a second time which should have no effect.
        HttpClientBuilder.create().build().execute(e);
        Integer thirdCount = db.queryForObject(sql, Map.of(), Integer.class);
        assertThat("The note count should increment by exactly one.", thirdCount, equalTo(initialCount + 1));
    }

    @Order(14)
    @Test
    public void getEventHistory_ShouldReturnAllEventsWithCorrectState() throws Exception {
        String url = String.format("http://localhost:4452/internal/cases/%s", caseRef);

        var get = buildRequest("TEST_CASE_WORKER_USER@mailinator.com", url, HttpGet::new);
        withCcdAccept(get, ACCEPT_UI_CASE_VIEW);

        var response = HttpClientBuilder.create().build().execute(get);

        Map<String, Object> result = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});

        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));

        List auditEvents = (List) result.get("events");
        assertThat("Incorrect number of events found", auditEvents.size(), equalTo(8));

        // Get the oldest event (the creation event), which is the last in the list
        var firstEvent = (Map) auditEvents.get(auditEvents.size() - 1);
        assertThat("First event should be in the 'Submitted' state", firstEvent.get("state_id"), equalTo("Submitted"));
        assertThat("First event should have the state name 'Submitted'", firstEvent.get("state_name"), equalTo("Submitted"));

        this.firstEventId = Long.valueOf(firstEvent.get("id").toString());
    }

    @Order(15)
    @Test
    public void getCaseEventById() throws Exception {
        // 1. Build the request URL with the stored caseRef and firstEventId
        final String url = "http://localhost:4452/internal/cases/" + caseRef + "/events/" + firstEventId;
        var get = buildRequest("TEST_CASE_WORKER_USER@mailinator.com", url, HttpGet::new);

        // 2. Add required headers
        withCcdAccept(get, ACCEPT_UI_EVENT_VIEW);

        // 3. Execute the request
        var response = HttpClientBuilder.create().build().execute(get);
        String responseBody = EntityUtils.toString(response.getEntity());

        // 4. Assert status code
        assertEquals(200, response.getStatusLine().getStatusCode(), "Expected HTTP 200 OK");

        // 5. Deserialize and assert the response body
        Map<String, Object> result = mapper.readValue(responseBody, new TypeReference<>() {});
        assertThat(result.get("case_id"), equalTo(String.valueOf(caseRef)));

        Map event = (Map) result.get("event");
        assertNotNull(event, "Event object should not be null");

        // The 'id' in the event object is the event's internal ID, which is what we queried for
        assertThat(((Number)event.get("id")).longValue(), equalTo(firstEventId));
        // The 'event_id' is the string identifier from the definition
        assertThat(event.get("event_id"), equalTo("create-test-application"));
        assertThat(event.get("event_name"), equalTo("Create test case"));
    }

    @SneakyThrows
    @Order(16)
    @Test
    public void testSubmittedCallback() {
        var token = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), FailingSubmittedCallback.class.getSimpleName()).getToken();

        var body = Map.of(
            "data", Map.of(
                "note", "Test!"
            ),
            "event", Map.of(
                "id", FailingSubmittedCallback.class.getSimpleName(),
                "summary", "summary",
                "description", "description"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var e = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(e, ACCEPT_CREATE_EVENT);

        e.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        assertThat(FailingSubmittedCallback.callbackAttempts, equalTo(3));
    }

    @SneakyThrows
    @Order(18)
    @Test
    public void duplicateSubmittedCallbackShouldNotReRun() {
        // Reset counter on existing failing submitted callback
        FailingSubmittedCallback.callbackAttempts = 0;

        // Obtain a single event token and reuse it for both submissions
        var token = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), FailingSubmittedCallback.class.getSimpleName()).getToken();

        var body = Map.of(
            "data", Map.of(
                "note", "dup-submitted-callback-test"
            ),
            "event", Map.of(
                "id", FailingSubmittedCallback.class.getSimpleName(),
                "summary", "summary",
                "description", "description"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var req1 = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(req1, ACCEPT_CREATE_EVENT);

        req1.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var resp1 = HttpClientBuilder.create().build().execute(req1);
        assertThat(resp1.getStatusLine().getStatusCode(), equalTo(201));

        // Second submission with the SAME event token (duplicate request)
        var req2 = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(req2, ACCEPT_CREATE_EVENT);
        req2.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var resp2 = HttpClientBuilder.create().build().execute(req2);
        assertThat(resp2.getStatusLine().getStatusCode(), equalTo(201));

        // Desired behaviour: only initial 3 attempts (from retry policy), not run again on duplicate
        assertThat("Submitted callback must not re-run for duplicate request",
            FailingSubmittedCallback.callbackAttempts, equalTo(3));
    }

    @SneakyThrows
    @Order(17)
    @Test
    public void testPublishingToMessageOutbox() {
        db.update("DELETE FROM ccd.message_queue_candidates", Map.of());

        var token = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), PublishedEvent.class.getSimpleName()).getToken();

        var body = Map.of(
            "data", Map.of(
                "note", "Test!"
            ),
            "event", Map.of(
                "id", PublishedEvent.class.getSimpleName(),
                "summary", "summary",
                "description", "description"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var e = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(e, ACCEPT_CREATE_EVENT);

        e.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        Integer totalMessages = db.queryForObject("SELECT count(*) FROM ccd.message_queue_candidates", Map.of(), Integer.class);
        assertThat(totalMessages, equalTo(1));

        String noteCheck = """
            SELECT message_information->'AdditionalData'->'Data'->>'note'
             FROM ccd.message_queue_candidates
             WHERE reference = :caseReference 
             """;

        String retrievedNote = db.queryForObject(noteCheck, Map.of("caseReference", caseRef), String.class);
        assertThat(retrievedNote, equalTo("Test!"));

        // Verify the EventTimeStamp from the JSON blob
        String timestampCheckSql = """
            SELECT message_information->>'EventTimeStamp'
             FROM ccd.message_queue_candidates 
             WHERE reference = :caseReference """;
        String retrievedTimestampStr = db.queryForObject(timestampCheckSql, Map.of("caseReference", caseRef), String.class);
        assertThat(retrievedTimestampStr, is(notNullValue()));
        // Validate it's a parsable timestamp and it is recent
        LocalDateTime eventTimestamp = LocalDateTime.parse(retrievedTimestampStr);
        assertThat(eventTimestamp, is(greaterThan(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))));

        reset(jmsTemplate);

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            verify(jmsTemplate, atLeastOnce()).convertAndSend(eq("ccd-case-events-test"), payloadCaptor.capture(), postProcessorCaptor.capture())
        );

        JsonNode payload = payloadCaptor.getValue();
        assertThat(payload.path("AdditionalData").path("Data").path("note").asText(), equalTo("Test!"));

        Map<String, String> capturedProperties = new HashMap<>();
        Message jmsMessage = mock(Message.class);
        doAnswer(invocation -> {
            capturedProperties.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jmsMessage).setStringProperty(anyString(), anyString());

        postProcessorCaptor.getValue().postProcessMessage(jmsMessage);

        assertThat(capturedProperties.get("case_id"), equalTo(String.valueOf(caseRef)));
        assertThat(capturedProperties.get("case_type_id"), equalTo(NoFaultDivorce.getCaseType()));
        assertThat(capturedProperties.get("event_id"), equalTo(PublishedEvent.class.getSimpleName()));

        var publishedTimestamp = db.queryForObject(
            "SELECT published FROM ccd.message_queue_candidates WHERE reference = :caseReference ORDER BY id DESC LIMIT 1",
            Map.of("caseReference", caseRef),
            LocalDateTime.class
        );
        assertThat(publishedTimestamp, is(notNullValue()));
    }

    @SneakyThrows
    @Order(18)
    @Test
    public void testReturnErrorWhenCreateTestCase() {
        log.info("Testing that a case create that returns errors is rolled back in CCD");

        var initialPointerCount = getDataStoreCasePointerCount();

        // 1. Get initial case count from CCD database
        String sql = "SELECT count(*) FROM ccd.case_data";
        Integer initialCaseCount = db.queryForObject(sql, Map.of(), Integer.class);
        assertNotNull(initialCaseCount);

        // 2. Start the event to get a valid event token
        var start = ccdApi.startCase(getAuthorisation("TEST_SOLICITOR@mailinator.com"),
            getServiceAuth(),
            NoFaultDivorce.getCaseType(),
            ReturnErrorWhenCreateTestCase.class.getSimpleName());
        var token = start.getToken();

        // 3. Construct the request body for the event that is designed to fail
        var body = Map.of(
            "data", Map.of(
                "applicationType", "soleApplication" // Mandatory field as per event definition
            ),
            "event", Map.of(
                "id", ReturnErrorWhenCreateTestCase.class.getSimpleName(),
                "summary", "Test summary for failing case creation",
                "description", "Testing rollback of case pointer"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        // 4. Build and execute the POST request to submit the case
        var createCaseRequest = buildRequest(
            "TEST_SOLICITOR@mailinator.com",
            BASE_URL + "/data/case-types/" + NoFaultDivorce.getCaseType() + "/cases?ignore-warning=false",
            HttpPost::new);
        withCcdAccept(createCaseRequest, ACCEPT_CREATE_CASE);
        createCaseRequest.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(createCaseRequest);

        // 5. Assert the response indicates failure with the correct error
        assertThat("Expected HTTP 422 Unprocessable Entity",
            response.getStatusLine().getStatusCode(), equalTo(422));

        Integer finalCaseCount = db.queryForObject(sql, Map.of(), Integer.class);
        assertThat("Case count should not increment on failed submission", finalCaseCount, equalTo(initialCaseCount));

        var finalPointerCount = getDataStoreCasePointerCount();
        assertThat("Case pointer count should not increment on failed submission", finalPointerCount, equalTo(initialPointerCount));
    }

    @SneakyThrows
    private int getDataStoreCasePointerCount() {
        String sql = "SELECT COUNT(*) FROM case_data";
        int caseCount = 0;

        try (Connection dataStoredb = super.cftlib().getConnection(Database.Datastore);
             PreparedStatement statement = dataStoredb.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            if (rs.next()) {
                caseCount = rs.getInt(1);
            }
        }
        return caseCount;
    }

    @SneakyThrows
    private Boolean caseAppearsInSearch() {
        var request = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/data/internal/searchCases?ctid=" + NoFaultDivorce.getCaseType() + "&page=1",
            HttpPost::new);
        var query = String.format("""
            {
              "native_es_query":{
                "from":0,
                "query":{
                  "bool":{
                    "filter":[
                      {"term":{"reference":%d}}
                    ]
                  }
                },
                "size":25,
                "sort":[{"_id": "desc"}]
              },
              "supplementary_data":["*"]
            }
            """, caseRef);
        request.setEntity(new StringEntity(query, ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        var r = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        var aCase = (Map) ((List)r.get("cases")).get(0);
        var fields = (Map) aCase.get("fields");
        assertThat(fields.get("applicant1FirstName"), equalTo("app1_first_name"));
        assertThat(fields.get("applicant2FirstName"), equalTo("app2_first_name"));
        assertThat(((List)fields.get("notes")).size(), equalTo(4));

        assertThat(fields.get("[LAST_MODIFIED_DATE]"), notNullValue());
        assertThat(fields.get("[LAST_STATE_MODIFIED_DATE]"), notNullValue());

        return true;
    }

    private long createAdditionalCase(String solicitorEmail) throws Exception {
        var start = ccdApi.startCase(
            getAuthorisation(solicitorEmail),
            getServiceAuth(),
            NoFaultDivorce.getCaseType(),
            "create-test-application");

        var body = Map.of(
            "data", Map.of(
                "applicationType", "soleApplication",
                "applicant1SolicitorRepresented", "No",
                "applicant2SolicitorRepresented", "No"
            ),
            "event", Map.of(
                "id", "create-test-application",
                "summary", "",
                "description", ""
            ),
            "event_token", start.getToken(),
            "ignore_warning", false,
            "supplementary_data_request", Map.of(
                "$set", Map.of(
                    "orgs_assigned_users.organisationA", 22,
                    "baz", "qux"
                ),
                "$inc", Map.of(
                    "orgs_assigned_users.organisationB", -4,
                    "foo", 5
                )
            )
        );

        var createCase = buildRequest(
            solicitorEmail,
            BASE_URL + "/data/case-types/" + NoFaultDivorce.getCaseType() + "/cases?ignore-warning=false",
            HttpPost::new);
        withCcdAccept(createCase, ACCEPT_CREATE_CASE);
        createCase.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(createCase);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        var responseBody = EntityUtils.toString(response.getEntity());
        Map<?, ?> createdCase = new Gson().fromJson(responseBody, Map.class);
        return Long.parseLong((String) createdCase.get("id"));
    }

    private List<Map<String, Object>> buildCaseLinksPayload(long... caseReferences) {
        return Arrays.stream(caseReferences)
            .mapToObj(ref -> Map.<String, Object>of(
                "id", UUID.randomUUID().toString(),
                "value", Map.of(
                    "CaseReference", formatCaseReference(ref),
                    "CaseType", NoFaultDivorce.getCaseType()
                )
            ))
            .toList();
    }

    private void submitCaseLinksUpdate(List<Map<String, Object>> caseLinksPayload, String note) throws Exception {
        String user = "TEST_CASE_WORKER_USER@mailinator.com";
        String userToken = getAuthorisation(user);
        String serviceToken = getServiceAuth();
        String userId = idam.getUserInfo(userToken).getUid();

        StartEventResponse start = ccdApi.startEventForCaseWorker(
            userToken,
            serviceToken,
            userId,
            NoFaultDivorce.JURISDICTION,
            NoFaultDivorce.getCaseType(),
            String.valueOf(caseRef),
            CaseworkerMaintainCaseLink.CASEWORKER_MAINTAIN_CASE_LINK
        );

        Map<String, Object> data = new HashMap<>(start.getCaseDetails().getData());
        data.put("caseLinks", caseLinksPayload);

        CaseDataContent content = CaseDataContent.builder()
            .eventToken(start.getToken())
            .event(Event.builder()
                .id(CaseworkerMaintainCaseLink.CASEWORKER_MAINTAIN_CASE_LINK)
                .summary(note)
                .description(note)
                .build())
            .data(data)
            .build();

        CaseDetails response = ccdApi.submitEventForCaseWorker(
            userToken,
            serviceToken,
            userId,
            NoFaultDivorce.JURISDICTION,
            NoFaultDivorce.getCaseType(),
            String.valueOf(caseRef),
            true,
            content
        );

        assertThat(response.getState(), notNullValue());
    }

    private String formatCaseReference(long reference) {
        return String.format("%016d", reference);
    }

    private List<CaseLinkRow> fetchCaseLinks(long caseReference) {
        String sql = """
            SELECT linked.reference AS linked_reference,
                   link.standard_link
              FROM case_link link
              JOIN case_data linked ON linked.id = link.linked_case_id
             WHERE link.case_id = (SELECT id FROM case_data WHERE reference = ?)
            """;

        List<CaseLinkRow> rows = new ArrayList<>();
        try (Connection connection = cftlib().getConnection(Database.Datastore);
             Statement schema = connection.createStatement()) {
            schema.execute("SET search_path TO public");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, caseReference);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new CaseLinkRow(rs.getLong("linked_reference"), rs.getBoolean("standard_link")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load case_link data", e);
        }
        return rows;
    }

    private void updateDueDate() throws Exception {
        var e = prepareEventRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-update-due-date",
            Map.of("dueDate", "2020-01-01")
        );
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
    }


    private static int noteCount = 0;
    private void addNote() throws Exception {
        var e = prepareEventRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-add-note",
            Map.of("note", "Test note " + noteCount++)
        );
        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
    }

    private record CaseLinkRow(long linkedReference, boolean standardLink) { }

    private String getAuthorisation(String user) {
        return idam.getAccessToken(user, "");
    }

    private String getServiceAuth() {
        return cftlib().generateDummyS2SToken("ccd_gw");
    }

    <T extends HttpRequestBase> T buildRequest(String user, String url, Function<String, T> ctor) {
        var request = ctor.apply(url);
        var token = idam.getAccessToken(user, "");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("ServiceAuthorization", cftlib().generateDummyS2SToken("ccd_gw"));
        request.addHeader("Authorization",  token);
        return request;
    }

    private void withCcdAccept(HttpRequestBase request, String accept) {
        request.addHeader("experimental", "true");
        request.addHeader("Accept", accept);
    }

    private HttpPost prepareEventRequestWithToken(String user, String eventId, Map<String, ?> data, String token) {
        var body = Map.of(
            "data", data,
            "event", Map.of(
                "id", eventId,
                "summary", "summary",
                "description", "description"
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var e = buildRequest(user, BASE_URL + "/cases/" + caseRef + "/events", HttpPost::new);
        withCcdAccept(e, ACCEPT_CREATE_EVENT);
        e.setEntity(new StringEntity(new Gson().toJson(body), ContentType.APPLICATION_JSON));
        return e;
    }

    private HttpPost prepareEventRequest(String user, String eventId, Map<String, ?> data) {
        var token = ccdApi.startEvent(getAuthorisation(user), getServiceAuth(), String.valueOf(caseRef), eventId).getToken();
        return prepareEventRequestWithToken(user, eventId, data, token);
    }

    @Order(19)
    @Test
    public void testDecentralisedEventStartAndSubmitHandlers() throws Exception {
        // Verify start handler pre-populates data
        var start = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef), DecentralisedCaseworkerAddNote.CASEWORKER_DECENTRALISED_ADD_NOTE);

        var startData = mapper.readValue(mapper.writeValueAsString(start.getCaseDetails().getData()), CaseData.class);
        assertThat(startData.getNote(), startsWith("[start] set by "));

        // Verify submit handler persists the note
        String noteText = "Decentralised test note";
        String sqlCount = "SELECT count(*) FROM case_notes";
        Integer before = db.queryForObject(sqlCount, Map.of(), Integer.class);

        var e = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            DecentralisedCaseworkerAddNote.CASEWORKER_DECENTRALISED_ADD_NOTE,
            Map.of("note", noteText),
            start.getToken());

        var response = HttpClientBuilder.create().build().execute(e);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        var responsePayload = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        var afterSubmit = (Map) responsePayload.get("after_submit_callback_response");
        assertThat(afterSubmit, is(notNullValue()));
        assertThat(afterSubmit.get("confirmation_header"), equalTo("Decentralised submission complete"));
        assertThat(afterSubmit.get("confirmation_body"), equalTo("Case note saved successfully."));

        Integer after = db.queryForObject(sqlCount, Map.of(), Integer.class);
        assertThat(after, equalTo(before + 1));

        String lastNoteSql = "SELECT note FROM case_notes WHERE reference = :ref ORDER BY id DESC LIMIT 1";
        String latestNote = db.queryForObject(lastNoteSql, Map.of("ref", caseRef), String.class);
        assertThat(latestNote, equalTo(noteText));
    }

    @Order(20)
    @Test
    public void shouldPersistCaseLinksAndUpdateDerivedTable() throws Exception {
        long firstLinkedCase = createAdditionalCase("TEST_SOLICITOR2@mailinator.com");
        long secondLinkedCase = createAdditionalCase("solicitora@gmail.com");

        submitCaseLinksUpdate(buildCaseLinksPayload(firstLinkedCase, secondLinkedCase), "Initial linked cases");

        var initialPersistedLinks = fetchCaseLinks(caseRef);
        assertThat(initialPersistedLinks, hasSize(2));
        assertThat(initialPersistedLinks.stream().map(CaseLinkRow::linkedReference).toList(),
            containsInAnyOrder(firstLinkedCase, secondLinkedCase));
        assertThat(initialPersistedLinks.stream().map(CaseLinkRow::standardLink).toList(), everyItem(is(true)));

        var caseDetails = ccdApi.getCase(getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef));
        var caseData = mapper.readValue(mapper.writeValueAsString(caseDetails.getData()), CaseData.class);
        List<ListValue<CaseLink>> caseLinks = caseData.getCaseLinks();
        assertThat(caseLinks, is(notNullValue()));
        assertThat(caseLinks.size(), equalTo(2));
        assertThat(caseLinks.stream()
            .map(listValue -> listValue.getValue().getCaseReference())
            .toList(), containsInAnyOrder(formatCaseReference(firstLinkedCase), formatCaseReference(secondLinkedCase)));

        long replacementLinkedCase = createAdditionalCase("solicitorb@gmail.com");
        submitCaseLinksUpdate(buildCaseLinksPayload(replacementLinkedCase), "Updated linked cases");

        var updatedPersistedLinks = fetchCaseLinks(caseRef);
        assertThat(updatedPersistedLinks, hasSize(1));
        assertThat(updatedPersistedLinks.get(0).linkedReference(), equalTo(replacementLinkedCase));
        assertThat(updatedPersistedLinks.get(0).standardLink(), is(true));

        var refreshedCaseDetails = ccdApi.getCase(getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(), String.valueOf(caseRef));
        var refreshedCaseData = mapper.readValue(mapper.writeValueAsString(refreshedCaseDetails.getData()), CaseData.class);
        List<ListValue<CaseLink>> refreshedCaseLinks = refreshedCaseData.getCaseLinks();
        assertThat(refreshedCaseLinks, is(notNullValue()));
        assertThat(refreshedCaseLinks.size(), equalTo(1));
        assertThat(refreshedCaseLinks.get(0).getValue().getCaseReference(),
            equalTo(formatCaseReference(replacementLinkedCase)));
    }

    @SneakyThrows
    @Order(100)
    @Test
    void casePointerRemainsImmutable() {
        try (Connection connection = cftlib().getConnection(Database.Datastore);
             PreparedStatement statement = connection.prepareStatement("""
                SELECT state,
                       data::text AS data,
                       data_classification::text AS data_classification,
                       supplementary_data::text AS supplementary_data,
                       security_classification,
                       last_state_modified_date,
                       resolved_ttl,
                       created_date,
                       last_modified,
                       version
                   FROM case_data
                  WHERE reference = ?
                    AND security_classification = 'RESTRICTED'
                 """)) {
            statement.setLong(1, caseRef);

            try (ResultSet rs = statement.executeQuery()) {
                assertThat("Expected case pointer to exist for case " + caseRef, rs.next(), is(true));

                assertThat("Case pointer state must remain blank", rs.getString("state"), equalTo(""));

                var dataNode = mapper.readTree(rs.getString("data"));
                assertThat("Case pointer data must remain empty", dataNode.isObject() && dataNode.isEmpty(), is(true));

                var classificationNode = mapper.readTree(rs.getString("data_classification"));
                assertThat("Case pointer data classification must remain empty",
                    classificationNode.isObject() && classificationNode.isEmpty(), is(true));

                assertThat("Case pointer must not record supplementary data",
                    rs.getString("supplementary_data"), is(nullValue()));

                assertThat("Case pointer security classification must remain RESTRICTED",
                    rs.getString("security_classification"), equalTo("RESTRICTED"));

                var createdTimestamp = rs.getTimestamp("created_date");
                assertThat("Case pointer should report its creation timestamp", createdTimestamp, is(notNullValue()));

                var lastStateModified = rs.getTimestamp("last_state_modified_date");
                assertThat("Case pointer must not set last_state_modified_date", lastStateModified, is(nullValue()));

                assertThat("Case pointer must not set a resolved TTL", rs.getDate("resolved_ttl"), is(nullValue()));

                var lastModified = rs.getTimestamp("last_modified");
                if (lastModified != null) {
                    var delta = Duration.between(createdTimestamp.toInstant(), lastModified.toInstant()).abs();
                    assertThat("Case pointer last_modified should remain at initial value",
                        delta, lessThanOrEqualTo(Duration.ofSeconds(1)));
                }
            }
        }
    }

}
