package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class TaskPermissionListDeserializer extends JsonDeserializer<List<TaskPermission>> {

  private static final TypeReference<List<TaskPermission>> LIST_TYPE = new TypeReference<>() {};

  @Override
  public List<TaskPermission> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    ObjectMapper mapper = (ObjectMapper) parser.getCodec();
    JsonNode node = mapper.readTree(parser);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return mapper.convertValue(node, LIST_TYPE);
    }
    // WA returns permissions as an object; permissions are not needed for task termination.
    return Collections.emptyList();
  }
}
