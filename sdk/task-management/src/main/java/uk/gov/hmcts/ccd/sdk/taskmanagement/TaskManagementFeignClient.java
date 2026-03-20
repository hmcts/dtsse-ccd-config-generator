package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.List;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskReconfigureRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskReconfigureResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.GetTasksResponse;


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

  @GetMapping(value = "/tasks", produces = APPLICATION_JSON_VALUE)
  @CollectionFormat(feign.CollectionFormat.CSV)
  ResponseEntity<GetTasksResponse> getTasks(@RequestParam("case_id") String caseId,
                                            @RequestParam("task_types") List<String> taskTypes);

}
