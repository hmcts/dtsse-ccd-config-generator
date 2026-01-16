package uk.gov.hmcts.divorce.integration;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.divorce.divorcecase.NoFaultDivorce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///e2e",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.jms.servicebus.enabled=false",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration",
    "server.error.include-message=always",
    "server.error.include-exception=true"
})
class UnknownEventCallbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void aboutToStartUnknownEventReturnsNotFound() throws Exception {
        Map<String, Object> caseDetails = new LinkedHashMap<>();
        caseDetails.put("id", 1234567890123456L);
        caseDetails.put("jurisdiction", NoFaultDivorce.JURISDICTION);
        caseDetails.put("state", "Submitted");
        caseDetails.put("case_type_id", NoFaultDivorce.getCaseType());
        caseDetails.put("case_data", Map.of());

        Map<String, Object> payload = Map.of(
            "event_id", "unknown-event-id",
            "case_details", caseDetails,
            "case_details_before", caseDetails,
            "ignore_warning", false
        );

        mockMvc.perform(post("/callbacks/about-to-start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload)))
            .andExpect(status().is(HttpStatus.NOT_FOUND.value()))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Case event not found")));
    }
}
