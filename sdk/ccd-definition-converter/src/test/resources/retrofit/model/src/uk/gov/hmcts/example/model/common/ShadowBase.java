package uk.gov.hmcts.example.model.common;

/**
 * A base class declaring the SAME field names its subclass {@link ShadowChild} re-declares — ET's
 * {@code BaseCaseData}/{@code CaseData} shape. Lombok generates an accessor pair per declaration, one
 * overriding the other, which only compiles while both declarations share a type. Retyping either one
 * alone must therefore be refused, in BOTH directions: {@code baseAddressedSummary} is addressed here
 * (so the shadowing declaration is a DESCENDANT) and {@code childAddressedSummary} is addressed on the
 * subclass (so the shadowing declaration is an ANCESTOR).
 */
public class ShadowBase {

  private SharedSummary baseAddressedSummary;

  private SharedSummary childAddressedSummary;
}
