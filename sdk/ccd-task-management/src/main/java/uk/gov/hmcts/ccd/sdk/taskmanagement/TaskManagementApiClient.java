package uk.gov.hmcts.ccd.sdk.taskmanagement;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;

@Slf4j
public class TaskManagementApiClient {

  private final TaskManagementFeignClient feignClient;

  public TaskManagementApiClient(
      TaskManagementFeignClient feignClient
  ) {
    this.feignClient = feignClient;
  }

  public TaskManagementApiResponse createTask(TaskCreateRequest request) {
    try {
      ResponseEntity<TaskCreateResponse> response = feignClient.createTask(request);
      return buildResponse(response);
    } catch (FeignException ex) {
      log.warn(
          "Task management create failed with status {}: {}",
          ex.status(),
          ex.contentUTF8()
      );
      return new TaskManagementApiResponse(ex.status(), ex.contentUTF8(), null);
    } catch (RuntimeException ex) {
      log.error("Task management create failed", ex);
      return new TaskManagementApiResponse(null, ex.getMessage(), null);
    }
  }

  private TaskManagementApiResponse buildResponse(ResponseEntity<TaskCreateResponse> response) {
    TaskCreateResponse task = response.getBody();
    return new TaskManagementApiResponse(response.getStatusCodeValue(), null, task);
  }
}
