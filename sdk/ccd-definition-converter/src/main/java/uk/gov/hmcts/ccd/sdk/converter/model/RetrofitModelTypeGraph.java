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
   * The team's own declared class for a definition complex-type ID, so a walk that has only the ID to
   * go on — descending past a member the patch <em>synthesises</em>, or out of a generated companion
   * type — re-enters the model and keeps referencing real getters.
   *
   * <p>Without this the walk had two ways to name the same nested type and they could disagree: a
   * member resolved off the parsed source yields the model's own Java field names, while the same type
   * reached by ID yields the <em>definition</em>-derived ones. Those coincide only until a model member
   * is named differently from its CCD id (a {@code @JsonProperty}, an embedded acronym), at which point
   * the ID-derived path emits {@code Type::getMember} for a getter the class does not expose. Routing
   * every by-ID descent through here means the type is looked up once, the same way, wherever the walk
   * arrived from.
   *
   * <p>The class returned is the one the definition addresses that ID's members on, which is what makes
   * it the same decision the patch's own {@code ComplexTypes} member planner makes: a hand-rolled
   * {@code {id, value}} element wrapper is unwrapped to its value class (sscs's {@code Bundle} wrapper →
   * {@code BundleDetails}, which is where the definition's {@code Bundle} members are annotated). Empty
   * when the model declares no top-level class for the ID — a genuinely definition-only complex type,
   * which the converter emits as a companion and the caller walks through its generated model instead.
   *
   * @param complexTypeId the definition {@code ComplexTypes} ID
   * @return the team's declared class for that ID, or empty
   */
  Optional<Handle> complexTypeHandle(String complexTypeId);

  /**
   * How the emitted config reaches the complex field itself, from the root case-data class: the
   * getter, plus the {@code @JsonUnwrapped} hops to descend through first when the team's model does
   * not declare the field on the root class.
   *
   * <p>Without this the group's root getter was derived as {@code get} + the linker's own
   * CCD-id-derived {@code javaName} <em>invoked on {@code CaseData}</em> — which is wrong twice over in
   * retrofit mode: the team's member may be named differently from the CCD id, and it may not be
   * declared on the root class at all. Civil's {@code applicant1DQHearing} is declared on
   * {@code model.dq.Applicant1DQ} and reached via {@code CaseData}'s {@code @JsonUnwrapped applicant1DQ},
   * so {@code CaseData::getApplicant1DQHearing} does not compile. The page-field placement of the same
   * field already goes through the retrofit rebinder's {@code ClusteredFieldRef}; this method exposes
   * that one decision to the linker so the two can never disagree.
   *
   * <p>Three outcomes, all distinct:
   * <ul>
   *   <li><b>empty</b> — the model has no binding for this field (a definition-only field the patch
   *       synthesises onto the root class), so the caller keeps its own definition-derived
   *       {@code CaseData::get<javaName>} reference, exactly as before;</li>
   *   <li><b>{@link RootPlacement#reachable()} false</b> — the model declares the field but no
   *       compilable path reaches it: a {@code @JsonUnwrapped} hop's getter is suppressed
   *       ({@code @Getter(AccessLevel.NONE)} with no correctly-named accessor), or the field's own
   *       getter is. The caller keeps the whole group a verbatim row passthrough rather than emitting a
   *       reference that does not compile — the same refusal the rebinder's
   *       {@code unplaceableFieldIds} applies to the page placement of that field;</li>
   *   <li><b>reachable</b> — use its getter, opening its hops first.</li>
   * </ul>
   *
   * @param caseFieldId the complex field's CCD ID
   * @return how to reach the field, or empty when the model does not declare it
   */
  Optional<RootPlacement> rootPlacement(String caseFieldId);

  /**
   * How the config reaches one case field from the root case-data class: the hops to descend through
   * (outermost first, empty when the field is declared on the root class) and the field's own getter,
   * invoked on the last hop's target type — or on the case-data class when there are no hops. A
   * placement whose {@link #reachable()} is false carries no getter: the model declares the field but
   * exposes no compilable path to it.
   *
   * <p>Every hop is a {@code @JsonUnwrapped} member, which is what makes the chain safe to open with
   * {@code .complex(getter)}: the SDK registers no {@code CaseEventToFields} row for an unwrapped member
   * and shares the parent's field collections instead, so descending costs nothing in the generated
   * definition.
   *
   * @param getter the field's own getter, invoked on the last hop's target type (or on the case-data
   *     class when {@link #hops()} is empty); null when unreachable
   * @param hops the {@code @JsonUnwrapped} hops to descend, outermost first; empty for a field
   *     declared on the root class
   * @param reachable whether a compilable path to the field exists at all
   */
  record RootPlacement(String getter, java.util.List<PlacementHop> hops, boolean reachable) {

    /**
     * A reachable placement.
     *
     * @param getter the field's own getter
     * @param hops the hops to descend first, outermost first; empty for a root-declared field
     * @return the placement
     */
    public static RootPlacement of(String getter, java.util.List<PlacementHop> hops) {
      return new RootPlacement(getter, java.util.List.copyOf(hops), true);
    }

    /**
     * The placement for a field the model declares but exposes no compilable getter path to.
     *
     * @return an unreachable placement
     */
    public static RootPlacement unreachable() {
      return new RootPlacement(null, java.util.List.of(), false);
    }
  }

  /**
   * One {@code @JsonUnwrapped} hop of a {@link RootPlacement}: the getter to invoke, and the
   * fully-qualified type it returns (which the next hop's getter, or the placement's own getter, is
   * invoked on).
   *
   * @param getter the hop's getter, e.g. {@code getApplicant1DQ}
   * @param targetFqn the fully-qualified name of the type the getter returns
   */
  record PlacementHop(String getter, String targetFqn) {
  }

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
    private final String nestedTypeId;

    /**
     * Creates a member resolution for a member read off the parsed model, whose nested type is
     * therefore known as a handle (or is a leaf).
     *
     * @param getter the member's getter name (e.g. {@code getOrganisationID})
     * @param nested the type to descend into for a further segment (the collection element type when
     *     {@code collection} is true), or null when the member is a leaf the walk cannot descend past
     * @param collection whether the member is a {@code Collection} (list) in the model
     * @param declaredHint the member's declared {@code @CCD(hint)}, or null
     */
    public MemberResolution(String getter, Handle nested, boolean collection, String declaredHint) {
      this(getter, nested, collection, declaredHint, null);
    }

    /**
     * Creates a member resolution whose nested type is named by a definition complex-type ID rather
     * than by a parsed-model handle — the case for a member the retrofit patch <em>synthesises</em>,
     * which exists in the definition but not yet in the source the graph reads.
     *
     * @param getter the member's getter name
     * @param nested the parsed nested type, or null when it is named by {@code nestedTypeId} instead
     * @param collection whether the member is a {@code Collection}
     * @param declaredHint the member's declared {@code @CCD(hint)}, or null
     * @param nestedTypeId the definition complex-type ID to descend by (a collection's ELEMENT type
     *     ID) when {@code nested} is null; null when the member is a leaf
     */
    public MemberResolution(String getter, Handle nested, boolean collection, String declaredHint,
        String nestedTypeId) {
      this.getter = getter;
      this.nested = nested;
      this.collection = collection;
      this.declaredHint = declaredHint;
      this.nestedTypeId = nestedTypeId;
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

    /**
     * The definition complex-type ID to descend by when {@link #nested()} is null but the member is not
     * a leaf — a synthesised member, whose declared type the graph cannot hand back as a parsed handle.
     * The caller resolves it via {@link #complexTypeHandle} (the team's class) or, failing that, the
     * generated / SDK-predefined type of that ID. Null when the member is a leaf.
     *
     * @return the nested complex-type ID, or null
     */
    public String nestedTypeId() {
      return nestedTypeId;
    }
  }
}
