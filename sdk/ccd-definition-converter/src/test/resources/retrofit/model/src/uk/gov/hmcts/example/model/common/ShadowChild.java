package uk.gov.hmcts.example.model.common;

/**
 * Re-declares both of {@link ShadowBase}'s fields, so each name exists twice in one hierarchy. See
 * {@link ShadowBase} for why a retype of either declaration cannot compile.
 */
public class ShadowChild extends ShadowBase {

  private SharedSummary baseAddressedSummary;

  private SharedSummary childAddressedSummary;
}
