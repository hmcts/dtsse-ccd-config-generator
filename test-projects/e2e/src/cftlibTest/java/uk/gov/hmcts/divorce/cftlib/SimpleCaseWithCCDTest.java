package uk.gov.hmcts.divorce.cftlib;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.divorce.simplecase.SimpleCaseConfiguration;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseData;
import uk.gov.hmcts.divorce.simplecase.model.SimpleCaseState;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.rse.ccd.lib.Database;
import uk.gov.hmcts.rse.ccd.lib.test.CftlibTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
    "spring.jms.servicebus.enabled=true",
    "ccd.servicebus.destination=ccd-case-events-test",
    "ccd.servicebus.scheduler-enabled=true",
    "ccd.servicebus.schedule=*/1 * * * * *",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(TestWithCCD.ServiceBusTestConfiguration.class)
@Slf4j
public class SimpleCaseWithCCDTest extends CftlibTest {

    @Autowired
    private IdamClient idam;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CoreCaseDataApi ccdApi;

    private static final String BASE_URL = "http://localhost:4452";
    private static final String ACCEPT_CREATE_CASE =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-case.v2+json;charset=UTF-8";
    private static final String ACCEPT_CREATE_EVENT =
        "application/vnd.uk.gov.hmcts.ccd-data-store-api.create-event.v2+json;charset=UTF-8";

    private long simpleCaseRef;

    @SneakyThrows
    @Order(1)
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
        log.info("Simple case data after creation: {}", storedCase.getData());
        assertThat(storedData.getCreationMarker(), equalTo(SimpleCaseConfiguration.SUBMIT_CALLBACK_MARKER));
        assertThat(storedData.getDescription(), equalTo("Initial simple case description"));
    }

    @SneakyThrows
    @Order(2)
    @Test
    void simpleCaseBlobPersistsRawValues() {
        assertThat("Simple case must be created before persisting assertions", simpleCaseRef, greaterThan(0L));

        try (Connection connection = cftlib().getConnection(Database.Datastore);
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT state,
                        data::text AS data
                   FROM case_data
                  WHERE reference = ?
             """)) {
            statement.setLong(1, simpleCaseRef);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat("Expected a persisted record for simple case " + simpleCaseRef, rs.next(), is(true));
                assertThat("Case state should remain CREATED before follow-up", rs.getString("state"),
                    equalTo(SimpleCaseState.CREATED.name()));

                var dataNode = mapper.readTree(rs.getString("data"));
                assertThat(dataNode.path("subject").asText(), equalTo("Simple case subject"));
                assertThat(dataNode.path("description").asText(), equalTo("Initial simple case description"));
                assertThat(dataNode.path("creationMarker").asText(),
                    equalTo(SimpleCaseConfiguration.SUBMIT_CALLBACK_MARKER));
                assertThat("Hyphenated reference must be derived at projection time only",
                    dataNode.has("hyphenatedCaseRef"), is(false));
            }
        }
    }

    @SneakyThrows
    @Order(3)
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
        log.info("Simple case data after follow up: {}", updatedCase.getData());
        assertThat(updatedCase.getState(), equalTo(SimpleCaseState.FOLLOW_UP.name()));
        assertThat(updatedData.getFollowUpMarker(), equalTo(SimpleCaseConfiguration.FOLLOW_UP_CALLBACK_MARKER));
        assertThat(updatedData.getFollowUpNote(), containsString("Follow up detail"));
    }

    private String getAuthorisation(String user) {
        return idam.getAccessToken(user, "");
    }

    private String getServiceAuth() {
        return cftlib().generateDummyS2SToken("ccd_gw");
    }

    private <T extends HttpRequestBase> T buildRequest(String user, String url, Function<String, T> ctor) {
        var request = ctor.apply(url);
        var token = idam.getAccessToken(user, "");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("ServiceAuthorization", getServiceAuth());
        request.addHeader("Authorization", token);
        return request;
    }

    private void withCcdAccept(HttpRequestBase request, String accept) {
        request.addHeader("experimental", "true");
        request.addHeader("Accept", accept);
    }

    @SneakyThrows
    private HttpPost prepareEventRequestForCase(long reference, String user, String eventId, Map<String, ?> data) {
        var startEvent = ccdApi.startEvent(getAuthorisation(user), getServiceAuth(), String.valueOf(reference), eventId);
        Map<String, Object> submissionData = new LinkedHashMap<>(
            mapper.convertValue(startEvent.getCaseDetails().getData(), new TypeReference<Map<String, Object>>() {})
        );
        submissionData.putAll(data);

        var body = Map.of(
            "data", submissionData,
            "event", Map.of(
                "id", eventId,
                "summary", "",
                "description", ""
            ),
            "event_token", startEvent.getToken(),
            "ignore_warning", false
        );

        var e = buildRequest(user, BASE_URL + "/cases/" + reference + "/events", HttpPost::new);
        withCcdAccept(e, ACCEPT_CREATE_EVENT);
        e.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));
        return e;
    }
}

