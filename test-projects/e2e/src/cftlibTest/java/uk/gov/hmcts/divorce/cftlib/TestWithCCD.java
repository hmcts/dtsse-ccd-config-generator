package uk.gov.hmcts.divorce.cftlib;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.HashMap;
import java.util.stream.StreamSupport;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.impl.client.CloseableHttpClient;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessagePostProcessor;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.divorce.divorcecase.model.CaseData;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.simplecase.SimpleCaseConfiguration;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseData;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerMaintainCaseLink;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerPopulateSearchCriteria;
import uk.gov.hmcts.divorce.sow014.nfd.DecentralisedCaseworkerAddNote;
import uk.gov.hmcts.divorce.sow014.nfd.DecentralisedCaseworkerAddNoteFailure;
import uk.gov.hmcts.divorce.sow014.nfd.FailingSubmittedCallback;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerRoundTripData;
import uk.gov.hmcts.divorce.sow014.nfd.PublishedEvent;
import uk.gov.hmcts.divorce.sow014.nfd.ReturnErrorWhenCreateTestCase;
import uk.gov.hmcts.divorce.sow014.nfd.SubmittedConfirmationCallback;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.CaseReindexingService;
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
    private CaseReindexingService reindexQueueService;

    @Autowired
    private JmsTemplate jmsTemplate;

    private long firstEventId;
    private static final String BASE_URL = "http://localhost:4452";
    private static final String SERVICE_BASE_URL = "http://localhost:4013";
    private static final String ELASTICSEARCH_BASE_URL = "http://localhost:9200";
    private static final String TASK_MANAGEMENT_BASE_URL = "http://localhost:8087";
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
    private static final String API_FIRST_TASK_EVENT_ID = "api-first-create-task";

    @TestConfiguration
    static class ServiceBusTestConfiguration {

        @Bean
        ConnectionFactory connectionFactory() {
            ConnectionFactory factory = mock(ConnectionFactory.class);
            jakarta.jms.Connection connection = mock(jakarta.jms.Connection.class);
            jakarta.jms.Session session = mock(jakarta.jms.Session.class);
            jakarta.jms.MessageProducer producer = mock(jakarta.jms.MessageProducer.class);
            jakarta.jms.Queue queue = mock(jakarta.jms.Queue.class);
            jakarta.jms.Topic topic = mock(jakarta.jms.Topic.class);

            try {
                when(factory.createConnection()).thenReturn(connection);
                when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
                when(session.createQueue(anyString())).thenReturn(queue);
                when(session.createTopic(anyString())).thenReturn(topic);
                when(session.createProducer(any())).thenReturn(producer);
            } catch (jakarta.jms.JMSException ex) {
                throw new RuntimeException(ex);
            }
            return factory;
        }

        @Bean
        static BeanPostProcessor jmsTemplateSpyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof JmsTemplate jmsTemplate) {
                        JmsTemplate spy = spy(jmsTemplate);
                        doNothing().when(spy).convertAndSend(anyString(), any(), any(MessagePostProcessor.class));
                        return spy;
                    }
                    return bean;
                }
            };
        }
    }

    @Order(50)
    @Test
    public void replayedSubmissionShouldReturnOriginalSnapshot() throws Exception {
        var noteText = "Snapshot note " + UUID.randomUUID();
        Map<String, Object> eventData = Map.of("note", noteText);

        var startEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            "caseworker-add-note");

        var initialRequest = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-add-note",
            eventData,
            startEvent.getToken()
        );

        var initialResponse = HttpClientBuilder.create().build().execute(initialRequest);
        assertThat(initialResponse.getStatusLine().getStatusCode(), equalTo(201));

        String initialBody = EntityUtils.toString(initialResponse.getEntity());
        JsonNode initialPayload = mapper.readTree(initialBody);
        JsonNode initialCaseDataNode = initialPayload.get("data");
        assertThat("case data should be present on first submission", initialCaseDataNode, is(notNullValue()));
        Map<String, Object> initialCaseData =
            mapper.convertValue(initialCaseDataNode, new TypeReference<Map<String, Object>>() {});

        // mutate the case with a different event to simulate later activity
        addNote();

        var replayRequest = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            "caseworker-add-note",
            eventData,
            startEvent.getToken()
        );

        var replayResponse = HttpClientBuilder.create().build().execute(replayRequest);
        assertThat(replayResponse.getStatusLine().getStatusCode(), equalTo(201));

        String replayBody = EntityUtils.toString(replayResponse.getEntity());
        JsonNode replayPayload = mapper.readTree(replayBody);
        JsonNode replayCaseDataNode = replayPayload.get("data");
        assertThat("case data should be present on replay submission", replayCaseDataNode, is(notNullValue()));
        Map<String, Object> replayCaseData =
            mapper.convertValue(replayCaseDataNode, new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        var initialNotes = (List<Map<String, Object>>) initialCaseData.get("notes");
        @SuppressWarnings("unchecked")
        var replayNotes = (List<Map<String, Object>>) replayCaseData.get("notes");

        assertThat("Initial submission should include notes", initialNotes, is(notNullValue()));
        assertThat("Replay response should include notes", replayNotes, is(notNullValue()));

        assertThat(
            String.format(
                "Snapshot should have same note count as original submission (expected %d, got %d)",
                initialNotes.size(),
                replayNotes.size()
            ),
            replayNotes.size(),
            equalTo(initialNotes.size())
        );

        assertThat("Entire case data payload should match the original event snapshot",
            replayCaseData, equalTo(initialCaseData));
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

        var documentId = "12345678-1234-1234-1234-123456789012";
        var documentBaseUrl = "http://localhost/documents/" + documentId;
        var body = Map.of(
            "data", Map.ofEntries(
                Map.entry("applicationType", "soleApplication"),
                Map.entry("applicant1SolicitorRepresented", "No"),
                Map.entry("applicant2SolicitorRepresented", "No"),
                Map.entry("testDocument", Map.of(
                    "document_url", documentBaseUrl,
                    "document_binary_url", documentBaseUrl + "/binary",
                    "document_filename", "test.pdf"
                ))
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

        createCase.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
        var response = HttpClientBuilder.create().build().execute(createCase);
        var responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> r = mapper.readValue(responseBody, new TypeReference<>() {});
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
        assertThat(caseData.getTestDocument(), is(notNullValue()));
        assertThat(caseData.getTestDocument().getFilename(), equalTo("test.pdf"));
    }

    @Order(21)
    @Test
    public void shouldUpdateDocumentMetadataViaCaseFileViewEndpoint() throws Exception {
        var currentCase = ccdApi.getCase(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef)
        );

        Integer version = currentCase.getVersion();
        if (version == null) {
            version = db.queryForObject(
                "SELECT version FROM ccd.case_data WHERE reference = :ref",
                Map.of("ref", caseRef),
                Integer.class
            );
        }
        assertThat(version, is(notNullValue()));
        System.out.println("DEBUG case version before document update: " + version);
        ObjectNode body = mapper.createObjectNode();
        body.put("attribute_path", "testDocument");
        body.put("case_version", version);
        body.putNull("category_id");

        var request = buildRequest("TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/documentData/caseref/" + caseRef,
            HttpPut::new);
        request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(request);
        var responseBody = EntityUtils.toString(response.getEntity());
        int status = response.getStatusLine().getStatusCode();
        assertThat("document update response: " + responseBody,
            status, equalTo(200));
        assertThat("document update response: " + responseBody,
            responseBody, not(containsString("big test gap!!!!")));

        if (status == 200) {
            Map<String, Object> categoriesAndDocuments = mapper.readValue(responseBody, new TypeReference<>() {});
            assertThat(categoriesAndDocuments.get("case_version"), equalTo(version));
        }
    }

    private long caseRef;
    private long simpleCaseRef;

    @Order(199)
    @Test
    public void shouldPopulateSearchCriteriaFromAboutToSubmitCallback() throws Exception {
        var startEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            CaseworkerPopulateSearchCriteria.CASEWORKER_POPULATE_SEARCH_CRITERIA
        ).getToken();

        var event = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            CaseworkerPopulateSearchCriteria.CASEWORKER_POPULATE_SEARCH_CRITERIA,
            Map.of(),
            startEvent);
        var response = HttpClientBuilder.create().build().execute(event);
        var responseBody = EntityUtils.toString(response.getEntity());
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        Map<String, Object> responseMap =
            mapper.readValue(responseBody, new TypeReference<>() {});
        Map data = (Map) responseMap.get("data");
        Map<String, Object> searchCriteria = (Map<String, Object>) data.get("SearchCriteria");

        assertThat("SearchCriteria should be populated from global search processor", searchCriteria, is(notNullValue()));
        List<Map<String, Object>> otherCaseReferences =
            (List<Map<String, Object>>) searchCriteria.get("OtherCaseReferences");
        assertThat(otherCaseReferences, is(notNullValue()));
        assertThat(otherCaseReferences, hasSize(1));
        assertThat(otherCaseReferences.getFirst().get("value"), equalTo(CaseData.formatCaseRef(caseRef)));
    }

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
    @Order(199)
    void shouldPropagateSupplementaryDataChangesToElasticSearch() throws Exception {
        final int expectedFooValue = 123;

        Long caseDataId = db.queryForObject(
            "SELECT id FROM ccd.case_data WHERE reference = :ref",
            Map.of("ref", caseRef),
            Long.class);
        assertThat("Case data id should not be null", caseDataId, is(notNullValue()));

        var updateBody = """
            {
              "supplementary_data_updates": {
                "$set": {
                  "foo": 123
                }
              }
            }""";

        var updateRequest = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/supplementary-data",
            HttpPost::new);
        updateRequest.setEntity(new StringEntity(updateBody, ContentType.APPLICATION_JSON));

        var updateResponse = HttpClientBuilder.create().build().execute(updateRequest);
        assertThat(updateResponse.getStatusLine().getStatusCode(), equalTo(200));

        var payload = mapper.readTree(EntityUtils.toString(updateResponse.getEntity()));
        var supplementaryData = payload.path("supplementary_data");
        assertThat("Supplementary data response should contain foo", supplementaryData.path("foo").asInt(-1),
            equalTo(expectedFooValue));

        Integer fooInDb = db.queryForObject(
            "SELECT (supplementary_data->>'foo')::integer FROM ccd.case_data WHERE reference = :ref",
            Map.of("ref", caseRef),
            Integer.class);
        assertThat("Supplementary data in datastore should reflect update", fooInDb, equalTo(expectedFooValue));

        await()
            .pollInterval(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                var esRequest = new HttpGet(ELASTICSEARCH_BASE_URL + "/e2e_cases/_doc/" + caseDataId);
                var esResponse = HttpClientBuilder.create().build().execute(esRequest);
                assertThat(esResponse.getStatusLine().getStatusCode(), equalTo(200));

                var esPayload = mapper.readTree(EntityUtils.toString(esResponse.getEntity()));
                var source = esPayload.path("_source");
                assertThat("Elasticsearch document should contain _source", source.isMissingNode(), is(false));
                var fooValue = source.path("supplementary_data").path("foo").asInt(-1);
                assertThat("Supplementary data in Elasticsearch should reflect latest value",
                    fooValue, equalTo(expectedFooValue));
            });
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

        request.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
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

        e.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
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

        req1.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
        var resp1 = HttpClientBuilder.create().build().execute(req1);
        assertThat(resp1.getStatusLine().getStatusCode(), equalTo(201));

        // Second submission with the SAME event token (duplicate request)
        var req2 = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/cases/" + caseRef + "/events",
            HttpPost::new);
        withCcdAccept(req2, ACCEPT_CREATE_EVENT);
        req2.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
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
        clearInvocations(jmsTemplate);

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

        e.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
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
    @Order(19)
    @Test
    public void apiFirstTaskShouldBeCreatedViaOutboxAndPoller() {
        db.update("DELETE FROM ccd.task_outbox", Map.of());

        var startEvent = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            API_FIRST_TASK_EVENT_ID
        );

        var request = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            API_FIRST_TASK_EVENT_ID,
            Map.of("note", "api-first-task"),
            startEvent.getToken()
        );

        var response = HttpClientBuilder.create().build().execute(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = db.queryForObject("SELECT count(*) FROM ccd.task_outbox", Map.of(), Integer.class);
            assertThat(count, equalTo(1));
        });

        Map<String, Object> outboxRow = db.queryForMap(
            "SELECT task_id, status FROM ccd.task_outbox ORDER BY id DESC LIMIT 1",
            Map.of()
        );
        String taskId = (String) outboxRow.get("task_id");
        assertThat(taskId, is(notNullValue()));

        String payloadCaseId = db.queryForObject(
            "SELECT payload->'task'->>'case_id' FROM ccd.task_outbox ORDER BY id DESC LIMIT 1",
            Map.of(),
            String.class
        );
        assertThat(payloadCaseId, equalTo(String.valueOf(caseRef)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> processed = db.queryForMap(
                "SELECT status, last_response_code FROM ccd.task_outbox WHERE task_id = :taskId",
                Map.of("taskId", taskId)
            );
            assertThat(processed.get("status"), equalTo("PROCESSED"));
            assertThat(
                ((Number) processed.get("last_response_code")).intValue(),
                anyOf(equalTo(200), equalTo(201))
            );
        });
    }

    @SneakyThrows
    @Order(20)
    @Test
    public void apiFirstTaskShouldBeVisibleInWorkAllocationSearch() {
        String user = "TEST_CASE_WORKER_USER@mailinator.com";
        String authHeader = getIdamAccessTokenFromSimulator(user, "password");
        Map<String, Object> searchRequest = Map.of(
            "search_parameters", List.of(Map.of(
                "key", "jurisdiction",
                "operator", "IN",
                "values", List.of(NoFaultDivorce.JURISDICTION)
            )),
            "sorting_parameters", List.of(),
            "request_context", "AVAILABLE_TASKS"
        );

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
                HttpPost request = new HttpPost(TASK_MANAGEMENT_BASE_URL + "/task");
                request.addHeader("Content-Type", "application/json");
                request.addHeader("ServiceAuthorization", cftlib().generateDummyS2SToken("xui_webapp"));
                request.addHeader("Authorization", authHeader);
                request.setEntity(new StringEntity(mapper.writeValueAsString(searchRequest), ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = client.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    assertThat(status, equalTo(200));

                    String responseBody = EntityUtils.toString(response.getEntity());
                    JsonNode payload = mapper.readTree(responseBody);
                    JsonNode tasks = payload.path("tasks");

                    boolean hasCaseTask = tasks.isArray() && StreamSupport.stream(tasks.spliterator(), false)
                        .map(task -> task.path("case_id").asText(task.path("caseId").asText("")))
                        .anyMatch(caseId -> String.valueOf(caseRef).equals(caseId));
                    assertThat("Expected task for case " + caseRef + " in response: " + responseBody,
                        hasCaseTask, is(true));
                }
            }
        });
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
        createCaseRequest.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
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
        createCase.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(createCase);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        var responseBody = EntityUtils.toString(response.getEntity());
        Map<String, Object> createdCase = mapper.readValue(responseBody, new TypeReference<>() {});
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

    @SneakyThrows
    private String getIdamAccessTokenFromSimulator(String user, String password) {
        String baseUrl = Optional.ofNullable(System.getenv("IDAM_API_BASEURL")).orElse("http://localhost:5062");
        String clientId = Optional.ofNullable(System.getenv("IDAM_CLIENT_ID")).orElse("divorce");
        String clientSecret = Optional.ofNullable(System.getenv("IDAM_CLIENT_SECRET")).orElse("123456");
        String tokenUrl = baseUrl + "/o/token";

        List<NameValuePair> params = List.of(
            new BasicNameValuePair("grant_type", "password"),
            new BasicNameValuePair("client_id", clientId),
            new BasicNameValuePair("client_secret", clientSecret),
            new BasicNameValuePair("username", user),
            new BasicNameValuePair("password", password),
            new BasicNameValuePair("scope", "openid profile roles")
        );

        var request = new HttpPost(tokenUrl);
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

        var response = HttpClientBuilder.create().build().execute(request);
        try {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                String body = Optional.ofNullable(response.getEntity())
                    .map(entity -> {
                        try {
                            return EntityUtils.toString(entity);
                        } catch (IOException ex) {
                            return "<failed to read body>";
                        }
                    })
                    .orElse("<empty>");
                throw new IllegalStateException("IDAM token request failed status=" + status + " body=" + body);
            }

            String body = EntityUtils.toString(response.getEntity());
            String token = mapper.readTree(body).path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("IDAM token response missing access_token");
            }
            return "Bearer " + token;
        } finally {
            response.close();
        }
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

    @SneakyThrows
    private HttpPost prepareEventRequestWithToken(String user, String eventId, Map<String, ?> data, String token) {
        return prepareEventRequestWithToken(user, eventId, data, token, caseRef);
    }

    @SneakyThrows
    private HttpPost prepareEventRequestWithToken(
        String user,
        String eventId,
        Map<String, ?> data,
        String token,
        long reference
    ) {
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

        var e = buildRequest(user, BASE_URL + "/cases/" + reference + "/events", HttpPost::new);
        withCcdAccept(e, ACCEPT_CREATE_EVENT);
        e.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
        return e;
    }

    private HttpPost prepareEventRequest(String user, String eventId, Map<String, ?> data) {
        return prepareEventRequestForCase(caseRef, user, eventId, data);
    }

    @SneakyThrows
    private HttpPost prepareEventRequestForCase(long reference, String user, String eventId, Map<String, ?> data) {
        var startEvent = ccdApi.startEvent(getAuthorisation(user), getServiceAuth(), String.valueOf(reference), eventId);
        Map<String, Object> submissionData = new LinkedHashMap<>(
            mapper.convertValue(startEvent.getCaseDetails().getData(), new TypeReference<Map<String, Object>>() {})
        );
        submissionData.putAll(data);
        return prepareEventRequestWithToken(user, eventId, submissionData, startEvent.getToken(), reference);
    }

    private void submitRoundTripEvent(Map<String, Object> payload) throws Exception {
        String user = "TEST_CASE_WORKER_USER@mailinator.com";
        var startEvent = ccdApi.startEvent(
            getAuthorisation(user),
            getServiceAuth(),
            String.valueOf(caseRef),
            CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA
        );

        Map<String, Object> submissionData = new LinkedHashMap<>(payload);
        Map<String, Object> startCallbackData = mapper.convertValue(
            startEvent.getCaseDetails().getData(),
            new TypeReference<Map<String, Object>>() {}
        );
        Object startMarker = startCallbackData.get("setInAboutToStart");
        if (startMarker != null) {
            submissionData.put("setInAboutToStart", startMarker);
        }

        Map<String, Object> dataAfterMidEvent = invokeRoundTripMidEvent(user, submissionData);
        Object midEventMarker = dataAfterMidEvent.get("setInMidEvent");
        if (midEventMarker != null) {
            submissionData.put("setInMidEvent", midEventMarker);
        }
        assertThat("Mid-event callback should inject marker. Response data: " + dataAfterMidEvent,
            midEventMarker, equalTo(CaseworkerRoundTripData.MID_EVENT_MARKER));

        var request = prepareEventRequestWithToken(
            user,
            CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA,
            submissionData,
            startEvent.getToken()
        );
        var response = HttpClientBuilder.create().build().execute(request);
        try {
            String responseBody = EntityUtils.toString(response.getEntity());
            assertThat("Round-trip data update should succeed. Response: " + responseBody,
                response.getStatusLine().getStatusCode(), equalTo(201));
        } finally {
            response.close();
        }
    }

    private Map<String, Object> buildRoundTripPayload() {
        String documentId = UUID.randomUUID().toString();
        String documentBaseUrl = "http://localhost/documents/" + documentId;

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_url", documentBaseUrl);
        document.put("document_binary_url", documentBaseUrl + "/binary");
        document.put("document_filename", "round-trip.pdf");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationType", "jointApplication");
        payload.put("applicant1FirstName", "RoundTripA1");
        payload.put("applicant1MiddleName", "Signal");
        payload.put("applicant1LastName", "Verifier");
        payload.put("applicant1Email", "roundtrip-a1@example.local");
        payload.put("applicant1PhoneNumber", "07000000011");
        payload.put("applicant1LanguagePreferenceWelsh", "Yes");
        payload.put("applicant1AgreedToReceiveEmails", "Yes");
        payload.put("applicant2FirstName", "RoundTripA2");
        payload.put("applicant2MiddleName", "Echo");
        payload.put("applicant2LastName", "Verifier");
        payload.put("applicant2Email", "roundtrip-a2@example.local");
        payload.put("applicant2PhoneNumber", "07000000022");
        payload.put("applicant2LanguagePreferenceWelsh", "No");
        payload.put("applicant2AgreedToReceiveEmails", "No");
        payload.put("dueDate", "2030-01-01");
        payload.put("testDocument", document);
        return payload;
    }

    private Map<String, Object> invokeRoundTripMidEvent(String user, Map<String, Object> data) throws Exception {
        var caseDetails = ccdApi.getCase(
            getAuthorisation(user),
            getServiceAuth(),
            String.valueOf(caseRef)
        );

        Map<String, Object> caseDetailsPayload = new LinkedHashMap<>();
        caseDetailsPayload.put("id", caseRef);
        caseDetailsPayload.put("jurisdiction", caseDetails.getJurisdiction());
        caseDetailsPayload.put("state", caseDetails.getState());
        caseDetailsPayload.put("case_type_id", NoFaultDivorce.getCaseType());
        caseDetailsPayload.put("case_data", data);

        var body = Map.of(
            "case_details", caseDetailsPayload,
            "case_details_before", caseDetailsPayload,
            "event_id", CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA,
            "ignore_warning", false
        );

        String midEventUrl = "http://localhost:4013/callbacks/mid-event?page=roundTripData&eventId="
            + CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA;

        var request = new HttpPost(midEventUrl);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("ServiceAuthorization", getServiceAuth());
        request.addHeader("Authorization", getAuthorisation(user));
        request.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(request);
        try {
            assertThat("Round-trip mid-event callback should succeed",
                response.getStatusLine().getStatusCode(), equalTo(200));
            JsonNode payload = mapper.readTree(EntityUtils.toString(response.getEntity()));
            JsonNode dataNode = payload.path("data");
            assertThat("Mid-event response should include data", dataNode.isMissingNode(), is(false));
            return new LinkedHashMap<>(mapper.convertValue(
                dataNode,
                new TypeReference<Map<String, Object>>() {}
            ));
        } finally {
            EntityUtils.consumeQuietly(response.getEntity());
            response.close();
        }
    }

    @SneakyThrows
    private Map<String, Object> readCaseDataFromDb() {
        String sql = "select data::text from ccd.case_data where reference = :ref";
        String json = db.queryForObject(sql, Map.of("ref", caseRef), String.class);
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSubset(Map<String, Object> source, Map<String, Object> template) {
        Map<String, Object> subset = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            String key = entry.getKey();
            Object expectedValue = entry.getValue();
            Object actualValue = source.get(key);

            if (expectedValue instanceof Map<?, ?> expectedNested && actualValue instanceof Map<?, ?> actualNested) {
                subset.put(key,
                    extractSubset((Map<String, Object>) actualNested, (Map<String, Object>) expectedNested));
            } else {
                subset.put(key, actualValue);
            }
        }
        return subset;
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
    public void testDecentralisedSubmitHandlerErrorsAreRolledBack() throws Exception {
        String sqlCountByCase = "SELECT count(*) FROM case_notes WHERE reference = :ref";
        Integer before = db.queryForObject(sqlCountByCase, Map.of("ref", caseRef), Integer.class);

        var start = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            DecentralisedCaseworkerAddNoteFailure.CASEWORKER_DECENTRALISED_ADD_NOTE_FAIL
        );

        String noteText = "Decentralised failing note";
        var request = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            DecentralisedCaseworkerAddNoteFailure.CASEWORKER_DECENTRALISED_ADD_NOTE_FAIL,
            Map.of("note", noteText),
            start.getToken()
        );

        var response = HttpClientBuilder.create().build().execute(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(422));

        var payload = mapper.readValue(EntityUtils.toString(response.getEntity()), Map.class);
        @SuppressWarnings("unchecked")
        List<String> callbackErrors = (List<String>) payload.get("callbackErrors");
        assertThat("callback errors should include simulated failure",
            callbackErrors, contains("Simulated decentralised failure"));

        Integer after = db.queryForObject(sqlCountByCase, Map.of("ref", caseRef), Integer.class);
        assertThat(after, equalTo(before));
    }

    @Order(21)
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

    @Order(22)
    @Test
    public void decentralisedEventsDoNotIncrementCaseDataVersion() throws Exception {
        var params = Map.of("ref", caseRef);
        Integer versionBefore = db.queryForObject(
            "SELECT version FROM ccd.case_data WHERE reference = :ref",
            params,
            Integer.class
        );

        assertThat("expected existing case version", versionBefore, is(notNullValue()));

        var start = ccdApi.startEvent(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(caseRef),
            DecentralisedCaseworkerAddNote.CASEWORKER_DECENTRALISED_ADD_NOTE
        );

        var request = prepareEventRequestWithToken(
            "TEST_CASE_WORKER_USER@mailinator.com",
            DecentralisedCaseworkerAddNote.CASEWORKER_DECENTRALISED_ADD_NOTE,
            Map.of("note", "Version guard"),
            start.getToken()
        );

        var response = HttpClientBuilder.create().build().execute(request);
        try {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
            EntityUtils.consumeQuietly(response.getEntity());
        } finally {
            response.close();
        }

        Integer versionAfter = db.queryForObject(
            "SELECT version FROM ccd.case_data WHERE reference = :ref",
            params,
            Integer.class
        );

        assertThat("decentralised event should not bump case_data.version when no blob update supplied",
            versionAfter, equalTo(versionBefore));

    }

    @SneakyThrows
    @Order(23)
    @Test
    void decentralisedReindexingUsesQueueWithoutRevisionBump() {
        Long caseDataId = db.queryForObject(
            "SELECT id FROM ccd.case_data WHERE reference = :ref",
            Map.of("ref", caseRef),
            Long.class
        );
        assertThat("Case data id should not be null", caseDataId, is(notNullValue()));

        Integer revisionBefore = db.queryForObject(
            "SELECT case_revision FROM ccd.case_data WHERE reference = :ref",
            Map.of("ref", caseRef),
            Integer.class
        );
        assertThat("Existing revision should be available", revisionBefore, is(notNullValue()));

        await()
            .pollInterval(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(
                "Elasticsearch revision should match datastore before reindexing",
                fetchElasticsearchDocument(caseDataId).path("case_revision").asInt(),
                equalTo(revisionBefore)
            ));

        JsonNode originalDoc = fetchElasticsearchDocument(caseDataId);

        JsonNode corruptedDoc = originalDoc.deepCopy();
        ((ObjectNode) corruptedDoc).put("state", "MutatedState");
        pushElasticsearchDocument(caseDataId, revisionBefore, corruptedDoc);

        await()
            .pollInterval(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(
                "Elasticsearch document should reflect the mutation",
                fetchElasticsearchDocument(caseDataId).path("state").asText(),
                equalTo("MutatedState")
            ));

        int enqueued = reindexQueueService.enqueueCasesModifiedSince(LocalDate.now().minusYears(1));
        assertThat("Expected to enqueue at least one case for reindexing", enqueued, greaterThan(0));

        await()
            .pollInterval(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> {
                JsonNode refreshedDoc = fetchElasticsearchDocument(caseDataId);
                assertThat("Elasticsearch revision should remain unchanged", refreshedDoc.path("case_revision").asInt(),
                    equalTo(revisionBefore));
                assertThat("Elasticsearch state should drop the corruption", refreshedDoc.path("state").asText(),
                    not(equalTo("MutatedState")));
            });
    }

    @SneakyThrows
    @Order(24)
    @Test
    void roundTripDataSurvivesCaseLifecycle() {
        Map<String, Object> payload = buildRoundTripPayload();
        submitRoundTripEvent(payload);

        Map<String, Object> callbackInjectedFields = Map.of(
            "setInAboutToStart", CaseworkerRoundTripData.START_CALLBACK_MARKER,
            "setInMidEvent", CaseworkerRoundTripData.MID_EVENT_MARKER,
            "setInAboutToSubmit", CaseworkerRoundTripData.SUBMIT_CALLBACK_MARKER
        );

        Map<String, Object> expectedPersistedData = new LinkedHashMap<>(payload);
        expectedPersistedData.putAll(callbackInjectedFields);

        Map<String, Object> latestCaseData = mapper.convertValue(
            ccdApi.getCase(
                getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
                getServiceAuth(),
                String.valueOf(caseRef)
            ).getData(),
            new TypeReference<Map<String, Object>>() {}
        );

        Map<String, Object> datastoreSnapshot = readCaseDataFromDb();

        assertThat("CCD case view should retain the round-trip payload plus callback fields",
            extractSubset(latestCaseData, expectedPersistedData), equalTo(expectedPersistedData));
        assertThat("Datastore JSON should retain the round-trip payload plus callback fields",
            extractSubset(datastoreSnapshot, expectedPersistedData), equalTo(expectedPersistedData));
    }

    @SneakyThrows
    @Order(25)
    @Test
    void simpleCaseCreationEventAddsMarkers() {
        var start = ccdApi.startCase(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            SimpleCaseConfiguration.CASE_TYPE,
            SimpleCaseConfiguration.CREATE_EVENT
        );

        var startData = mapper.convertValue(start.getCaseDetails().getData(), SimpleCaseData.class);
        assertThat(startData.getCreationMarker(), equalTo(SimpleCaseConfiguration.START_CALLBACK_MARKER));

        var token = start.getToken();
        Map<String, Object> submissionData = new LinkedHashMap<>(
            mapper.convertValue(start.getCaseDetails().getData(), new TypeReference<Map<String, Object>>() {})
        );
        submissionData.put("subject", "Simple case subject");
        submissionData.put("description", "Initial simple case description");

        var body = Map.of(
            "data", submissionData,
            "event", Map.of(
                "id", SimpleCaseConfiguration.CREATE_EVENT,
                "summary", "",
                "description", ""
            ),
            "event_token", token,
            "ignore_warning", false
        );

        var createCase = buildRequest(
            "TEST_CASE_WORKER_USER@mailinator.com",
            BASE_URL + "/data/case-types/" + SimpleCaseConfiguration.CASE_TYPE + "/cases?ignore-warning=false",
            HttpPost::new
        );
        withCcdAccept(createCase, ACCEPT_CREATE_CASE);
        createCase.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(createCase);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));
        Map<String, Object> result = mapper.readValue(EntityUtils.toString(response.getEntity()), new TypeReference<>() {});
        simpleCaseRef = Long.parseLong((String) result.get("id"));
        assertThat(result.get("state"), equalTo(SimpleCaseState.CREATED.name()));

        var storedCase = ccdApi.getCase(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(simpleCaseRef)
        );
        var storedData = mapper.convertValue(storedCase.getData(), SimpleCaseData.class);
        System.out.println("Simple case data after creation: " + storedCase.getData());
        assertThat(storedData.getCreationMarker(), equalTo(SimpleCaseConfiguration.SUBMIT_CALLBACK_MARKER));
        assertThat(storedData.getDescription(), equalTo("Initial simple case description"));
    }

    @SneakyThrows
    @Order(26)
    @Test
    void simpleCaseBlobPersistsRawValues() {
        assertThat("Simple case must be created before persisting assertions", simpleCaseRef, greaterThan(0L));

        var persistedCase = db.queryForMap("""
            SELECT state,
                   data::text AS data
              FROM ccd.case_data
             WHERE reference = :reference
            """,
            Map.of("reference", simpleCaseRef)
        );
        assertThat("Expected a persisted record for simple case " + simpleCaseRef, persistedCase, is(notNullValue()));
        assertThat("Case state should remain CREATED before follow-up",
            (String) persistedCase.get("state"),
            equalTo(SimpleCaseState.CREATED.name()));

        var dataNode = mapper.readTree((String) persistedCase.get("data"));
        assertThat(dataNode.path("subject").asText(), equalTo("Simple case subject"));
        assertThat(dataNode.path("description").asText(), equalTo("Initial simple case description"));
        assertThat(dataNode.path("creationMarker").asText(),
            equalTo(SimpleCaseConfiguration.SUBMIT_CALLBACK_MARKER));
        assertThat("Hyphenated reference must be derived at projection time only",
            dataNode.has("hyphenatedCaseRef"), is(false));
    }

    @SneakyThrows
    @Order(27)
    @Test
    void simpleCaseFollowUpEventUpdatesNote() {
        var request = prepareEventRequestForCase(
            simpleCaseRef,
            "TEST_CASE_WORKER_USER@mailinator.com",
            SimpleCaseConfiguration.FOLLOW_UP_EVENT,
            Map.of("followUpNote", "Follow up detail")
        );

        var response = HttpClientBuilder.create().build().execute(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(201));

        var updatedCase = ccdApi.getCase(
            getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"),
            getServiceAuth(),
            String.valueOf(simpleCaseRef)
        );
        var updatedData = mapper.convertValue(updatedCase.getData(), SimpleCaseData.class);
        System.out.println("Simple case data after follow up: " + updatedCase.getData());
        assertThat(updatedCase.getState(), equalTo(SimpleCaseState.FOLLOW_UP.name()));
        assertThat(updatedData.getFollowUpMarker(), equalTo(SimpleCaseConfiguration.FOLLOW_UP_CALLBACK_MARKER));
        assertThat(updatedData.getFollowUpNote(), containsString("Follow up detail"));
    }

    @SneakyThrows
    @Order(28)
    @Test
    void persistenceEndpointRejectsUnknownCaseType() {
        Map<String, Object> caseDetails = new LinkedHashMap<>();
        caseDetails.put("id", caseRef);
        caseDetails.put("jurisdiction", NoFaultDivorce.JURISDICTION);
        caseDetails.put("case_type_id", NoFaultDivorce.getCaseType());
        caseDetails.put("state", "Submitted");
        caseDetails.put("case_data", Map.of());

        Map<String, Object> payload = Map.of(
            "case_details", caseDetails,
            "event_details", Map.of(
                "case_type", "UNKNOWN_CASE",
                "event_id", "caseworker-add-note"
            )
        );

        var request = new HttpPost(SERVICE_BASE_URL + "/ccd-persistence/cases");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Authorization", getAuthorisation("TEST_CASE_WORKER_USER@mailinator.com"));
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        request.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

        var response = HttpClientBuilder.create().build().execute(request);
        var responseBody = EntityUtils.toString(response.getEntity());
        assertThat(response.getStatusLine().getStatusCode(), equalTo(400));
        JsonNode errorPayload = mapper.readTree(responseBody);
        assertThat(errorPayload.path("status").asInt(), equalTo(400));
    }

    private JsonNode fetchElasticsearchDocument(long caseDataId) throws IOException {
        var request = new HttpGet(ELASTICSEARCH_BASE_URL + "/e2e_cases/_doc/" + caseDataId);
        try (var response = HttpClientBuilder.create().build().execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            assertThat("Elasticsearch document should exist for case " + caseDataId, statusCode, equalTo(200));

            var payload = mapper.readTree(EntityUtils.toString(response.getEntity()));
            var source = payload.path("_source");
            assertThat("Elasticsearch document should contain _source", source.isMissingNode(), is(false));
            return source;
        }
    }

    private void pushElasticsearchDocument(long caseDataId, int revision, JsonNode document) throws IOException {
        ObjectNode payload = document.deepCopy();
        payload.put("case_revision", revision);

        var request = new HttpPut(String.format(
            "%s/e2e_cases/_doc/%d?version_type=external_gte&version=%d",
            ELASTICSEARCH_BASE_URL,
            caseDataId,
            revision
        ));
        request.setEntity(new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

        try (var response = HttpClientBuilder.create().build().execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            assertThat("Elasticsearch document push should succeed", status, anyOf(equalTo(200), equalTo(201)));
            EntityUtils.consumeQuietly(response.getEntity());
        }
    }

    private int fetchRevisionFromElasticsearch(long caseDataId) throws IOException {
        return fetchElasticsearchDocument(caseDataId).path("case_revision").asInt(-1);
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

    @SneakyThrows
    @Order(200)
    @Test
    void cascadingDeleteRemovesAllCaseData() {
        db.getJdbcTemplate().execute("DELETE FROM ccd.case_data");

        Integer caseDataCount = db.getJdbcTemplate()
            .queryForObject("SELECT COUNT(*) FROM ccd.case_data", Integer.class);
        assertThat("Case data table should be empty after delete", caseDataCount, equalTo(0));
    }

}
