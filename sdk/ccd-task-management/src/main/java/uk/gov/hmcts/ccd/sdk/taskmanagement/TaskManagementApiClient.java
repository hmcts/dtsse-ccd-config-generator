package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

@Slf4j
public class TaskManagementApiClient {

    private final RestTemplate restTemplate;
    private final AuthTokenGenerator authTokenGenerator;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public TaskManagementApiClient(
        RestTemplateBuilder builder,
        AuthTokenGenerator authTokenGenerator,
        TaskManagementProperties properties,
        ObjectMapper objectMapper
    ) {
        this.restTemplate = builder.build();
        this.authTokenGenerator = authTokenGenerator;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.getApi().getUrl();
    }

    public TaskManagementApiResponse createTask(TaskCreateRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            return createTask(payload);
        } catch (IOException ex) {
            log.error("Task management create failed during payload serialization", ex);
            return new TaskManagementApiResponse(null, ex.getMessage(), null);
        }
    }

    public TaskManagementApiResponse createTask(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("ServiceAuthorization", authTokenGenerator.generate());

        HttpEntity<String> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/task/create",
                entity,
                String.class
            );
            return buildResponse(response);
        } catch (HttpStatusCodeException ex) {
            log.warn(
                "Task management create failed with status {}: {}",
                ex.getStatusCode(),
                ex.getResponseBodyAsString()
            );
            return new TaskManagementApiResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString(), null);
        } catch (RuntimeException ex) {
            log.error("Task management create failed", ex);
            return new TaskManagementApiResponse(null, ex.getMessage(), null);
        }
    }

    private TaskManagementApiResponse buildResponse(ResponseEntity<String> response) {
        String body = response.getBody();
        TaskCreateResponse task = null;
        if (body != null && response.getStatusCode().is2xxSuccessful()) {
            try {
                task = objectMapper.readValue(body, TaskCreateResponse.class);
            } catch (IOException ex) {
                log.debug("Task management create response could not be parsed", ex);
            }
        }
        return new TaskManagementApiResponse(response.getStatusCodeValue(), body, task);
    }
}
