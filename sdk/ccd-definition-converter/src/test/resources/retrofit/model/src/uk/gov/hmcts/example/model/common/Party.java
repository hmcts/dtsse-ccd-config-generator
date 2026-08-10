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

  // A member whose definition complex type ID is camelCase (noticeDetails) while the class is
  // PascalCase — the SDK would emit the type as 'NoticeDetails' without a class-level
  // @ComplexType(name) pin.
  private NoticeDetails noticeDetails;

  // A member whose type already carries a team-written @ComplexType: the ID pin must refuse it (a
  // second annotation would not compile), which is also what makes the op idempotent.
  private PinnedByTeamCT pinnedByTeam;

  // A member whose definition complex type ID (executorApplying) shares NOTHING with the class name, so
  // no name-based lookup can reach it: RetrofitTypeBinder binds the ID by DECLARATION instead and the
  // patch pins it onto this class, rather than generating a companion nothing references.
  private AdditionalExecutorApplying executorApplying;

  // The same shape on a FixedList: the definition's handoffReasonFixedList is declared as the model enum
  // HandoffReasonId.
  private uk.gov.hmcts.example.enums.HandoffReasonId handoffReason;

  // Two members referencing ONE definition type (disagreeingCT) as two different classes: unanimity is
  // required, so the ID must stay unbound.
  private DeclaredOne disagreeingOne;

  private DeclaredTwo disagreeingTwo;

  // Two divergently-named definition types both declared as ONE class: neither can be bound, since the
  // class carries only one @ComplexType(name).
  private TwiceClaimedPayload firstClaiming;

  private TwiceClaimedPayload secondClaiming;

  // A definition FixedLists ID declared as a CLASS: the kinds disagree, so the binding is refused.
  private CrossKindPayload crossKind;

  // A definition FixedList the team DOES model as an enum — but NO field declares it, so reflection
  // reaches no rows for it (sscs's ScannedDocumentDetails.type). The patch must name the TEAM's enum
  // rather than a companion, with the import for ITS package (…example.callback), not the model one.
  private String scannedDocumentType;

  // The same shape whose enum spells the definition's codes in the team's own house style: the SDK emits
  // ListElementCode as the CONSTANT name and nothing can pin any other value, so naming this enum would
  // emit a list of wrong codes where today it emits none. Refused.
  private String houseStyleType;

  // And the same shape whose constant NAMES do match the definition's codes, but which carries a
  // @JsonValue redirecting what Jackson serialises them as: the emitted ListElementCode is `first`, not
  // FIRST, so the name match passes while the list would still be wrong. Refused.
  private String jsonValuedType;

  // Two collection members whose element wrappers hold ONE shared payload class: only the first
  // definition type can pin its ID onto SharedDetails, so the second must be reported as a gap.
  private List<FirstSharedCT> firstShared;

  private List<SecondSharedCT> secondShared;

  // Two members whose definition complex types (firstSummaryCT/secondSummaryCT) have NO model class,
  // so the converter generates a companion for each — but both members are declared as one shared
  // SharedSummary (sscs's ten dwp*DocumentCT / one DwpResponseDocument shape). Only a per-FIELD retype
  // can bind them, since one class can carry only one @ComplexType(name).
  private SharedSummary firstSummary;

  private SharedSummary secondSummary;

  // The same shape, but something in the model calls this member's getter (SummaryReader): the retype
  // changes what that call returns, so it must be refused and reported rather than breaking the build.
  private SharedSummary readSummary;

  // The same shape again, but reached with no accessor at all: the hand-written getter below returns
  // this field DIRECTLY as a SharedSummary (fpl's CaseData.getOrders() shape). Retyping the
  // declaration alone leaves that return uncompilable, so it must be refused too.
  private SharedSummary inlineReadSummary;

  // And the same shape reached through a Lombok @Builder setter named after the field, which carries
  // no get/set prefix for the accessor check to see (fpl's .respondents(…) shape).
  private SharedSummary builderSetSummary;

  /**
   * Reads {@link #inlineReadSummary} directly, with no accessor to intercept the retype.
   */
  public SharedSummary resolveInlineSummary() {
    return inlineReadSummary != null ? inlineReadSummary : new SharedSummary();
  }

  // Two members whose types declare the SAME field names in one extends hierarchy (ET's
  // BaseCaseData/CaseData shape): Lombok's per-declaration accessor pairs override each other, so
  // retyping either declaration alone breaks the override. Refused in both directions.
  private ShadowBase shadowBase;

  private ShadowChild shadowChild;

  // A member whose type carries a MARKER @JsonInclude (= ALWAYS): its synthesised members must each
  // carry @JsonInclude(NON_NULL) so the published wire payload gains no null properties.
  private AlwaysIncludedParty alwaysIncluded;

  // The same with a VALUED @JsonInclude(NON_NULL): nulls are already suppressed class-wide, so no
  // per-field annotation is added.
  private NonNullIncludedParty nonNullIncluded;
}
