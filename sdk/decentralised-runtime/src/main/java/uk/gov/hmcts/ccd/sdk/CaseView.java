package uk.gov.hmcts.ccd.sdk;

/**
 * Provides case data to CCD when CCD loads cases.
 *
 * <p>
 * Decentralised services typically implement the request-only overload and ignore the JSON blob,
 * while legacy services can override the request-plus-blob overload to continue working with the blob.
 *
 * @param <CaseType> domain view returned to CCD
 * @param <StateType> typed enum representing possible CCD states
 */
public interface CaseView<CaseType, StateType extends Enum<StateType>> {

  /**
   * Retrieve a case view for the given request.
   *
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
