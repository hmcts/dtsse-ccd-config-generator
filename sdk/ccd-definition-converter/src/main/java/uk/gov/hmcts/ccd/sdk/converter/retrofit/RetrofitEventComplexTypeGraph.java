package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.model.RetrofitModelTypeGraph;

/**
 * The retrofit binding of {@link RetrofitModelTypeGraph} over the parsed model AST: resolves a
 * complex {@code CaseData} field, and every dotted {@code CaseEventToComplexTypes} member segment
 * beneath it, against the class the field is <em>actually declared as</em> in the team's model.
 *
 * <p>This is what makes a {@code CaseEventToComplexTypes} chain reference the team's own class (with
 * its real getters) instead of the SDK-predefined type of the same complex-type ID: probate's
 * {@code model.ccd.raw.ChangeOrganisationRequest} whose {@code OrganisationToAdd} member is the
 * team's {@code model.caseaccess.Organisation} (getter {@code getOrganisationID}), not the SDK's
 * {@code uk.gov.hmcts.ccd.sdk.type.Organisation} ({@code getOrganisationId}); prl's {@code PartyDetails}
 * scalar field (not the synthesised {@code PartyDetailsApplicant} sibling); fpl's {@code Address}. A
 * member with no Java backing on the real class (a definition-only label field homed onto a richer
 * synthesised companion) does not resolve, so that row falls back to a verbatim row passthrough rather
 * than emitting a broken getter reference.
 *
 * <p>"No Java backing" means <em>after the retrofit patch is applied</em>, not merely in the parsed
 * source: the patch synthesises definition-only complex-type members as real fields, so those members
 * resolve here too, and a dotted code descends PAST one by the complex-type id its synthesised field is
 * declared with (see {@link RetrofitPlannedSynthesis} and {@link #complexTypeHandle}). Reading only the
 * pre-patch source made the converter pass a member through as raw JSON in the very same run whose patch
 * added the field for it.
 *
 * <p>Field → declared-type lookups come from the matcher's {@link PropertyResolver.Resolution}
 * (already computed for this run); member walks descend the parsed AST directly, mirroring
 * {@code TypeInference}'s collection-element extraction (a {@code List<Wrapper<X>>} / {@code List<X>}
 * element is the walkable type X).
 */
public final class RetrofitEventComplexTypeGraph implements RetrofitModelTypeGraph {

  /**
   * Collection raw types the SDK treats structurally, mirroring {@link TypeInference}.
   */
  private static final Set<String> COLLECTIONS = Set.of(
      "List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
      "SortedSet", "TreeSet");

  private final ModelSourceIndex index;
  private final Map<String, ResolvedProperty> propertiesById;
  private final ValueWrapperUnwrapper unwrapper;
  /**
   * The root case-data class, for checking that the first hop of a {@code @JsonUnwrapped} chain — and
   * a directly-declared field's own getter — is actually resolvable. Null when the caller has no root
   * to hand, in which case {@link #rootPlacement} cannot verify getters and trusts the chain (matching
   * {@code RetrofitModelRebinder.hopChainGettersResolvable}'s own null-root behaviour).
   */
  private final ModelSourceIndex.Type root;
  private final RetrofitPlannedSynthesis plannedSynthesis;
  /**
   * The fields the patch re-declares as a generated companion class. Such a field must NOT resolve
   * against the class the parsed source still names: after the patch it is declared as a companion,
   * which is generated output and so absent from {@link ModelSourceIndex}. Answering "no binding"
   * instead is exactly the signal {@code EventComplexTypeResolver} already treats as "descend the
   * definition's own type id" — see {@link RetrofitPlannedRetypes}.
   */
  private final RetrofitPlannedRetypes plannedRetypes;
  /**
   * Where this walk records each {@code @JsonNaming}-derived name it resolves a member by, so the
   * patch pins it with an explicit {@code @JsonProperty} — see {@link RetrofitPinnedNames}.
   */
  private final RetrofitPinnedNames pinnedNames;

  /**
   * Creates the graph over the model exactly as parsed, with no knowledge of the patch's planned
   * field synthesis. Used by unit tests and by any caller that has no patch plan to hand.
   *
   * @param index the parsed model source index
   * @param resolution the matcher's resolution (CCD field ID → resolved model property)
   */
  public RetrofitEventComplexTypeGraph(
      ModelSourceIndex index, PropertyResolver.Resolution resolution) {
    this(index, resolution, null, RetrofitPlannedSynthesis.empty(),
        RetrofitPlannedRetypes.empty(), RetrofitPinnedNames.empty());
  }

