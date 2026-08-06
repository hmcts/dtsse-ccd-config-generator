package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * The complex-type members {@link RetrofitPatchEmitter} will <em>synthesise</em> onto the team's
 * existing model classes, keyed by owning class FQN then CCD member id.
 *
 * <p>Why this exists: the patch adds a definition-only member as a real field on the team's class, so
 * after the patch is applied that member IS addressable as {@code Type::getMember}. But the
 * {@code CaseEventToComplexTypes} member walk ({@link RetrofitEventComplexTypeGraph}) reads the model
 * as PARSED — i.e. pre-patch — and so used to see no such member and drop the row to a verbatim
 * passthrough. Civil showed the defect concretely: the patch synthesises {@code private String
 * bandLabel;} onto {@code FixedRecoverableCosts} in the very same run whose
 * {@code CaseEventToComplexTypes/CLAIMANT_RESPONSE/applicant2DQFixedRecoverableCosts.json} carries
 * {@code bandLabel} as a passthrough. Feeding the plan to the graph closed 239 of Civil's 939
 * fallback rows (939 → 700) and halved its member-not-found cause (576 → 326).
 *
 * <p>The plan is produced by the emitter rather than re-derived, because a definition member with no
 * model field is not automatically synthesised: the emitter refuses (routing it to a
 * {@code MANUAL_PLACEMENT} gap) when appending a field would break a constructor contract it cannot
 * repair, and drops members whose Java name collides with a declared field. A re-derivation that
 * missed those refusals would make the graph emit {@code Type::getMember} for a member the patch
 * never adds — a compile break in the team's repo. Only members the emitter actually plans to add
 * appear here.
 *
 * <p>A member is recorded with the complex-type ID its synthesised field is DECLARED with, so the walk
 * can descend past it: the field the patch adds carries the definition's own type, but that type may be
 * a companion class the parsed source does not have yet, so there is no {@code ModelSourceIndex.Type} to
 * hand back. Carrying the id instead lets the walk re-enter the model by id where the team declares a
 * class for it (sscs's {@code supporter.name.firstName}, descending the synthesised
 * {@code Supporter supporter} on {@code Appeal} into the model's own {@code Name}) and use the generated
 * companion's members where it does not. A member whose declared type is not a complex type at all
 * carries no id and stays a leaf, so a segment beneath it falls back exactly as before.
 */
final class RetrofitPlannedSynthesis {

  /**
   * A member the patch will add: the Java field name it is declared with (which the SDK's getter
   * name derives from), the {@code @CCD(hint)} it will carry, and — when its declared type is a complex
   * type — the complex-type ID a further segment descends by.
   *
   * @param javaName the synthesised field's Java name
   * @param hint the member's declared hint, or null
   * @param nestedTypeId the complex-type ID to descend by (a collection's ELEMENT type ID), or null when
   *     the member is a leaf
   * @param collection whether the synthesised field is a {@code Collection}, so the walk opens the
   *     element-typed scope for it
   */
  record Member(String javaName, String hint, String nestedTypeId, boolean collection) {
  }

  /**
   * The {@code FieldType} value marking a collection member, whose element type is the walkable one.
   */
  private static final String COLLECTION = "Collection";

  private final Map<String, Map<String, Member>> byOwnerFqn = new LinkedHashMap<>();

  /** An empty plan, for generate mode and for tests that exercise no synthesis. */
  static RetrofitPlannedSynthesis empty() {
    return new RetrofitPlannedSynthesis();
  }

  /**
   * Records a member the patch will synthesise onto {@code ownerFqn}.
   *
   * @param ownerFqn the FQN of the model class the field is added to
   * @param field the definition member being synthesised (its id is the CCD member id)
   */
  void record(String ownerFqn, FieldModel field) {
    boolean collection = COLLECTION.equals(field.getFieldType());
    // The type a further segment descends by is the member's own declared CCD type — its element type
    // for a collection, mirroring EventComplexTypeResolver.nestedTypeId. Recorded verbatim, with no
    // is-this-complex test: the caller already resolves an id to a type (the team's class, a generated
    // companion, an SDK-predefined class) and gets nothing for a scalar's 'Text'/'Date'/…, so a leaf
    // member stays a leaf without this class having to duplicate the type vocabulary.
    String nestedTypeId = collection ? field.getFieldTypeParameter() : field.getFieldType();
    byOwnerFqn.computeIfAbsent(ownerFqn, k -> new LinkedHashMap<>())
        .putIfAbsent(field.getId(),
            new Member(field.getJavaName(), field.getHint(), nestedTypeId, collection));
  }

  /**
   * The member the patch will synthesise onto {@code ownerFqn} under CCD id {@code ccdId}.
   *
   * @param ownerFqn the owning model class FQN
   * @param ccdId the CCD member id ({@code ListElementCode} segment)
   * @return the planned member, or empty when none is planned there
   */
  Optional<Member> member(String ownerFqn, String ccdId) {
    Map<String, Member> members = byOwnerFqn.get(ownerFqn);
    return members == null ? Optional.empty() : Optional.ofNullable(members.get(ccdId));
  }

  /** Whether nothing is planned (generate mode, or a model needing no synthesis). */
  boolean isEmpty() {
    return byOwnerFqn.isEmpty();
  }
}
