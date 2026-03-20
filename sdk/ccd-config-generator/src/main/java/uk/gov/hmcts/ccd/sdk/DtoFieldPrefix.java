package uk.gov.hmcts.ccd.sdk;

import java.util.regex.Pattern;

public final class DtoFieldPrefix {

  private static final Pattern VALID_PREFIX = Pattern.compile("^[A-Za-z0-9]+$");
  private static final String SEPARATOR = "_";

  private DtoFieldPrefix() {
  }

  public static void validate(String caseTypeId, String eventId, String fieldPrefix) {
    if (fieldPrefix == null || fieldPrefix.isBlank()) {
      throw new IllegalArgumentException(
          "DTO field prefix is required for case type '%s', event '%s'."
              .formatted(caseTypeId, eventId));
    }
    if (!VALID_PREFIX.matcher(fieldPrefix).matches()) {
      throw new IllegalArgumentException(
          ("Invalid DTO field prefix '%s' for case type '%s', event '%s'. "
              + "Expected ASCII alphanumeric characters matching %s.")
              .formatted(fieldPrefix, caseTypeId, eventId, VALID_PREFIX.pattern()));
    }
  }

  public static String toFieldId(String fieldPrefix, String fieldName) {
    return toFieldKeyPrefix(fieldPrefix).concat(fieldName);
  }

  public static String toFieldKeyPrefix(String fieldPrefix) {
    return fieldPrefix.concat(SEPARATOR);
  }
}
