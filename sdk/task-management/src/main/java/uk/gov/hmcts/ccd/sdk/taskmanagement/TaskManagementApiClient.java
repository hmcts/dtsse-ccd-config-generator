package uk.gov.hmcts.ccd.sdk.taskmanagement;

import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchResponse;

public class TaskManagementApiClient {

  private final TaskManagementFeignClient feignClient;

  public TaskManagementApiClient(
      TaskManagementFeignClient feignClient
  ) {
    this.feignClient = feignClient;
  }

  public ResponseEntity<TaskCreateResponse> createTask(TaskCreateRequest request) {
    return feignClient.createTask(request);
  }

  public ResponseEntity<TaskSearchResponse> searchTasks(SearchTaskRequest request) {
    return feignClient.searchTasks(null, request);
  }

  public ResponseEntity<TaskTerminationResponse> terminateTask(TaskTerminationRequest request) {
    return feignClient.terminateTask(request);
  }
}
