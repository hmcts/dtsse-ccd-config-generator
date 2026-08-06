package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The model fields {@link RetrofitPatchEmitter} will <em>retype</em> to a generated companion class,
 * keyed by owning class FQN then Java member name (plus a by-CCD-id index for the root case fields).
 *
 * <p>Why a retype is needed at all. A definition complex type with no model class of its own is emitted
 * as a fresh companion class ({@link RetrofitComplexTypeEmitter}), carrying
 * {@code @ComplexType(name = <definitionId>, generate = true)}. But nothing pointed the team's field at
 * it: the field kept its own declared type, so {@code CaseFieldGenerator.resolveType} emitted that
 * class's Java simple name as the {@code FieldType} and the companion was dead code. Measured on the
 * full retrofit sweep: 668 of 781 generated companions (506 classes + 162 enums) were never referenced
 * by the patch. sscs shows the shape exactly — ten {@code dwp*DocumentCT} definition types are all
 * backed by one {@code DwpResponseDocument} field type, so the definition's rows
 * ({@code dwpAT38DocumentCT|documentLink}, …) had no counterpart while the generated rows
 * ({@code DwpResponseDocument|documentLink}) had no home. Both halves of that matched pair collapse
 * when the field is declared as the companion.
 *
 * <p>Retyping is what {@link RetrofitPatchEmitter#planComplexTypeId} explicitly refuses to solve with a
 * class-level {@code @ComplexType(name)} pin: one class can carry only one ID, so ten definition types
 * sharing a class is reported there as a gap. Giving each field its own companion resolves that gap
 * rather than contradicting it — the companion also reproduces members the team's class does not have
 * at all (each {@code dwp*DocumentCT} carries its own {@code Label} member).
 *
 * <p>Why the plan is produced rather than re-derived. A retype is refused for shapes where rewriting
 * the declaration would not compile (a hand-written constructor naming the old type, a subclass calling
 * {@code super(...)} positionally, a positional {@code new Owner(...)} call site). The
 * {@code CaseEventToComplexTypes} member walk ({@link RetrofitEventComplexTypeGraph}) reads the model as
 * PARSED, so without this plan it would resolve the walk against the OLD declared class and emit
 * {@code DwpResponseDocument::getDocumentLink} for a field the patch has just declared as
 * {@code DwpAT38DocumentCT} — a compile break in the team's repo. A re-derivation that missed a refusal
 * would break it the other way, emitting the companion's getters for a field the patch left alone. One
 * shared plan, written by the emitter and read by the graph, is what makes the two impossible to
 * disagree (the same discipline as {@link RetrofitPlannedSynthesis} and {@link RetrofitPinnedNames}).
 *
 * <p>A retyped field's walk resolves through the generated companion's own {@link
 * uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel}, not through the parsed AST: the companion is
 * generated output and so is absent from {@link ModelSourceIndex}. The graph therefore answers "no
 * binding" for a retyped field, which is exactly the signal {@code EventComplexTypeResolver} already
 * treats as "fall back to the type-id node".
 */
final class RetrofitPlannedRetypes {

  /**
   * A field the patch will retype: the companion class it will be declared as, and the definition
   * complex-type ID that companion carries.
   *
   * @param targetSimpleName the companion class's Java simple name (e.g. {@code DwpAT38DocumentCT})
   * @param definitionId the definition complex-type ID the companion is annotated with (e.g.
   *     {@code dwpAT38DocumentCT}) — the ID the walk descends by
   */
  record Retype(String targetSimpleName, String definitionId) {
  }

  private final Map<String, Map<String, Retype>> byOwnerFqn = new LinkedHashMap<>();
  /** CCD case-field id → the retype planned for the root field of that id. */
  private final Map<String, Retype> byCaseFieldId = new LinkedHashMap<>();
  /**
   * The model class FQNs a retype has already claimed a field of, so a second definition type binding
   * the SAME member is not silently re-pointed. Keyed {@code fqn#member}.
   */
  private final Set<String> claimed = new LinkedHashSet<>();

  /** An empty plan, for generate mode and for tests that exercise no retype. */
  static RetrofitPlannedRetypes empty() {
    return new RetrofitPlannedRetypes();
  }

  /**
   * Records a root {@code CaseData} field the patch will retype.
   *
   * @param ownerFqn the FQN of the class declaring the field
   * @param memberName the Java field name
   * @param caseFieldId the CCD case-field id
   * @param retype the companion the field will be declared as
   * @return true when recorded, false when that member was already claimed
   */
  boolean recordRootField(
      String ownerFqn, String memberName, String caseFieldId, Retype retype) {
    if (!claim(ownerFqn, memberName)) {
      return false;
    }
    byOwnerFqn.computeIfAbsent(ownerFqn, k -> new LinkedHashMap<>()).put(memberName, retype);
    byCaseFieldId.put(caseFieldId, retype);
    return true;
  }

  /**
   * Records a complex-type member the patch will retype.
   *
   * @param ownerFqn the FQN of the class declaring the member
   * @param memberName the Java field name
   * @param retype the companion the member will be declared as
   * @return true when recorded, false when that member was already claimed
   */
  boolean recordMember(String ownerFqn, String memberName, Retype retype) {
    if (!claim(ownerFqn, memberName)) {
      return false;
    }
    byOwnerFqn.computeIfAbsent(ownerFqn, k -> new LinkedHashMap<>()).put(memberName, retype);
    return true;
  }

  private boolean claim(String ownerFqn, String memberName) {
    return claimed.add(ownerFqn + "#" + memberName);
  }

  /**
   * The retype planned for a root case field, so the member walk knows the field is no longer declared
   * as the class the parsed source says.
   *
   * @param caseFieldId the CCD case-field id
   * @return the planned retype, or empty
   */
  Optional<Retype> forCaseField(String caseFieldId) {
    return Optional.ofNullable(byCaseFieldId.get(caseFieldId));
  }

  /**
   * The retype planned for a member of a model class.
   *
   * @param ownerFqn the owning model class FQN
   * @param memberName the Java field name
   * @return the planned retype, or empty
   */
  Optional<Retype> forMember(String ownerFqn, String memberName) {
    Map<String, Retype> members = byOwnerFqn.get(ownerFqn);
    return members == null ? Optional.empty() : Optional.ofNullable(members.get(memberName));
  }

  /** The number of fields planned for retyping, for reporting. */
  int size() {
    return claimed.size();
  }

  /** Whether nothing is planned (generate mode, or a model needing no retype). */
  boolean isEmpty() {
    return claimed.isEmpty();
  }
}
