package uk.gov.hmcts.divorce.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import uk.gov.hmcts.divorce.roundtrip.RoundTripFixture;
import uk.gov.hmcts.divorce.sow014.nfd.CaseworkerRoundTripData;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sends every SDK complex type through the service's callback endpoint and requires the data
 * that comes back to match what was sent. Each type is sent twice: behind a prefixed
 * {@code @JsonUnwrapped} and as a plain nested complex type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///e2e",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.jms.servicebus.enabled=false",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration",
    "server.error.include-message=always",
    "server.error.include-exception=true"
})
class SdkComplexTypeRoundTripTest {

    private static final long CASE_REFERENCE = 1616591401473378L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void prefixedUnwrappedSdkTypesSurviveAboutToSubmitCallback() throws Exception {
        ObjectNode sent = fixture();
        sent.remove("sdkNested");

        List<String> differences = RoundTripFixture.lostOrChanged(sent, aboutToSubmit(sent));

        assertThat(RoundTripFixture.describe(differences), differences, empty());
    }

    @Test
    void nestedSdkTypesSurviveAboutToSubmitCallback() throws Exception {
        ObjectNode sent = mapper.createObjectNode();
        sent.set("sdkNested", fixture().get("sdkNested"));

        List<String> differences = RoundTripFixture.lostOrChanged(sent, aboutToSubmit(sent));

        assertThat(RoundTripFixture.describe(differences), differences, empty());
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(
            RoundTripFixture.load(RoundTripFixture.SDK_COMPLEX_TYPES, CASE_REFERENCE));
    }

    private JsonNode aboutToSubmit(JsonNode data) throws Exception {
        Map<String, Object> caseDetails = new LinkedHashMap<>();
        caseDetails.put("id", CASE_REFERENCE);
        caseDetails.put("jurisdiction", NoFaultDivorce.JURISDICTION);
        caseDetails.put("state", "Submitted");
        caseDetails.put("case_type_id", NoFaultDivorce.getCaseType());
        caseDetails.put("case_data", data);

        Map<String, Object> request = Map.of(
            "event_id", CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA,
            "case_details", caseDetails,
            "case_details_before", caseDetails,
            "ignore_warning", false
        );

        String response = mockMvc.perform(post("/callbacks/about-to-submit")
                .queryParam("eventId", CaseworkerRoundTripData.CASEWORKER_ROUNDTRIP_DATA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return mapper.readTree(response).path("data");
    }
}
