package uk.gov.hmcts.ccd.sdk;

/**
 * Projection hook that allows services to reshape CCD case data for the UI.
 * <p>
 * Decentralised services typically implement the request-only overload and ignore the JSON blob,
 * while legacy services can override the request-plus-blob overload to work directly with the blob.
 *
 * @param <CaseType>  the domain model returned to CCD
 * @param <StateType> the strongly-typed representation of the CCD state
 */
public interface CaseView<CaseType, StateType extends Enum<StateType>> {

  /**
   * @param request encapsulated request information
   * @return the projected case data
   */
  default CaseType getCase(CaseViewRequest<StateType> request) {
    throw new UnsupportedOperationException(
        "CaseView implementations must override getCase(CaseViewRequest) or "
            + "getCase(CaseViewRequest, CaseType)");
  }

  /**
   * Overload for services that wish to work with the deserialised blob.
   *
   * @param request encapsulated request information
   * @param blobCase case data deserialised from the blob
   * @return the projected case data
   */
  default CaseType getCase(CaseViewRequest<StateType> request, CaseType blobCase) {
    return getCase(request);
  }
}
