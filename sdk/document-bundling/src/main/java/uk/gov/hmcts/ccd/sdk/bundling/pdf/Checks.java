package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/**
 * Argument validation helpers for the assembly model types.
 */
final class Checks {

  private Checks() {
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
