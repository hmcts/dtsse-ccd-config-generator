package uk.gov.hmcts.example.model.common;

/**
 * One payload class backing MORE THAN ONE definition complex type — sscs's real shape, where ten
 * {@code dwp*DocumentCT} types plus {@code tl1FormCT} and {@code appendix12DocumentCT} are all declared
 * separately in the definition but modelled by a single class. A class can carry only one
 * {@code @ComplexType(name)}, so only the first ID can be pinned and the rest must be reported rather
 * than silently losing to it.
 */
public class SharedDetails {

  private String detail;
}
