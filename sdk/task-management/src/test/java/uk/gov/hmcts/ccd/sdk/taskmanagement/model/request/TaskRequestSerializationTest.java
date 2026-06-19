package uk.gov.hmcts.ccd.sdk.taskmanagement.model.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRequestSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldSerializeTerminationRequestCaseTypeAsSnakeCase() throws Exception {
    TaskTerminationRequest request = TaskTerminationRequest.builder()
        .action("complete")
        .caseTypeId("NFD")
        .taskIds(List.of("task-id"))
        .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

    assertThat(json.get("case_type_id").asText()).isEqualTo("NFD");
    assertThat(json.get("task_ids").get(0).asText()).isEqualTo("task-id");
  }

  @Test
  void shouldSerializeReconfigureRequestCaseTypeAsSnakeCase() throws Exception {
    TaskReconfigureRequest request = TaskReconfigureRequest.builder()
        .caseTypeId("NFD")
        .tasks(List.of())
        .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

    assertThat(json.get("case_type_id").asText()).isEqualTo("NFD");
    assertThat(json.get("tasks")).isEmpty();
  }
}
