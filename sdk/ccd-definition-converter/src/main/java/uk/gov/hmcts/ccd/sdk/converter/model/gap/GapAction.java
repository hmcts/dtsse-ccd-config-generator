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
  /**
   * Purely informational: the row is a redundant input declaration that produces no generated
   * output and is neither passed through nor reproduced (the comparator forgives its absence as an
   * accepted semantic difference). Example: an orphan complex-type/fixed-list declaration nothing
   * reachable references, or a member-by-member redeclaration of an SDK-predefined platform type.
   * Non-blocking — it never fails the conversion, and it flags the row as safe to delete from the
   * source definition.
   */
  ADVISORY,
  /** Nothing could be done; conversion fails unless --allow-gaps is set. */
  OMITTED_FAIL
}
