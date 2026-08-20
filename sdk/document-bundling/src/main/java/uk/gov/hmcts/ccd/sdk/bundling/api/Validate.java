package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * Argument validation helpers for the public API types.
 */
final class Validate {

  private Validate() {
  }

  static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be provided and non-blank");
    }
    return value;
  }

  static <T> T requireNonNull(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must be provided");
    }
    return value;
  }
}
