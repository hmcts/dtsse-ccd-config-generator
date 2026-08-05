package uk.gov.hmcts.example.model.common;

import java.util.List;

/** A simple complex type used as a generic collection element type. */
public class Party {

  private String name;

  private String role;

  // A NESTED concrete value-wrapper collection member (like SSCS's
  // ReasonableAdjustmentsLetters.List<Correspondence>): List<DocItem> mis-resolves to DocItem, so
  // the patch must add @CCD(typeParameterOverride = "Document") on this member too — the reconciler
  // has to run on complex-type members, not just root CaseData fields (bug A2).
  private List<DocItem> attachments;

  // A member whose type (RecoverableCosts) is @AllArgsConstructor with a subclass calling super(...)
  // positionally (B4): a definition-only member of it MUST be synthesised, with a narrow explicit
  // constructor added to the PARENT so the unchanged subclass's super(...) still binds.
  private RecoverableCosts costs;

  // The same subclass-super(...) shape but with the all-args constructor INFERRED from @Builder rather
  // than declared: unrepairable (the narrow constructor would suppress the inference), so its
  // definition-only member must still route to a gap.
  private BuilderOnlyCosts builderOnlyCosts;

  // A member whose type (ValueHolder) is @Value with a hand-written @JsonCreator: a definition-only
  // member of ValueHolder must NOT be synthesised into it (final field would be uninitialised).
  private ValueHolder holder;

  // Regression fixture (annotation-placement fix): NoTrailingNewlineHost has no trailing newline
  // and its annotated member is followed by MORE unchanged lines than the diff's context window, so
  // the "\ No newline at end of file" marker must not be misplaced on the wrong hunk line.
  private NoTrailingNewlineHost noTrailingNewlineHost;

  // A member whose type (FinalFieldParty) is @Data with private-final fields and a constructor-level
  // @Builder (fpl's RespondentParty shape): a definition-only member of it MUST be synthesised (a
  // non-final field compiles and is set via the setter), NOT dropped by the old "any final field"
  // guard.
  private FinalFieldParty finalFieldParty;

  // A member whose type (BuilderBoundParty) is @Data @Builder bound to a hand-written multi-arg
  // @JsonCreator (sscs's Appeal shape): a definition-only member of it MUST be synthesised AND the
  // bound constructor widened to take it, keeping the builder binding valid.
  private BuilderBoundParty builderBoundParty;

  // A member whose type has TWO non-delegating constructors, where the shorter one's widened form
  // collides with the longer one's narrow overload — the overload-collision guard must suppress it.
  private TwoConstructorParty twoConstructorParty;
}