  /**
   * Creates the graph over the model <em>as the applied patch will leave it</em>: a member the patch
   * synthesises onto a complex class resolves here even though the parsed source has no such field,
   * and a member whose serialised id comes from the class's {@code @JsonNaming} strategy resolves
   * because the patch will pin that id with an explicit {@code @JsonProperty}.
   *
   * @param index the parsed model source index
   * @param resolution the matcher's resolution (CCD field ID → resolved model property)
   * @param root the root case-data class, so {@link #rootPlacement} can verify each hop's getter
   *             actually resolves; null to skip that verification
   * @param plannedSynthesis the members the patch emitter has committed to adding
   * @param plannedRetypes the fields the patch emitter has committed to re-declaring as a generated
   *                       companion, which this walk must therefore stop resolving against the class
   *                       the parsed source names
   * @param pinnedNames collects the naming-strategy-derived names this walk relies on, which the
   *                    patch must pin; the two must be fed to the same patch run
   */
  RetrofitEventComplexTypeGraph(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      ModelSourceIndex.Type root, RetrofitPlannedSynthesis plannedSynthesis,
      RetrofitPlannedRetypes plannedRetypes, RetrofitPinnedNames pinnedNames) {
    this.index = index;
    this.propertiesById = resolution.properties;
    this.unwrapper = new ValueWrapperUnwrapper(index);
    this.root = root;
    this.plannedSynthesis = plannedSynthesis;
    this.plannedRetypes = plannedRetypes;
    this.pinnedNames = pinnedNames;
  }

  @Override
  public Optional<Handle> rootHandle(String caseFieldId) {
    ResolvedProperty property = propertiesById.get(caseFieldId);
    if (property == null || !(property.declaredType instanceof ClassOrInterfaceType cit)) {
      return Optional.empty();
    }
    if (plannedRetypes.forCaseField(caseFieldId).isPresent()) {
      // The patch re-declares this field as a generated companion, which has no parsed class to hand
      // back. Empty makes the caller descend the definition's own complex-type id instead — the
      // companion's ComplexTypeModel — which is the type the field will actually have.
      return Optional.empty();
    }
    // A collection field binds to its ELEMENT type (the members' owner); a scalar complex field to
    // its declared class. A field whose declared type is not a parsed model class (a genuinely
    // SDK-typed field) yields empty, so the resolver falls back to the SDK/generated type-id node.
    Optional<ModelSourceIndex.Type> resolved = COLLECTIONS.contains(cit.getNameAsString())
        ? elementType(property.context, cit)
        : index.resolve(property.context, cit);
    return resolved.map(TypeHandle::new);
  }

  @Override
  public boolean rootIsCollection(String caseFieldId) {
    ResolvedProperty property = propertiesById.get(caseFieldId);
    return property != null
        && property.declaredType instanceof ClassOrInterfaceType cit
        && COLLECTIONS.contains(cit.getNameAsString());
  }

  @Override
  public Optional<RootPlacement> rootPlacement(String caseFieldId) {
    ResolvedProperty property = propertiesById.get(caseFieldId);
    if (property == null) {
      // Definition-only field: the patch synthesises it onto the root class (or the CaseDataExtra
      // holder), so this graph has no say — the linker keeps its own derived getter.
      return Optional.empty();
    }
    List<PlacementHop> hops = new ArrayList<>();
    ModelSourceIndex.Type enclosing = root;
    if (property.unwrap != null) {
      for (ResolvedProperty.Hop hop : property.unwrap.hops) {
        // Every hop must be referenceable as PrevType::getHop. A @JsonUnwrapped parent whose Lombok
        // getter is suppressed (@Getter(AccessLevel.NONE), no correctly-named accessor) is an "invalid
        // method reference" compile error, which is exactly the failure this method exists to prevent —
        // so refuse the whole placement rather than emit it. Mirrors the rebinder's
        // hopChainGettersResolvable, which routes the same field's PAGE placement to the column
        // passthrough; the two refuse together.
        if (enclosing != null && !index.hasResolvableGetter(enclosing, hop.memberName)) {
          return Optional.of(RootPlacement.unreachable());
        }
        hops.add(new PlacementHop(
            "get" + capitalise(hop.memberName), hop.typePackage + "." + hop.typeSimpleName));
        // Descend for the next hop's getter check. A hop type outside the parsed source cannot be
        // inspected, so checking stops there (its own members could not be @JsonUnwrapped hops through
        // a suppressed getter we could see).
        enclosing = index.byFqn(hop.typePackage + "." + hop.typeSimpleName).orElse(null);
      }
    }
    if (enclosing != null && !index.hasResolvableGetter(enclosing, property.memberName)) {
      return Optional.of(RootPlacement.unreachable());
    }
    return Optional.of(RootPlacement.of("get" + capitalise(property.memberName), hops));
  }

