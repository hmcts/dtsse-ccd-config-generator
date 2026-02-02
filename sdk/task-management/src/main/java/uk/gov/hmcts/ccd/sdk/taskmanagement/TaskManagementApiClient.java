package uk.gov.hmcts.ccd.sdk.taskmanagement;

import io.micrometer.common.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchResponse;
import uk.gov.hmcts.reform.idam.client.IdamClient;

public class TaskManagementApiClient {

  private static final String BEARER_PREFIX = "Bearer ";

  private final TaskManagementFeignClient feignClient;
  private final IdamClient idamClient;
  @Value("${idam.systemupdate.username}")
  private String systemUserName;
  @Value("${idam.systemupdate.password}")
  private String systemPassword;


  public TaskManagementApiClient(
      TaskManagementFeignClient feignClient,
      IdamClient idamClient
  ) {
    this.feignClient = feignClient;
    this.idamClient = idamClient;
  }

  public ResponseEntity<TaskCreateResponse> createTask(TaskCreateRequest request) {
    return feignClient.createTask(request);
  }

  public ResponseEntity<TaskSearchResponse> searchTasks(SearchTaskRequest request) {
    System.out.printf("system user details %s password %s%n", systemUserName, systemPassword);
    String authToken = getBearerToken(idamClient.getAccessToken(systemUserName, systemPassword));
    System.out.printf("system user details are username %s password %s authtoken %s%n", systemUserName, systemPassword, authToken);
    return feignClient.searchTasks(authToken, request);
  }

  public ResponseEntity<TaskTerminationResponse> terminateTask(TaskTerminationRequest request) {
    return feignClient.terminateTask(request);
  }

  private String getBearerToken(String token) {
    if (StringUtils.isBlank(token)) {
      return token;
    }
    return token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX.concat(token);
  }
}
