package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.FieldDeclaration;
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
 * synthesised companion) does not resolve, so the whole group falls back to a verbatim row passthrough
 * rather than emitting a broken getter reference.
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

  /**
   * Creates the graph.
   *
   * @param index the parsed model source index
   * @param resolution the matcher's resolution (CCD field ID → resolved model property)
   */
  public RetrofitEventComplexTypeGraph(
      ModelSourceIndex index, PropertyResolver.Resolution resolution) {
    this.index = index;
    this.propertiesById = resolution.properties;
  }

  @Override
  public Optional<Handle> rootHandle(String caseFieldId) {
    ResolvedProperty property = propertiesById.get(caseFieldId);
    if (property == null || !(property.declaredType instanceof ClassOrInterfaceType cit)) {
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
  public Optional<MemberResolution> member(Handle owner, String segment) {
    ModelSourceIndex.Type ownerType = ((TypeHandle) owner).type;
    Optional<MemberField> found = findMember(ownerType, segment);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    MemberField member = found.get();
    String getter = "get" + capitalise(member.fieldName);
    boolean collection = member.declared instanceof ClassOrInterfaceType cit
        && COLLECTIONS.contains(cit.getNameAsString());
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
        }
      }
      current = superclassOf(current).orElse(null);
    }
    return Optional.empty();
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
    return index.resolve(context, inner).map(this::unwrapValueWrapper);
  }

  /**
   * Unwraps a hand-rolled CCD collection-element wrapper to the type its {@code value} member holds,
   * or returns the type unchanged when it is not such a wrapper.
   *
   * <p>The discriminator is deliberately strict: the type's serialisable members, across its whole
   * {@code extends} chain, must be exactly {@code value} (optionally alongside CCD's element
   * {@code id}), and {@code value} must resolve to another parsed model class. A type that merely
   * happens to have a {@code value} member alongside real data members is not a wrapper and is left
   * alone; a {@code value} typed as a String/enum/JDK type resolves to nothing and is likewise left
   * alone, so {@code MultiBundleConfig}-style value objects are unaffected.
   *
   * <p>An inherited {@code value} may be declared against a type variable
   * ({@code SscsDocument extends AbstractDocument<SscsDocumentDetails>}, where {@code AbstractDocument}
   * declares {@code D value}), so type arguments are carried down the chain and substituted.
   */
  private ModelSourceIndex.Type unwrapValueWrapper(ModelSourceIndex.Type element) {
    Set<String> memberIds = new java.util.LinkedHashSet<>();
    BoundType valueType = null;
    Map<String, BoundType> bindings = Map.of();
    ModelSourceIndex.Type current = element;
    int guard = 0;
    Set<String> visited = new java.util.HashSet<>();
    while (current != null && guard++ < 20 && visited.add(current.fqn)) {
      for (FieldDeclaration field : declaredFields(current)) {
        if (isIgnored(field)) {
          continue;
        }
        for (VariableDeclarator var : field.getVariables()) {
          String id = effectiveId(field, var);
          memberIds.add(id);
          if ("value".equals(id) && valueType == null) {
            valueType = substitute(var.getType(), current.unit, bindings);
          }
        }
      }
      bindings = superclassBindings(current, bindings);
      current = superclassOf(current).orElse(null);
    }
    if (valueType == null || !WRAPPER_MEMBER_IDS.containsAll(memberIds)) {
      return element;
    }
    if (!(valueType.type instanceof ClassOrInterfaceType cit)) {
      return element;
    }
    return index.resolve(valueType.context, cit)
        .filter(t -> !t.isEnum())
        .orElse(element);
  }

  /**
   * The member ids a CCD collection-element wrapper may carry: the value payload plus the element id
   * CCD assigns each collection entry.
   */
  private static final Set<String> WRAPPER_MEMBER_IDS = Set.of("value", "id");

  /**
   * An AST type together with the compilation unit it was written in, so it resolves in the right
   * import scope after being carried across files by type-argument substitution.
   */
  private record BoundType(Type type, com.github.javaparser.ast.CompilationUnit context) {
  }

  /**
   * Resolves a declared type through the current type-variable bindings: a bare simple name matching a
   * bound type variable becomes that binding (with the unit it was written in), else the type as-is.
   */
  private BoundType substitute(
      Type declared, com.github.javaparser.ast.CompilationUnit context,
      Map<String, BoundType> bindings) {
    if (declared instanceof ClassOrInterfaceType cit && cit.getTypeArguments().isEmpty()) {
      BoundType bound = bindings.get(cit.getNameAsString());
      if (bound != null) {
        return bound;
      }
    }
    return new BoundType(declared, context);
  }

  /**
   * The type-variable bindings a type's {@code extends} clause imposes on its superclass: the
   * superclass's declared type parameters mapped to the arguments the subclass supplies, each
   * substituted through the bindings already in force so a chain of generic subclasses composes.
   */
  private Map<String, BoundType> superclassBindings(
      ModelSourceIndex.Type type, Map<String, BoundType> inherited) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return Map.of();
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    if (extended.isEmpty()) {
      return Map.of();
    }
    ClassOrInterfaceType supertype = extended.get(0);
    List<Type> args = supertype.getTypeArguments().map(ArrayList::new).orElseGet(ArrayList::new);
    Optional<ModelSourceIndex.Type> resolvedSuper = index.resolve(type.unit, supertype);
    if (args.isEmpty() || resolvedSuper.isEmpty()
        || !resolvedSuper.get().decl.isClassOrInterfaceDeclaration()) {
      return Map.of();
    }
    var params = resolvedSuper.get().decl.asClassOrInterfaceDeclaration().getTypeParameters();
    Map<String, BoundType> bindings = new java.util.LinkedHashMap<>();
    for (int i = 0; i < params.size() && i < args.size(); i++) {
      bindings.put(params.get(i).getNameAsString(), substitute(args.get(i), type.unit, inherited));
    }
    return bindings;
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
