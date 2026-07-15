package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;

/**
 * Rebinds a linked {@link CaseTypeModel} for retrofit config emission so its typed getters reference
 * the team's <em>existing</em> model rather than a freshly generated {@code CaseData}.
 *
 * <p>The linker produced one {@link FieldModel} per data-bearing CaseField, each carrying a
 * generated {@code javaName} (a bean-decapitalised form of the CCD id) plus every {@code @CCD}
 * attribute. Generate mode emits a {@code CaseData} whose member is that {@code javaName}; retrofit
 * mode must instead point the config's {@code CaseData::getX} references at the team's real member
 * names ({@link PropertyResolver}'s resolution). This rebinder therefore, for each field the matcher
 * resolved:
 *
 * <ul>
 *   <li>rewrites {@link FieldModel#getJavaName()} to the resolved model member name (so
 *       {@code EventsConfigEmitter} emits {@code get<RealMember>});</li>
 *   <li>for a field the matcher reached through a top-level {@code @JsonUnwrapped} member, records a
 *       {@link ClusteredFieldRef} so it is placed via {@code .complex(CaseData::getParent)
 *       .x(ParentType::getMember)} — mirroring generate mode's clustered leaves.</li>
 * </ul>
 *
 * <p>Fields the matcher did <em>not</em> resolve (UNMATCHED_DEFINITION_FIELD, synthesised onto the
 * model class by the patch) keep the linker's {@code javaName}: the synthesised field is named with
 * exactly that {@code javaName}, so the config's {@code get<javaName>} reference resolves. Fixed
 * lists whose ID matches an existing model enum are dropped so no duplicate enum is generated (the
 * SDK reflects the team's enum from the annotated field instead — proposal decision on reusing model
 * enums; {@link TypeInference} distinguishes the case).
 */
final class RetrofitModelRebinder {

  private final ModelSourceIndex index;
  private final Map<String, ResolvedProperty> properties;
  private final TypeReconciler reconciler;
  private final SynthesisPlacement placement;
  private final ModelSourceIndex.Type rootType;

  RetrofitModelRebinder(ModelSourceIndex index, PropertyResolver.Resolution resolution) {
    this(index, resolution, null);
  }

  RetrofitModelRebinder(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      ModelSourceIndex.Type rootType) {
    this(index, resolution, rootType, 0);
  }

  RetrofitModelRebinder(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      ModelSourceIndex.Type rootType, int constructorLimit) {
    this.index = index;
    this.properties = resolution.properties;
    this.reconciler = new TypeReconciler(index);
    this.placement = new SynthesisPlacement(index, constructorLimit);
    this.rootType = rootType;
  }

