package uk.gov.hmcts.divorce.tasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

@Component
@Slf4j
public class TaskManagementApiClient {

    private final RestTemplate restTemplate;
    private final AuthTokenGenerator authTokenGenerator;
    private final String baseUrl;

    public TaskManagementApiClient(RestTemplateBuilder builder,
                                   AuthTokenGenerator authTokenGenerator,
                                   @Value("${task-management.api.url}") String baseUrl) {
        this.restTemplate = builder.build();
        this.authTokenGenerator = authTokenGenerator;
        this.baseUrl = baseUrl;
    }

    public TaskManagementApiResponse createTask(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("ServiceAuthorization", authTokenGenerator.generate());

        HttpEntity<String> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl + "/task/create", entity, String.class);
            return new TaskManagementApiResponse(response.getStatusCodeValue(), response.getBody());
        } catch (HttpStatusCodeException ex) {
            log.warn("Task management create failed with status {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return new TaskManagementApiResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RuntimeException ex) {
            log.error("Task management create failed", ex);
            return new TaskManagementApiResponse(null, ex.getMessage());
        }
    }
}
