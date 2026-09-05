package uk.gov.hmcts.example.callback;

/**
 * The same shape as {@link HouseStyleType} — a fixed list modelled as an enum no field declares, whose
 * codes the team spells in its own house style — except that one of the definition's codes has no
 * constant at all ({@code thirdKind}). The enum is the model of that CCD column, so a code the column
 * really carries and the enum cannot name is a gap in the model: the missing constant is ADDED, and the
 * list then round-trips in full rather than being refused for the one row.
 *
 * <p>Nothing about the added constant's constructor call is inferred. Every constant here passes one
 * string literal, and every one passes its OWN definition code there, so that position provably carries
 * the code and the new constant passes the same arity of the same kind — which compiles for exactly the
 * reason its siblings do. See {@code RetrofitFixedListLabels#synthesisedArguments}.
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