  @Override
  public Optional<MemberResolution> member(Handle owner, String segment) {
    ModelSourceIndex.Type ownerType = ((TypeHandle) owner).type;
    Optional<MemberField> found = findMember(ownerType, segment);
    if (found.isEmpty()) {
      // Not declared in the parsed source — but the patch may be about to add it. The field the patch
      // adds is declared with the member's own DEFINITION type, so the walk can descend past it by that
      // type's complex-type ID even though no parsed field names it: sscs's supporter.name.firstName
      // descends the synthesised `Supporter supporter` on Appeal. The nested handle stays null because
      // this graph has no parsed type for the id; the caller resolves the id — through
      // complexTypeHandle when the model declares a class for it, else through the generated /
      // SDK-predefined type — so the by-id descent is decided in exactly one place.
      return plannedSynthesis.member(ownerType.fqn, segment)
          .map(planned -> new MemberResolution(
              "get" + capitalise(planned.javaName()), null, planned.collection(), planned.hint(),
              planned.nestedTypeId()));
    }
    MemberField member = found.get();
    String getter = "get" + capitalise(member.fieldName);
    boolean collection = member.declared instanceof ClassOrInterfaceType cit
        && COLLECTIONS.contains(cit.getNameAsString());
    Optional<RetrofitPlannedRetypes.Retype> retyped =
        plannedRetypes.forMember(ownerType.fqn, member.fieldName);
    if (retyped.isPresent()) {
      // The patch re-declares this member as a generated companion. The getter is unchanged (the field
      // keeps its name), but the type to descend into is the companion's — named by its definition
      // complex-type id, exactly as a synthesised member's is, because the companion is generated output
      // with no parsed class to hand back.
      return Optional.of(new MemberResolution(getter, null, collection, member.declaredHint,
          retyped.get().definitionId()));
    }
    // The nested type to descend into for a further segment: the collection element type for a
    // collection member, else the member's declared class. Null when the member is a leaf (a scalar,
    // enum, JDK type, or a type outside the parsed source) — a further segment then fails to resolve
    // and the group falls back to a row passthrough.
    ModelSourceIndex.Type nested = null;
    if (member.declared instanceof ClassOrInterfaceType cit) {
      // elementType already unwraps a {id, value} element wrapper; a SCALAR complex member is never
      // wrapper-unwrapped, since CCD only wraps collection ELEMENTS.
      nested = (collection
          ? elementType(member.context, cit)
          : index.resolve(member.context, cit))
          .filter(t -> !t.isEnum())
          .orElse(null);
    }
    return Optional.of(new MemberResolution(
        getter, nested == null ? null : new TypeHandle(nested), collection, member.declaredHint));
  }

  @Override
  public Optional<Handle> complexTypeHandle(String complexTypeId) {
    if (complexTypeId == null || complexTypeId.isEmpty()) {
      return Optional.empty();
    }
    // The SAME lookup the patch's ComplexTypes member planner uses (RetrofitPatchEmitter's
    // complexTypeClass + unwrap), so the class whose getters this walk emits is the class whose members
    // the patch annotates. Deriving it any other way here would let the two disagree about, say, sscs's
    // Bundle — where the definition's members belong to BundleDetails behind a hand-rolled wrapper.
    String modelPackage = root != null ? root.packageName : null;
    return index.complexTypeClass(complexTypeId, modelPackage)
        .map(unwrapper::unwrap)
        .filter(t -> !t.isEnum())
        .map(TypeHandle::new);
  }

