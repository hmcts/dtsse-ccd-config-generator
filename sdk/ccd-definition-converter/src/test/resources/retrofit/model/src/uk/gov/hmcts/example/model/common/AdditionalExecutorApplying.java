package uk.gov.hmcts.example.model.common;

/**
 * A model class whose name shares nothing with the definition ID that describes it: the definition
 * declares the complex type {@code executorApplying} and the field referencing it,
 * {@code Party.executorApplying}, is declared as THIS class (probate's real shape —
 * {@code ExecutorApplying} vs {@code AdditionalExecutorApplying}).
 *
 * <p>{@code ModelSourceIndex.complexTypeClass} matches only by simple name (exactly, then
 * case-insensitively), so it cannot reach this class at all: the ID would get a generated companion
 * nothing references while this class emitted a full set of rows under {@code AdditionalExecutorApplying},
 * an ID the definition never mentions — two diff lines per member. {@link RetrofitTypeBinder} binds the ID
 * to it by DECLARATION instead, and the patch pins {@code @ComplexType(name = "executorApplying")}.
 */
public class AdditionalExecutorApplying {

  private String applyingExecutorName;
}
