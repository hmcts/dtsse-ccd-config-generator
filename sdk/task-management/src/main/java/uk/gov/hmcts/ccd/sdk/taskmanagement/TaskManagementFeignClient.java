package uk.gov.hmcts.ccd.sdk.taskmanagement;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "task-management-api",
    url = "${task-management.api.url}",
    configuration = TaskManagementFeignConfig.class
)
public interface TaskManagementFeignClient {

  @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<TaskCreateResponse> createTask(@RequestBody TaskCreateRequest payload);
}