  /**
   * Finds a member of a type (walking its {@code extends} chain, subclass-first) whose effective CCD
   * id — its {@code @JsonProperty} value, else its Java field name — equals the segment. Static and
   * {@code @JsonIgnore}/{@code @CCD(ignore=true)} members are skipped, matching the SDK's own field
   * discovery.
   */
  private Optional<MemberField> findMember(ModelSourceIndex.Type owner, String segment) {
    ModelSourceIndex.Type current = owner;
    int guard = 0;
    java.util.Set<String> visited = new java.util.HashSet<>();
    while (current != null && guard++ < 20 && visited.add(current.fqn)) {
      // A class-level @JsonNaming renames every field Jackson serialises off this class, so the
      // definition's segment may be the STRATEGY's name for a field rather than the field's own
      // (Civil's @JsonNaming(UpperCamelCaseStrategy) Address: field addressLine1, definition segment
      // AddressLine1). Evaluated only when the strategy is one we can compute statically; a custom
      // strategy class yields empty and every member of that class keeps refusing to resolve.
      Optional<NamingStrategy> strategy = NamingStrategy.of(current);
      for (FieldDeclaration field : declaredFields(current)) {
        if (isIgnored(field)) {
          continue;
        }
        String declaredHint = Annotations.find(field, "CCD")
            .flatMap(ann -> Annotations.stringMember(ann, "hint"))
            .orElse(null);
        for (VariableDeclarator var : field.getVariables()) {
          if (segment.equals(effectiveId(field, var))) {
            return Optional.of(new MemberField(
                var.getNameAsString(), var.getType(), current.unit, declaredHint));
          }
          if (matchesNamingStrategy(strategy, field, var, segment)
              || matchesCreatorParameter(current, field, var, segment)) {
            // Resolving here commits the patch to pinning this name: the SDK reads @JsonProperty only
            // off the field and the read method, so without an explicit one it would regenerate the
            // field's own name and silently change the CCD id (see RetrofitPinnedNames). Record
            // before returning so the reliance and the pin are one decision. The id pinned is the
            // segment both paths matched — i.e. the definition's own — so the patch has nothing left
            // to re-derive and cannot disagree with whichever idiom resolved it.
            pinnedNames.record(current.fqn, var.getNameAsString(), segment);
            return Optional.of(new MemberField(
                var.getNameAsString(), var.getType(), current.unit, declaredHint));
          }
        }
      }
      current = superclassOf(current).orElse(null);
    }
    return Optional.empty();
  }

  /**
   * Whether a field serialises under {@code segment} by virtue of its class's {@code @JsonNaming}
   * strategy. A field carrying its own {@code @JsonProperty} is excluded: that annotation already
   * overrides the strategy, so its effective id was decided by {@link #effectiveId} and the strategy
   * has no say — matching Jackson's own precedence.
   */
  private boolean matchesNamingStrategy(Optional<NamingStrategy> strategy, FieldDeclaration field,
      VariableDeclarator var, String segment) {
    if (strategy.isEmpty() || Annotations.has(field, "JsonProperty")) {
      return false;
    }
    return segment.equals(strategy.get().idFor(var.getNameAsString()));
  }

  /**
   * Whether a field serialises under {@code segment} by virtue of a {@code @JsonProperty} on the
   * matching {@code @JsonCreator} CONSTRUCTOR PARAMETER rather than on the field itself.
   *
   * <p>Jackson honours that annotation for both directions on an immutable value class, so the field
   * genuinely appears in the definition under the parameter's name — but the SDK's
   * {@code PropertyUtils.getPropertyName} reads {@code @JsonProperty} only off the field and the read
   * method, so the member walk saw nothing and the row fell back to a verbatim passthrough. fpl's
   * {@code Address} is exactly this: {@code private final String addressLine1} with
   * {@code @JsonProperty("AddressLine1")} on the creator parameter, which alone accounted for 364 of
   * its {@code EventToComplexTypes} fallbacks.
   *
   * <p>Matched by PARAMETER NAME, not position: the parameter must be named for the field it assigns,
   * which is Lombok's and every hand-written creator's convention here, and a positional match would
   * silently mis-bind a constructor whose parameters are reordered. A field carrying its own
   * {@code @JsonProperty} is excluded — that annotation wins over the parameter's for Jackson too, and
   * {@link #effectiveId} has already had its say.
   */
  private boolean matchesCreatorParameter(ModelSourceIndex.Type owner, FieldDeclaration field,
      VariableDeclarator var, String segment) {
    if (Annotations.has(field, "JsonProperty")) {
      return false;
    }
    String fieldName = var.getNameAsString();
    // getConstructors(), not findAll(): the latter descends into nested classes, where a creator
    // parameter of the same name belongs to a different type entirely.
    for (ConstructorDeclaration ctor : owner.decl.getConstructors()) {
      if (!Annotations.has(ctor, "JsonCreator")) {
        continue;
      }
      for (Parameter parameter : ctor.getParameters()) {
        if (!parameter.getNameAsString().equals(fieldName)) {
          continue;
        }
        Optional<String> id = Annotations.find(parameter.getAnnotations(), "JsonProperty")
            .flatMap(Annotations::stringValue);
        if (id.filter(segment::equals).isPresent()) {
          return true;
        }
      }
    }
    return false;
  }

