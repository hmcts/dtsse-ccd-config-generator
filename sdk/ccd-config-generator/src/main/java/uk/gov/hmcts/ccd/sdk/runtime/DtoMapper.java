package uk.gov.hmcts.ccd.sdk.runtime;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

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
   * <p>CCD sends data like {@code {createPossessionClaimPropertyAddress: "..."}}.
   * This strips the prefix to get {@code {propertyAddress: "..."}} and converts to the DTO class.
   */
  public static <D> D fromCcdData(Map<String, ?> data, String prefix, Class<D> dtoClass,
                                  ObjectMapper mapper) {
    Map<String, Object> unprefixed = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : data.entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(prefix) && key.length() > prefix.length()) {
        String fieldName = uncapitalize(key.substring(prefix.length()));
        unprefixed.put(fieldName, entry.getValue());
      }
    }
    return mapper.convertValue(unprefixed, dtoClass);
  }

  /**
   * Serialises a DTO and adds the event prefix to each key.
   *
   * <p>Converts a DTO with {@code {propertyAddress: "..."}} to
   * {@code {createPossessionClaimPropertyAddress: "..."}}.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> toCcdData(Object dto, String prefix, ObjectMapper mapper) {
    Map<String, Object> flat = mapper.convertValue(dto, Map.class);
    Map<String, Object> prefixed = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : flat.entrySet()) {
      String key = entry.getKey();
      String prefixedKey = prefix + capitalize(key);
      prefixed.put(prefixedKey, entry.getValue());
    }
    return prefixed;
  }
}
