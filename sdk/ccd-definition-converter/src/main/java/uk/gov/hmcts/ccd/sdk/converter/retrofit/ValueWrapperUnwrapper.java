package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unwraps a hand-rolled CCD collection-element wrapper class to the class its {@code value} member
 * holds.
 *
 * <p>CCD serialises every collection element as {@code {id, value}}, so the member namespace a
 * definition addresses on a collection's element type — both a {@code CaseEventToComplexTypes}
 * {@code ListElementCode} and the {@code ComplexTypes} sheet's own member rows for that type ID —
 * is rooted at the <em>value</em> type, not at the wrapper. The generic form
 * ({@code List<ListValue<X>>}, {@code List<CcdValue<X>>}) is unwrapped structurally by the type
 * argument; sscs instead declares non-generic wrappers ({@code List<Bundle>} where {@code Bundle}
 * holds a single {@code BundleDetails value}), which no type-argument step can see through.
 *
 * <p>Both the {@code CaseEventToComplexTypes} member walk
 * ({@link RetrofitEventComplexTypeGraph}) and the {@code ComplexTypes} member annotation planner
 * ({@link RetrofitPatchEmitter}) must agree on the target, so the discriminator lives here rather
 * than being duplicated. Without it the planner tried to annotate the definition's members onto the
 * wrapper — which declares only {@code value} — so every member looked definition-only and was
 * routed to synthesis, where the wrapper's {@code @Value} + single-arg {@code @JsonCreator} idiom
 * refused it: 111 members across 22 sscs classes reported as "add the field by hand" when the
 * fields already existed on the {@code *Details} value class all along.
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
final class ValueWrapperUnwrapper {

  /**
   * The member ids a CCD collection-element wrapper may carry: the value payload plus the element id
   * CCD assigns each collection entry.
   */
  private static final Set<String> WRAPPER_MEMBER_IDS = Set.of("value", "id");

  private final ModelSourceIndex index;

  ValueWrapperUnwrapper(ModelSourceIndex index) {
    this.index = index;
  }

  /**
   * The class whose members a definition addresses on {@code element}: its {@code value} type when
   * {@code element} is a hand-rolled {@code {id, value}} wrapper, else {@code element} unchanged.
   *
   * @param element the resolved element/complex class
   * @return the value type to address members on, never null
   */
  ModelSourceIndex.Type unwrap(ModelSourceIndex.Type element) {
    Set<String> memberIds = new LinkedHashSet<>();
    BoundType valueType = null;
    Map<String, BoundType> bindings = Map.of();
    ModelSourceIndex.Type current = element;
    int guard = 0;
    Set<String> visited = new HashSet<>();
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
   * The fields declared directly on a type — {@code findAll} descends into nested classes, and
   * statics are never serialised by Jackson nor walked by the SDK.
   */
  private List<FieldDeclaration> declaredFields(ModelSourceIndex.Type type) {
    List<FieldDeclaration> fields = new ArrayList<>();
    for (FieldDeclaration field : type.decl.findAll(FieldDeclaration.class)) {
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

  /**
   * The CCD id of a member: {@code @JsonProperty} value if present and non-empty, else the field
   * name — exactly the SDK's {@code FieldUtils.getFieldId} with no prefix.
   */
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
   * Resolves a declared type through the current type-variable bindings: a bare simple name matching
   * a bound type variable becomes that binding (with the unit it was written in), else the type
   * as-is.
   */
  private BoundType substitute(
      Type declared, CompilationUnit context, Map<String, BoundType> bindings) {
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
    Map<String, BoundType> bindings = new LinkedHashMap<>();
    for (int i = 0; i < params.size() && i < args.size(); i++) {
      bindings.put(params.get(i).getNameAsString(), substitute(args.get(i), type.unit, inherited));
    }
    return bindings;
  }

  /**
   * An AST type together with the compilation unit it was written in, so it resolves in the right
   * import scope after being carried across files by type-argument substitution.
   */
  private record BoundType(Type type, CompilationUnit context) {
  }
}
