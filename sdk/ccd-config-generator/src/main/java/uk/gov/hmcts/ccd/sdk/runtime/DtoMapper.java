package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.SneakyThrows;

/**
 * Handles transparent JSON payload serialisation for isolated DTO events
 * at the CCD-to-handler boundary.
 *
 * <p>The DTO is serialised as a JSON string in a single opaque {@code ccdSdkDtoEventData} CCD field.
 */
public final class DtoMapper {

  public static final String PAYLOAD_FIELD = "ccdSdkDtoEventData";

  private DtoMapper() {
  }

  /**
   * Extracts and deserialises a DTO from the {@code ccdSdkDtoEventData} field of CCD data.
   */
  @SneakyThrows
  public static <D> D fromCcdData(Map<String, ?> data, Class<D> dtoClass,
                                  ObjectMapper mapper) {
    Map<String, ?> source = data == null ? Collections.emptyMap() : data;
    Object payloadValue = source.get(PAYLOAD_FIELD);
    if (payloadValue == null) {
      return mapper.convertValue(Collections.emptyMap(), dtoClass);
    }
    if (payloadValue instanceof String json) {
      return mapper.readValue(json, dtoClass);
    }
    if (payloadValue instanceof JsonNode node && node.isTextual()) {
      return mapper.readValue(node.textValue(), dtoClass);
    }
    throw new RuntimeException(PAYLOAD_FIELD + " must be a JSON string, got: "
        + payloadValue.getClass().getName());
  }

  /**
   * Serialises a DTO as a JSON string and wraps it in the {@code ccdSdkDtoEventData} CCD field.
   */
  @SneakyThrows
  public static Map<String, Object> toCcdData(Object dto, ObjectMapper mapper) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (dto == null) {
      return result;
    }
    result.put(PAYLOAD_FIELD, mapper.writeValueAsString(dto));
    return result;
  }
}
