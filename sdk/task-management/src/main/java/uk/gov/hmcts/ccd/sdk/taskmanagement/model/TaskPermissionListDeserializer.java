package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import java.util.Collections;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class TaskPermissionListDeserializer extends ValueDeserializer<List<TaskPermission>> {

  @Override
  public List<TaskPermission> deserialize(JsonParser parser, DeserializationContext context)
      throws JacksonException {
    JsonNode node = context.readTree(parser);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return context.readTreeAsValue(
          node,
          context.getTypeFactory().constructCollectionType(List.class, TaskPermission.class));
    }
    // WA returns permissions as an object; permissions are not needed for task termination.
    return Collections.emptyList();
  }
}