  /**
   * Rebinds the model.
   *
   * @param model the linked model (produced with {@code retrofit=true}, so already unclustered)
   * @return a rebound model whose fields/getters target the team's existing model
   */
  CaseTypeModel rebind(CaseTypeModel model) {
    Map<String, ClusteredFieldRef> refs = new LinkedHashMap<>();
    List<FieldModel> reboundFields = new java.util.ArrayList<>();
    // Fields the config must NOT place via a typed getter because the @JsonUnwrapped parent they are
    // reached through has no resolvable getter on the model (finding Bug4). Collected here and handed
    // to the emitters so a broken CaseData::getParent method reference is never emitted.
    Set<String> unplaceable = new java.util.LinkedHashSet<>();

    // B2: when synthesising all the unmatched fields onto the root class would blow the constructor
    // limit, the patch moves them into a CaseDataExtra class reached via a prefix-less
    // @JsonUnwrapped member. The config must then reference each moved field through that parent
    // (.complex(CaseData::getCaseDataExtra).x(CaseDataExtra::getMember)), so compute the same plan
    // the patch emitter does and record a ClusteredFieldRef for each moved field.
    Set<String> declaredOnRoot = rootType == null
        ? java.util.Set.of() : placement.declaredFieldNames(rootType);
    List<FieldModel> synthesised = new java.util.ArrayList<>();
    for (FieldModel field : model.getCaseFields()) {
      // Mirror the patch emitter's placeable set: unmatched fields, minus those whose Java name
      // collides with an existing declared member (finding B1 — the patch skips those, so the config
      // must not reference them through the CaseDataExtra parent either).
      if (properties.get(field.getId()) == null && !declaredOnRoot.contains(field.getJavaName())) {
        synthesised.add(field);
      }
    }
    Set<String> placeableIds = new java.util.HashSet<>();
    synthesised.forEach(f -> placeableIds.add(f.getId()));
    SynthesisPlacement.Plan plan = placement.plan(rootType, synthesised);

    for (FieldModel field : model.getCaseFields()) {
      ResolvedProperty property = properties.get(field.getId());
      if (property == null) {
        // Unmatched definition field: the patch synthesises it onto the model class named after the
        // linker's javaName, so keep the field (and its javaName) unchanged. When the B2 overflow
        // plan fires, the field lives on CaseDataExtra instead, so reference it through the
        // prefix-less @JsonUnwrapped parent.
        reboundFields.add(field);
        if (plan.overflow && placeableIds.contains(field.getId())) {
          if (plan.existingHost != null) {
            // B2 borderline: the synthesised field lives on an EXISTING prefix-less @JsonUnwrapped
            // member's class, reached via that member's own getter (SscsCaseData::getAdjournment etc.).
            refs.put(field.getId(), ClusteredFieldRef.builder()
                .parentGetter(getter(plan.existingHost.memberName))
                .clusterType(plan.existingHost.type.simpleName)
                .clusterTypePackage(plan.existingHost.type.packageName)
                .memberGetter(getter(field.getJavaName()))
                .build());
          } else {
            // Common overflow: the field lives on the added CaseDataExtra class, reached via the added
            // prefix-less @JsonUnwrapped caseDataExtra member.
            refs.put(field.getId(), ClusteredFieldRef.builder()
                .parentGetter(getter(SynthesisPlacement.EXTRA_MEMBER))
                .clusterType(plan.extraClassName)
                .clusterTypePackage(rootType.packageName)
                .memberGetter(getter(field.getJavaName()))
                .build());
          }
        }
        continue;
      }
      // Reconcile the definition's declared type against what the SDK would infer from the TEAM's
      // actual field type (the linker chose overrides for a FRESH field of the definition's type),
      // then rebind the getter onto the resolved model member. The same reconciliation runs on
      // complex-type members in RetrofitPatchEmitter, so nested collection members get overrides too.
      FieldModel rebound = reconciler.reconcile(field, property)
          .toBuilder().javaName(property.memberName).build();
      reboundFields.add(rebound);
      if (property.unwrap != null) {
        // The outermost hop is placed via .complex(CaseData::getParent); any further nested
        // @JsonUnwrapped hops become intermediate .complex(PrevType::getChild) blocks, and the leaf
        // getter is finally invoked on the innermost hop's type (the class that declares it).
        List<ResolvedProperty.Hop> hops = property.unwrap.hops;
        // Bug4: every hop's getter must exist as a resolvable method reference; a @JsonUnwrapped
        // parent whose Lombok getter is suppressed (@Getter(AccessLevel.NONE)) with no correctly-named
        // hand-written accessor cannot be referenced as PrevType::getHop. Emitting it is an "invalid
        // method reference" compile error and the SDK has no public string-id overload for event
        // fields, so mark the field unplaceable — the emitter skips it and the CaseEventToFields
        // column passthrough carries its per-field metadata.
        if (!hopChainGettersResolvable(hops)) {
          unplaceable.add(field.getId());
          continue;
        }
        ResolvedProperty.Hop outer = hops.get(0);
        List<ClusteredFieldRef.Hop> nested = new java.util.ArrayList<>();
        for (int i = 1; i < hops.size(); i++) {
          ResolvedProperty.Hop hop = hops.get(i);
          nested.add(ClusteredFieldRef.Hop.builder()
              .getter(getter(hop.memberName))
              .type(hop.typeSimpleName)
              .typePackage(hop.typePackage)
              .build());
        }
        refs.put(field.getId(), ClusteredFieldRef.builder()
            .parentGetter(getter(outer.memberName))
            .clusterType(outer.typeSimpleName)
            .clusterTypePackage(outer.typePackage)
            .parentHops(nested)
            .memberGetter(getter(property.memberName))
            .build());
      }
    }

    List<FixedListModel> reboundLists = new java.util.ArrayList<>();
    for (FixedListModel list : model.getFixedLists()) {
      // A FixedList whose ID names a top-level type ALREADY in the model is not regenerated: emitting
      // a fresh enum of that simple name in the model package would be a duplicate-type compile error
      // (finding F1 — fpl's HearingVenue is a @Data address class, not an enum). When that existing
      // type is an enum the SDK reflects it from the annotated field (proposal decision 3); when it is
      // a non-enum class the list rows still round-trip via the field's typeParameterOverride /
      // passthrough, but no colliding enum is generated either way.
      if (!index.hasTopLevelType(list.getId())) {
        reboundLists.add(list);
      }
    }

    return model.toBuilder()
        .caseFields(reboundFields)
        .fixedLists(reboundLists)
        .clusteredFieldRefs(refs)
        .unplaceableFieldIds(unplaceable)
        .build();
  }

  /**
   * Whether every {@code @JsonUnwrapped} hop in the chain has a resolvable getter (Bug4): the
   * outermost hop's member on the root class, then each inner hop's member on the previous hop's
   * type. When any hop's parent getter is missing, the whole placement cannot be emitted as a nested
   * {@code .complex(...)} chain. A hop whose type is not in the parsed model source (an external
   * library complex type) is assumed to expose a standard getter — the SDK reflects it fine — so only
   * a resolvable-in-source type with a suppressed/missing getter blocks placement.
   */
  private boolean hopChainGettersResolvable(List<ResolvedProperty.Hop> hops) {
    if (rootType == null) {
      return true;
    }
    ModelSourceIndex.Type enclosing = rootType;
    for (ResolvedProperty.Hop hop : hops) {
      if (enclosing != null && !index.hasResolvableGetter(enclosing, hop.memberName)) {
        return false;
      }
      // Descend to the hop's type for the next hop's getter check; if it is not in the parsed source
      // we cannot inspect it, but such a type's members would not themselves be @JsonUnwrapped hops
      // through a suppressed getter we could see, so stop checking further in-source.
      enclosing = index.byFqn(hop.typePackage + "." + hop.typeSimpleName).orElse(null);
    }
    return true;
  }

  /**
   * The fixed-list IDs that were dropped because the model already declares a same-named enum.
   *
   * @param model the linked model
   * @return the reused enum IDs, sorted
   */
  Set<String> reusedEnumIds(CaseTypeModel model) {
    Set<String> reused = new TreeSet<>();
    for (FixedListModel list : model.getFixedLists()) {
      if (index.hasEnum(list.getId())) {
        reused.add(list.getId());
      }
    }
    return reused;
  }

  private static String getter(String member) {
    return "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
  }
}
