package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskReconfigureRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskReconfigureResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.SearchTaskRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.TaskSearchResponse;

@FeignClient(
    name = "task-management-api",
    url = "${task-management.api.url}",
    configuration = TaskManagementFeignConfig.class
)
public interface TaskManagementFeignClient {

  @PostMapping(value = "/tasks", consumes = APPLICATION_JSON_VALUE)
  ResponseEntity<TaskCreateResponse> createTask(@RequestBody TaskCreateRequest payload);

  @PostMapping(value = "/tasks/terminate")
  ResponseEntity<TaskTerminationResponse> terminateTask(@RequestBody TaskTerminationRequest payload);

  @PutMapping(value = "/tasks/reconfigure", consumes = APPLICATION_JSON_VALUE)
  ResponseEntity<TaskReconfigureResponse> reconfigureTask(@RequestBody TaskReconfigureRequest payload);

  @PostMapping(value = "/task", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
  ResponseEntity<TaskSearchResponse> searchTasks(
      @RequestHeader(AUTHORIZATION) String authToken,
      @RequestBody SearchTaskRequest searchTaskRequest
  );
}
