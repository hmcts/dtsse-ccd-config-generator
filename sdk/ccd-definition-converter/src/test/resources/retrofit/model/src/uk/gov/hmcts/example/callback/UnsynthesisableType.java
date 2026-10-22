package uk.gov.hmcts.example.callback;

/**
 * The refusal that survives constant synthesis: a definition code with no constant ({@code thirdSort}),
 * on an enum whose constants pass an argument no new constant's value can be established from.
 *
 * <p>Each constant passes its code and then a {@code Category} reference. Adding a constant means passing
 * something in that position, and what a new one should pass is a guess: the definition says nothing about
 * it, no unanimous rule claims it (it is neither the code, nor the label, nor an empty string), and a
 * guess that fails to compile — or compiles and puts a wrong value in the team's model — is worse than
 * the residual line the refusal costs. So the enum stays refused and the constant-set divergence is
 * reported instead.
 */
public enum UnsynthesisableType {
  FIRST_SORT("firstSort", Category.PRIMARY),
  SECOND_SORT("secondSort", Category.SECONDARY);

  private final String value;
  private final Category category;

  UnsynthesisableType(String value, Category category) {
    this.value = value;
    this.category = category;
  }

  public String getValue() {
    return value;
  }

  public Category getCategory() {
    return category;
  }

  /** The classifier a new constant would have to be assigned, and which nothing can establish. */
  public enum Category {
    PRIMARY,
    SECONDARY
  }
}
