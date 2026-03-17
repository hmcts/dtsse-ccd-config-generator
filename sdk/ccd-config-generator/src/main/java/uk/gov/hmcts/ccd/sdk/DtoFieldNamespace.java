package uk.gov.hmcts.ccd.sdk;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class DtoFieldNamespace {

  private static final Pattern VALID_NAMESPACE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$");

  private DtoFieldNamespace() {
  }

  public static void validate(String caseTypeId, String eventId, String fieldNamespace) {
    if (fieldNamespace == null || fieldNamespace.isBlank()) {
      throw new IllegalArgumentException(
          "DTO field namespace is required for case type '%s', event '%s'."
              .formatted(caseTypeId, eventId));
    }
    if (!VALID_NAMESPACE.matcher(fieldNamespace).matches()) {
      throw new IllegalArgumentException(
          ("Invalid DTO field namespace '%s' for case type '%s', event '%s'. "
              + "Expected lowercase dot-separated segments matching %s.")
              .formatted(fieldNamespace, caseTypeId, eventId, VALID_NAMESPACE.pattern()));
    }
  }

  public static String toPrefixStem(String fieldNamespace) {
    if (fieldNamespace == null || fieldNamespace.isBlank()) {
      return fieldNamespace;
    }

    String[] segments = fieldNamespace.split("\\.");
    StringBuilder result = new StringBuilder(segments[0]);
    for (int i = 1; i < segments.length; i++) {
      result.append(StringUtils.capitalize(segments[i]));
    }
    return result.toString();
  }

  public static String toFieldId(String fieldNamespace, String fieldName) {
    return toPrefixStem(fieldNamespace).concat(StringUtils.capitalize(fieldName));
  }
}
