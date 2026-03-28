package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.DtoFieldPrefix;

/**
 * Handles transparent prefix stripping/adding for isolated DTO events
 * at the CCD-to-handler boundary.
 *
 * <p>Since DTOs are flat, this is straightforward key renaming.
 */
public final class DtoMapper {

  private DtoMapper() {
  }

  /**
   * Strips the event prefix from CCD data keys and deserialises into a DTO.
   *
   * <p>CCD sends data like {@code {cpc_propertyAddress: "..."}}.
   * This strips the prefix to get {@code {propertyAddress: "..."}} and converts to the DTO class.
   */
  public static <D> D fromCcdData(Map<String, ?> data, String prefix, Class<D> dtoClass,
                                  ObjectMapper mapper) {
    Map<String, Object> unprefixed = new LinkedHashMap<>();
    Map<String, ?> source = data == null ? Collections.emptyMap() : data;
    String fieldKeyPrefix = DtoFieldPrefix.toFieldKeyPrefix(prefix);
    for (Map.Entry<String, ?> entry : source.entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(fieldKeyPrefix) && key.length() > fieldKeyPrefix.length()) {
        String fieldName = key.substring(fieldKeyPrefix.length());
        unprefixed.put(fieldName, entry.getValue());
      }
    }
    return mapper.convertValue(unprefixed, dtoClass);
  }

  /**
   * Serialises a DTO and adds the event prefix to each key.
   *
   * <p>Converts a DTO with {@code {propertyAddress: "..."}} to
   * {@code {cpc_propertyAddress: "..."}}.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> toCcdData(Object dto, String prefix, ObjectMapper mapper) {
    if (dto == null) {
      return new LinkedHashMap<>();
    }

    Map<String, Object> flat = mapper.convertValue(dto, Map.class);
    Map<String, Object> prefixed = new LinkedHashMap<>();
    String fieldKeyPrefix = DtoFieldPrefix.toFieldKeyPrefix(prefix);
    for (Map.Entry<String, Object> entry : flat.entrySet()) {
      String key = entry.getKey();
      String prefixedKey = fieldKeyPrefix + key;
      prefixed.put(prefixedKey, entry.getValue());
    }
    return prefixed;
  }
}
