package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

@JsonComponent
@SuppressWarnings({"PMD.LawOfDemeter"})
public class SearchRequestCustomDeserializer extends StdDeserializer<TaskSearchParameter<?>> {

  private static final long serialVersionUID = -1895766495984179418L;

  private static final String ERROR_MESSAGE =
    "Each search_parameter element must have 'key', 'values' and 'operator' fields present and populated.";

  public SearchRequestCustomDeserializer() {
    this(null);
  }

  public SearchRequestCustomDeserializer(final Class<?> cls) {
    super(cls);
  }

  @Override
  public TaskSearchParameter<?> deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException {

    final ObjectMapper mapper = (ObjectMapper) jsonParser.getCodec();
    final JsonNode searchNode = mapper.readTree(jsonParser);

    final JsonNode operatorNode = searchNode.get("operator");

    if (operatorNode == null) {
      throw new RuntimeException(ERROR_MESSAGE);
    }

    if (TaskSearchOperator.BOOLEAN.getValue().equals(operatorNode.asText())) {
      return mapper.treeToValue(searchNode, TaskSearchParameterBoolean.class);
    } else if (TaskSearchOperator.IN.getValue().equals(operatorNode.asText())) {
      return mapper.treeToValue(searchNode, TaskSearchParameterList.class);
    } else {
      throw new RuntimeException(ERROR_MESSAGE);
    }
  }
}
