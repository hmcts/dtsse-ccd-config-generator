package uk.gov.hmcts.example.enums;

/**
 * A team enum whose constants are EXACTLY the codes of two different definition lists —
 * {@code ComplexityBand} (whose ID is this enum's own simple name, so that list owns the class) and
 * {@code RivalComplexityBand} (which does not).
 *
 * <p>Civil's real shape: one {@code ComplexityBand} enum of {@code BAND_1}..{@code BAND_4} named by five
 * separate definition lists — {@code ComplexityBand}, {@code FastTrackComplexityBand},
 * {@code FinalOrdersIntermediateComplexityBand}, {@code ComplexityBandIntermediate},
 * {@code IntermediateComplexityBand} — all with the same codes and differently-worded labels. A class
 * carries one {@code @ComplexType(name)}, so at most one of those IDs can be served by the enum and
 * {@code RetrofitTypeBinder} refuses the rest on its claimed-by-another-ID rule.
 *
 * <p>The point of the fixture is that this refusal has nothing to do with the constant set: the enum
 * reproduces the rival list's codes EXACTLY. A companion is still generated for the rival ID, so the
 * field declaring this enum has to be pointed at that companion — otherwise the companion is emitted
 * referenced by nothing and the rival list's own rows have no counterpart.
 */
public enum ComplexityBand {

  BAND_1,

  BAND_2
}
