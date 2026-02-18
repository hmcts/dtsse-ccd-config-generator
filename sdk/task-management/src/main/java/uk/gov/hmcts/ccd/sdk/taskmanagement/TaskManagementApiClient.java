package uk.gov.hmcts.ccd.sdk.taskmanagement;

import io.micrometer.common.util.StringUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskReconfigureRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskReconfigureResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskRequestContext;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchKey;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchOperator;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchParameterList;
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
    String authToken = getBearerToken(idamClient.getAccessToken(systemUserName, systemPassword));
    return feignClient.searchTasks(authToken, request);
  }

  public List<TaskPayload> searchTasks(String caseId, List<String> taskTypes) {
    requireText(caseId, "caseId");
    Objects.requireNonNull(taskTypes, "taskTypes must not be null");

    var searchRequest = SearchTaskRequest.builder()
      .searchParameters(
        List.of(
          TaskSearchParameterList.builder()
            .key(TaskSearchKey.TASK_TYPE)
            .operator(TaskSearchOperator.IN)
            .values(taskTypes)
            .build(),
          TaskSearchParameterList.builder()
            .key(TaskSearchKey.CASE_ID)
            .operator(TaskSearchOperator.IN)
            .values(List.of(caseId))
            .build()
        )
      )
      .taskSortingParameters(null)
      .requestContext(TaskRequestContext.ALL_WORK)
      .build();

    var response = searchTasks(searchRequest);
    if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
      throw new IllegalStateException("Failed to retrieve tasks for reconfiguration");
    }

    return response.getBody().getTasks();
  }

  public ResponseEntity<TaskTerminationResponse> terminateTask(TaskTerminationRequest request) {
    return feignClient.terminateTask(request);
  }

  public ResponseEntity<TaskReconfigureResponse> reconfigureTask(TaskReconfigureRequest request) {
    return feignClient.reconfigureTask(request);
  }

  private String getBearerToken(String token) {
    if (StringUtils.isBlank(token)) {
      return token;
    }
    return token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX.concat(token);
  }

  private void requireText(String value, String field) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
