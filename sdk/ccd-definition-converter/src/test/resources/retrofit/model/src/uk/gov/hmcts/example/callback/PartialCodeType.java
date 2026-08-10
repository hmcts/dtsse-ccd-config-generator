package uk.gov.hmcts.example.callback;

/**
 * The same shape as {@link HouseStyleType} — a fixed list modelled as an enum no field declares, whose
 * codes the team spells in its own house style — except that one of the definition's codes has no
 * constant at all ({@code thirdKind}). A code pin can only redirect a constant that exists, so pinning
 * the two that do match would emit a list right about two rows and missing the third: the same defect the
 * refusal exists to prevent, at smaller scale. The enum genuinely models a different constant set, which
 * is a divergence to report rather than a code to pin, so naming it is refused outright.
 */
public enum PartialCodeType {
  FIRST_KIND("firstKind"),
  SECOND_KIND("secondKind");

  private final String value;

  PartialCodeType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
