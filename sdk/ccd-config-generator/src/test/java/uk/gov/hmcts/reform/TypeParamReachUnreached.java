package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * The over-reach guard: a list nothing names at all — no declared field type, no
 * {@code typeParameterClass} — which must therefore emit no {@code FixedLists} rows even though it
 * carries {@code @ComplexType(generate = true)} exactly as {@link TypeParamReachVenue} does. Without
 * it, a resolver that swept the classpath for {@code @ComplexType} rather than following references
 * would pass the rest of this fixture's assertions.
 */
@ComplexType(name = "FL_typeParamReachUnreached", generate = true)
public enum TypeParamReachUnreached implements HasLabel {
  ORPHAN;

  @Override
  public String getLabel() {
    return "Reached by nothing";
  }
}
