package uk.gov.hmcts.ccd.sdk.taskmanagement;

import io.micrometer.common.util.StringUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.TaskPayload;
import uk.gov.hmcts.ccd.sdk.taskmanagement.model.request.TaskCreateApiRequest;
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
    Objects.requireNonNull(request, "request must not be null");
    List<TaskPayload> tasks = request.tasks();
    if (tasks.size() != 1) {
      throw new IllegalArgumentException("createTask requires exactly one task");
    }
    return createTask(tasks.get(0));
  }

  public ResponseEntity<TaskCreateResponse> createTask(TaskPayload task) {
    Objects.requireNonNull(task, "task must not be null");
    return feignClient.createTask(new TaskCreateApiRequest(task));
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
