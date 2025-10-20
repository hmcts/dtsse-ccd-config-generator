package uk.gov.hmcts.ccd.sdk;

/**
 * Projection hook that allows services to reshape CCD case data for the UI.
 * <p>
 * Decentralised services typically implement the two-parameter overload and ignore the JSON blob,
 * while legacy services can override the three-parameter overload to work directly with the blob.
 *
 * @param <CaseType> the domain model returned to CCD
 */
public interface CaseView<CaseType> {

  /**
   * Projection entry point used by the platform.
   * <p>
   * The default implementation simply delegates to {@link #getCase(long, String)}, which is suitable
   * for decentralised services that rebuild the case from their own stores.
   *
   * @param caseRef the CCD case reference
   * @param state the CCD state
   * @param blobCase case data deserialised from the blob
   * @return the projected case data
   */
  default CaseType getCase(long caseRef, String state, CaseType blobCase) {
    return getCase(caseRef, state);
  }

  /**
   * Overload for services that do not need the legacy blob.
   * <p>
   * Implementors may ignore this and instead override the three-parameter method if they rely on the
   * blob.
   *
   * @param caseRef the CCD case reference
   * @param state the CCD state
   * @return the projected case data
   */
  default CaseType getCase(long caseRef, String state) {
    throw new UnsupportedOperationException(
        "Implement either the two-argument or three-argument CaseView#getCase overload.");
  }
}
