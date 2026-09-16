package uk.gov.hmcts.ccd.sdk.taskmanagement.search;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

@JacksonComponent
@SuppressWarnings("PMD.LawOfDemeter")
public class SearchRequestCustomDeserializer extends StdDeserializer<TaskSearchParameter<?>> {

  private static final long serialVersionUID = -1895766495984179418L;

  private static final String ERROR_MESSAGE =
      "Each search_parameter element must have 'key', 'values' and 'operator' fields present and populated.";

  public SearchRequestCustomDeserializer() {
    super(TaskSearchParameter.class);
  }

  @Override
  public TaskSearchParameter<?> deserialize(JsonParser jsonParser, DeserializationContext ctxt)
      throws JacksonException {

    final JsonNode searchNode = ctxt.readTree(jsonParser);

    final JsonNode operatorNode = searchNode.get("operator");

    if (operatorNode == null) {
      throw new RuntimeException(ERROR_MESSAGE);
    }

    if (TaskSearchOperator.BOOLEAN.getValue().equals(operatorNode.asText())) {
      return ctxt.readTreeAsValue(searchNode, TaskSearchParameterBoolean.class);
    } else if (TaskSearchOperator.IN.getValue().equals(operatorNode.asText())) {
      return ctxt.readTreeAsValue(searchNode, TaskSearchParameterList.class);
    } else {
      throw new RuntimeException(ERROR_MESSAGE);
    }
  }
}
