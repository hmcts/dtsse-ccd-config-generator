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
   * Purely informational: a divergence that produces no generated output and is neither passed
   * through nor separately reproduced — the comparator forgives it as an accepted semantic
   * difference. Two shapes occur:
   *
   * <ul>
   *   <li>a redundant input declaration safe to delete from the source (an orphan
   *       complex-type/fixed-list nothing reachable references, or a member-by-member redeclaration
   *       of an SDK-predefined platform type); and</li>
   *   <li>a display-only over-grant the SDK injects that no config-generator construct can subtract —
   *       notably the {@code AuthorisationCaseField} read the generator adds for a role that can see
   *       a field via an unrestricted tab/search. The field/role's intended grant is still derived
   *       into an {@code @CCD(access)} class; this record only notes the injected surplus (forgiven
   *       by the {@code TAB_READ_INJECTION} comparator rule), so no row is passed through for it.</li>
   * </ul>
   *
   * <p>Non-blocking — it never fails the conversion.
   */
  ADVISORY,
  /** Nothing could be done; conversion fails unless --allow-gaps is set. */
  OMITTED_FAIL
}
