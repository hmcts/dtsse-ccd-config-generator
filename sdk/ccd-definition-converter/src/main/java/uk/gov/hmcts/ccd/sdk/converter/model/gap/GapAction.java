package uk.gov.hmcts.ccd.sdk.converter.model.gap;

/** What the converter did about a gap. */
public enum GapAction {
  /** The full row is written to the passthrough JSON and merged after generation. */
  PASSTHROUGH_ROW,
  /** Only the inexpressible columns are grafted onto the generated row after generation. */
  PASSTHROUGH_COLUMN,
  /** Expressed as environment-guarded Java code rather than data. */
  CONDITIONAL_CODE,
  /**
   * The converter could not place the field automatically and a maintainer must do it by hand (e.g.
   * a synthesised complex-type member that cannot be appended to a class using a hand-written
   * single-arg {@code @JsonCreator} + {@code @Builder} idiom without breaking the builder).
   */
  MANUAL_PLACEMENT,
  /** Nothing could be done; conversion fails unless --allow-gaps is set. */
  OMITTED_FAIL
}
