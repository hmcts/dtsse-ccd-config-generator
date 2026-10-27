package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code @JsonUnwrapped} model fields whose Lombok {@code @Getter(AccessLevel.NONE)} the retrofit
 * patch will delete, so the getter the emitted config references actually exists.
 *
 * <p>Why this is needed. A retrofit config reaches a member of an unwrapped sub-object as
 * {@code .complex(Root::getMember)} / {@code Member::getLeaf}, and the SDK resolves such a reference by
 * introspecting a serialized lambda, so it must be a real {@code Type::method}. Lombok generates that
 * getter from the class-level {@code @Data}/{@code @Getter} — unless the field suppresses it with
 * {@code @Getter(AccessLevel.NONE)}. sscs's {@code SscsCaseData} suppresses it on 22 unwrapped members
 * ({@code finalDecisionCaseData}, {@code pipSscsCaseData}, {@code adjournment}, …), hand-writing a
 * <em>differently-named</em> lazy accessor ({@code getSscsFinalDecisionCaseData}) for some and none for
 * the rest. {@link ModelSourceIndex#hasResolvableGetter} therefore answered false, and every placement
 * through those members — the {@code CaseEventToComplexTypes} member walk
 * ({@link RetrofitEventComplexTypeGraph}), the page placement ({@link RetrofitModelRebinder}) and the
 * synthesis host choice ({@link SynthesisPlacement}) — refused and fell back to verbatim passthrough
 * rather than emit an "invalid method reference" compile error. That refusal is what kept sscs's last
 * {@code CaseEventToComplexTypes} file alive ({@code writeFinalDecision/otherPartyAttendedQuestions},
 * two rows reached through {@code finalDecisionCaseData}) and 54 of the 65
 * {@code writeFinalDecision} {@code CaseEventToFields} rows on column passthrough.
 *
 * <p><b>Why deleting the suppression is safe, and why only for an unwrapped field.</b> Jackson reads
 * {@code @JsonUnwrapped} off the FIELD, so the missing getter never affected the wire format: the field
 * is already a visible property (a bare {@code private} field is not auto-detected — the annotation is
 * what makes it visible), and an added getter of the standard name attaches to that SAME property rather
 * than introducing a new one. Serialisation is therefore byte-identical before and after — verified
 * empirically on sscs, where a probe serialising a populated {@code SscsCaseData} produced identical
 * output with the suppression present and removed, and the repo's full test suite passed with it removed.
 * The change is additive to the published jar's surface (a new public getter, no signature changed).
 *
 * <p>An un-annotated suppressed field gets NO such guarantee — it is invisible to Jackson today (private,
 * no getter) and un-suppressing it would start serialising a brand-new property. So the un-suppression is
 * scoped to fields carrying {@code @JsonUnwrapped}, which is also exactly the shape the placements need.
 * A field with a hand-written getter of the standard name never reaches here at all
 * ({@code hasResolvableGetter} returns true on it before consulting Lombok).
 *
 * <p><b>Why the plan is recorded rather than re-derived.</b> Same discipline as
 * {@link RetrofitPinnedNames}: the reliance is recorded at the moment it is relied upon —
 * {@code hasResolvableGetter} records here precisely when the un-suppression is what flips its answer to
 * true — and the patch removes exactly what is recorded. Nothing is un-suppressed that no placement
 * needed, and no placement can reference a getter the patch declines to create. A re-derivation could
 * break either way: emit {@code SscsCaseData::getFinalDecisionCaseData} against a still-suppressed field
 * (a compile error in the team's repo), or delete a suppression nothing asked for (an unjustified edit).
 */
final class RetrofitUnsuppressedGetters {

  /**
   * A field whose getter suppression the patch will delete.
   *
   * @param ownerFqn the FQN of the class declaring the field
   * @param file the source file that class was parsed from, which the patch edits
   * @param memberName the Java field name whose {@code @Getter(AccessLevel.NONE)} is removed
   */
  record Unsuppression(String ownerFqn, Path file, String memberName) {
  }

  /**
   * Keyed {@code fqn#member} so the same field recorded by two call sites is one edit.
   */
  private final Map<String, Unsuppression> byKey = new LinkedHashMap<>();

  /** An empty plan, for generate mode and for models suppressing no unwrapped member's getter. */
  static RetrofitUnsuppressedGetters empty() {
    return new RetrofitUnsuppressedGetters();
  }

  /**
   * Records that the patch must delete {@code memberName}'s {@code @Getter(AccessLevel.NONE)}, because
   * a placement resolved the getter only on that basis.
   *
   * @param owner the class declaring the field
   * @param memberName the Java field name
   */
  void record(ModelSourceIndex.Type owner, String memberName) {
    byKey.putIfAbsent(owner.fqn + "#" + memberName,
        new Unsuppression(owner.fqn, owner.file, memberName));
  }

  /**
   * Every un-suppression the run relied on, in the order first recorded.
   *
   * @return the planned edits
   */
  Collection<Unsuppression> all() {
    return Collections.unmodifiableCollection(byKey.values());
  }

  /** Whether no placement relied on an un-suppression. */
  boolean isEmpty() {
    return byKey.isEmpty();
  }

  /** The number of fields to un-suppress, for reporting. */
  int size() {
    return byKey.size();
  }
}
