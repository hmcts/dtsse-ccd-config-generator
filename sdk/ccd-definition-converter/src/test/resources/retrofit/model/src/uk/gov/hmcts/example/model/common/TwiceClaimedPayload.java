package uk.gov.hmcts.example.model.common;

/**
 * One class that TWO divergently-named definition types ({@code firstClaimingCT},
 * {@code secondClaimingCT}) are both declared as. A class carries only one {@code @ComplexType(name)},
 * so {@link RetrofitTypeBinder} binds neither rather than picking a winner arbitrarily — the
 * declaration-bound counterpart of the name-bound collision {@code planComplexTypeId} reports.
 */
public class TwiceClaimedPayload {

  private String detail;
}
