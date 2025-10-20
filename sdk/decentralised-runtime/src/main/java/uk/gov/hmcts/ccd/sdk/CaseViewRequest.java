package uk.gov.hmcts.ccd.sdk;

/**
 * Carrier for the data required to project a CCD case into a service-defined view.
 *
 * @param <StateType> the strongly typed representation of the CCD state
 */
public record CaseViewRequest<StateType extends Enum<StateType>>(
    long caseRef,
    StateType state
) {
}
