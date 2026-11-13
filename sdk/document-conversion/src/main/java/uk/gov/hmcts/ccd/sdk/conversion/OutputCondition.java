package uk.gov.hmcts.ccd.sdk.conversion;

/**
 * The printing condition a PDF/X file is prepared for, written to its output intent. It names a
 * characterisation registered at {@code registryName}, so no ICC profile is embedded.
 *
 * @param identifier the registered condition identifier, such as {@code FOGRA39}
 * @param info a human-readable description of the condition
 * @param registryName the registry the identifier belongs to
 */
public record OutputCondition(String identifier, String info, String registryName) {

  /** Offset litho on coated paper, the usual European characterisation for production print. */
  public static final OutputCondition FOGRA39 = new OutputCondition(
      "FOGRA39", "Coated FOGRA39 (ISO 12647-2:2004)", "http://www.color.org");

  /** Validates the condition. */
  public OutputCondition {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("OutputCondition.identifier must be provided");
    }
    if (registryName == null || registryName.isBlank()) {
      throw new IllegalArgumentException("OutputCondition.registryName must be provided");
    }
    if (info == null) {
      info = identifier;
    }
  }
}