  private List<FieldDeclaration> declaredFields(ModelSourceIndex.Type type) {
    List<FieldDeclaration> fields = new ArrayList<>();
    for (FieldDeclaration field : type.decl.findAll(FieldDeclaration.class)) {
      // findAll descends into nested classes; keep only fields declared directly on this type, and
      // skip statics (Jackson never serialises them and the SDK's walk excludes them).
      if (!field.getParentNode().map(p -> p == type.decl).orElse(false)) {
        continue;
      }
      if (field.hasModifier(com.github.javaparser.ast.Modifier.Keyword.STATIC)) {
        continue;
      }
      fields.add(field);
    }
    return fields;
  }

  private static boolean isIgnored(FieldDeclaration field) {
    if (Annotations.has(field, "JsonIgnore")) {
      return true;
    }
    return Annotations.find(field, "CCD")
        .map(ann -> Annotations.booleanMemberTrue(ann, "ignore"))
        .orElse(false);
  }

  /** The CCD id of a member: {@code @JsonProperty} value if present and non-empty, else the field
   * name — exactly the SDK's {@code FieldUtils.getFieldId} with no prefix. */
  private static String effectiveId(FieldDeclaration field, VariableDeclarator var) {
    return Annotations.find(field, "JsonProperty")
        .flatMap(Annotations::stringValue)
        .filter(v -> !v.isEmpty())
        .orElse(var.getNameAsString());
  }

  private Optional<ModelSourceIndex.Type> superclassOf(ModelSourceIndex.Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return Optional.empty();
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    return extended.isEmpty() ? Optional.empty() : index.resolve(type.unit, extended.get(0));
  }

  /**
   * The walkable element type of a collection declared type, resolved against {@code context}: for a
   * generic wrapper element ({@code List<CollectionMember<X>>}, {@code List<ListValue<X>>}) the inner
   * type X, else the direct element type. Empty when the element is not a parsed model class (a
   * String/JDK collection, or an SDK-typed element).
   */
  private Optional<ModelSourceIndex.Type> elementType(
      com.github.javaparser.ast.CompilationUnit context, ClassOrInterfaceType collection) {
    Optional<ClassOrInterfaceType> element = firstTypeArgument(collection);
    if (element.isEmpty()) {
      return Optional.empty();
    }
    ClassOrInterfaceType inner = firstTypeArgument(element.get()).orElse(element.get());
    // A collection element that is itself a hand-rolled {id, value} wrapper unwraps to its value type:
    // CCD serialises every collection element as {id, value}, so the member namespace a
    // CaseEventToComplexTypes ListElementCode addresses is rooted at the VALUE type, not the wrapper.
    // The generic form (List<ListValue<X>>, List<CcdValue<X>>) is already unwrapped by the type-argument
    // step above; sscs instead declares non-generic wrappers (List<HearingOutcome> where HearingOutcome
    // holds a single HearingOutcomeDetails value), which that step cannot see through.
    return index.resolve(context, inner).map(unwrapper::unwrap);
  }

  private static Optional<ClassOrInterfaceType> firstTypeArgument(ClassOrInterfaceType type) {
    return type.getTypeArguments()
        .flatMap(args -> args.isEmpty() ? Optional.empty() : Optional.of(args.get(0)))
        .filter(t -> t instanceof ClassOrInterfaceType)
        .map(t -> (ClassOrInterfaceType) t);
  }

  private static String capitalise(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /** A resolved model type wrapped as an opaque handle for the resolver. */
  private static final class TypeHandle implements Handle {
    private final ModelSourceIndex.Type type;

    TypeHandle(ModelSourceIndex.Type type) {
      this.type = type;
    }

    @Override
    public String fqn() {
      return type.fqn;
    }
  }

  /** A member field matched by CCD id: its Java name, declared type, declaring unit and hint. */
  private record MemberField(String fieldName, Type declared,
      com.github.javaparser.ast.CompilationUnit context, String declaredHint) {
  }
}
