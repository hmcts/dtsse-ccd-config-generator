package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.Optional;

/**
 * A model-aware view of the team's existing Java classes, consulted by
 * {@code EventComplexTypeResolver} in retrofit mode so a {@code CaseEventToComplexTypes} member
 * chain binds to the class the field is <em>actually declared as</em> — not the SDK-predefined type
 * of the same CCD complex-type ID nor a similarly-named synthesised sibling.
 *
 * <p>The CCD definition names a complex field's type by its ComplexTypes ID (e.g.
 * {@code ChangeOrganisationRequest}, {@code Organisation}, {@code AddressUK}), many of which the SDK
 * also ships as {@code uk.gov.hmcts.ccd.sdk.type.*} classes. But a service team routinely declares
 * its <em>own</em> same-shaped class for that field ({@code model.ccd.raw.ChangeOrganisationRequest},
 * {@code model.caseaccess.Organisation}), whose getters differ ({@code getOrganisationID} vs the
 * SDK's {@code getOrganisationId}). Resolving the member chain against the SDK class emits a method
 * reference to a getter the real field type does not expose — a compile error the service team must
 * hand-fix. This graph lets the resolver read the real declared type off the parsed model source and
 * walk it, so the emitted {@code Type::getMember} references resolve against the team's classes.
 *
 * <p>Implemented in the retrofit package over the parsed model AST; null in generate mode, where the
 * resolver walks only generated complex types and reflected SDK-predefined types.
 */
public interface RetrofitModelTypeGraph {

  /**
   * The declared model type a complex {@code CaseData} field binds to: for a scalar complex field its
   * declared class, and for a {@code Collection} field its element class. Empty when the field's
   * declared type is not in the parsed model source (a genuinely SDK-typed / library field), so the
   * resolver falls back to the SDK-predefined / generated path.
   *
   * @param caseFieldId the root complex field's CCD ID
   * @return the bound model type handle, or empty
   */
  Optional<Handle> rootHandle(String caseFieldId);

  /**
   * Whether the root complex field is a {@code Collection} in the model (its declared type is a list),
   * so the emitter must open the element-typed two-arg {@code .complex(getter, Element.class)} scope.
   *
   * @param caseFieldId the root complex field's CCD ID
   * @return true when the field's declared model type is a list
   */
  boolean rootIsCollection(String caseFieldId);

  /**
   * Resolves one dotted {@code ListElementCode} segment against a model type: matches it to a member
   * whose effective CCD id (its {@code @JsonProperty} value, else its Java field name) equals the
   * segment, and returns the member's getter plus — when the member is itself a complex type or a
   * collection of one — the nested handle to descend into. Empty when the type declares no matching
   * member (e.g. a definition-only label field with no Java backing), so the whole group falls back to
   * a verbatim row passthrough rather than emitting a broken reference.
   *
   * @param owner the model type the segment is resolved against
   * @param segment the {@code ListElementCode} segment (a member's CCD id)
   * @return the resolved member, or empty
   */
  Optional<MemberResolution> member(Handle owner, String segment);

  /**
   * An opaque handle to a parsed model type. The resolver only ever reads its {@link #fqn()} (to name
   * the type in a {@code Type::getMember} reference) and passes it back into {@link #member}.
   */
  interface Handle {

    /**
     * The type's fully-qualified name.
     *
     * @return the fully-qualified class name
     */
    String fqn();
  }

  /**
   * One resolved member of a model type: the getter to reference, the nested type to descend into (its
   * element type when the member is a collection), whether the member is a collection, and the
   * member's declared {@code @CCD(hint)}.
   */
  final class MemberResolution {

    private final String getter;
    private final Handle nested;
    private final boolean collection;
    private final String declaredHint;

    /**
     * Creates a member resolution.
     *
     * @param getter the member's getter name (e.g. {@code getOrganisationID})
     * @param nested the type to descend into for a further segment (the collection element type when
     *     {@code collection} is true), or null when the member is a leaf the walk cannot descend past
     * @param collection whether the member is a {@code Collection} (list) in the model
     * @param declaredHint the member's declared {@code @CCD(hint)}, or null
     */
    public MemberResolution(String getter, Handle nested, boolean collection, String declaredHint) {
      this.getter = getter;
      this.nested = nested;
      this.collection = collection;
      this.declaredHint = declaredHint;
    }

    /**
     * The member's getter name.
     *
     * @return the getter
     */
    public String getter() {
      return getter;
    }

    /**
     * The nested type to descend into, or null when the member is a leaf.
     *
     * @return the nested handle, or null
     */
    public Handle nested() {
      return nested;
    }

    /**
     * Whether the member is a collection (list) in the model.
     *
     * @return true for a collection member
     */
    public boolean collection() {
      return collection;
    }

    /**
     * The member's declared {@code @CCD(hint)}, or null.
     *
     * @return the declared hint, or null
     */
    public String declaredHint() {
      return declaredHint;
    }
  }
}
