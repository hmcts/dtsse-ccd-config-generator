package uk.gov.hmcts.ccd.sdk.taskmanagement;

import io.micrometer.common.util.StringUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskReconfigureRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskTerminationRequest;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskCreateResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskReconfigureResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.response.TaskTerminationResponse;
import uk.gov.hmcts.ccd.sdk.taskmanagement.search.GetTasksResponse;

public class TaskManagementApiClient {

  private final TaskManagementFeignClient feignClient;

  public TaskManagementApiClient(TaskManagementFeignClient feignClient) {
    this.feignClient = feignClient;
  }

  public ResponseEntity<TaskCreateResponse> createTask(TaskCreateRequest request) {
    return feignClient.createTask(request);
  }

  public ResponseEntity<GetTasksResponse> getTasks(String caseId, List<String> taskTypes) {
    requireText(caseId, "caseId");
    Objects.requireNonNull(taskTypes, "taskTypes must not be null");
    return feignClient.getTasks(caseId, taskTypes);
  }

  public ResponseEntity<TaskTerminationResponse> terminateTask(TaskTerminationRequest request) {
    return feignClient.terminateTask(request);
  }

  public ResponseEntity<TaskReconfigureResponse> reconfigureTask(TaskReconfigureRequest request) {
    return feignClient.reconfigureTask(request);
  }

  private void requireText(String value, String field) {
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
