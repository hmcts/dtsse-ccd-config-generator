package uk.gov.hmcts.ccd.sdk.taskmanagement;

import org.springframework.http.ResponseEntity;

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
}
