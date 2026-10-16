package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The model classes the SDK's own reachability walk reaches from the root case-data class — the set
 * whose members {@code ComplexTypeGenerator} emits {@code ComplexTypes} rows for.
 *
 * <p>It mirrors {@code ConfigResolver.resolve}: every non-static, non-ignored field of the class and
 * its {@code extends} chain contributes its type, a collection contributing its element type (through
 * one level of generic element wrapper, {@code List<ListValue<X>>} → {@code X}), and the walk descends
 * into each type it reaches, guarded on the current path. A type reached only through a
 * {@code @JsonUnwrapped} member is descended into but NOT recorded — the SDK excludes unwrapped
 * holders from {@code ComplexTypes} because their members flatten into the enclosing type's rows.
 *
 * <p>Deliberately NOT the {@link RetrofitTypeTokens} descent, which unwraps a hand-rolled
 * {@code {id, value}} element wrapper to its value class. The SDK does no such thing: it reaches the
 * wrapper, records it, and then reaches the value class through the wrapper's own {@code value} field
 * — which is exactly why sscs's {@code Hearing} wrapper emits a {@code ComplexTypes} row of its own
 * (ID {@code Hearing}, {@code ListElementCode value}) that no definition row declares. Reproducing
 * the SDK's walk is the whole point: what it reaches is what needs a row, or needs suppressing.
 *
 * <p>Two approximations, both benign for the one decision this feeds ({@link RetrofitPatchEmitter}'s
 * suppression of a reachable class the definition never declares, which emits nothing either way):
 * a field declared against a type VARIABLE resolves to no parsed class here where the SDK resolves it
 * through the subclass's type argument (under-reach), and a field the definition matched but whose
 * enclosing class the SDK never reaches is still walked (over-reach).
 */
final class RetrofitReachableTypes {

  /**
   * Collection raw types the SDK treats structurally, mirroring {@link TypeInference}.
   */
  private static final Set<String> COLLECTIONS = Set.of(
      "List", "Set", "Collection", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
      "SortedSet", "TreeSet");

  /** Guards against a pathological source graph; real models nest a handful of levels. */
  private static final int MAX_DEPTH = 40;

  private final ModelSourceIndex index;

  RetrofitReachableTypes(ModelSourceIndex index) {
    this.index = index;
  }

  /**
   * The classes reachable from a root model class, in walk order.
   *
   * @param root the root case-data class
   * @param ignoredMembers {@code declaringFqn#memberName} keys of the fields the patch is about to
   *                       mark {@code @CCD(ignore = true)} on every class that reaches them — the SDK
   *                       excludes an ignored field from the walk, so a type only such a field names
   *                       is not reachable in the patched model either
   * @return the reachable types, root excluded
   */
  Set<ModelSourceIndex.Type> from(ModelSourceIndex.Type root, Set<String> ignoredMembers) {
    Map<String, ModelSourceIndex.Type> reached = new LinkedHashMap<>();
    if (root != null) {
      walk(root, reached, new LinkedHashSet<>(), ignoredMembers, 0);
    }
    return new LinkedHashSet<>(reached.values());
  }

  private void walk(ModelSourceIndex.Type type, Map<String, ModelSourceIndex.Type> reached,
      Set<String> path, Set<String> ignoredMembers, int depth) {
    if (depth > MAX_DEPTH || !path.add(type.fqn)) {
      return;
    }
    ModelSourceIndex.Type current = type;
    Set<String> climbed = new LinkedHashSet<>();
    while (current != null && climbed.add(current.fqn)) {
      for (FieldDeclaration field : current.decl.getFields()) {
        if (isExcluded(current, field, ignoredMembers)) {
          continue;
        }
        boolean unwrapped = Annotations.has(field, "JsonUnwrapped");
        for (VariableDeclarator var : field.getVariables()) {
          fieldType(current, var).ifPresent(resolved -> {
            if (!unwrapped && !resolved.isEnum()) {
              reached.putIfAbsent(resolved.fqn, resolved);
            }
            walk(resolved, reached, path, ignoredMembers, depth + 1);
          });
        }
      }
      current = superclassOf(current).orElse(null);
    }
    path.remove(type.fqn);
  }

  /**
   * The type a field contributes to the walk: a collection's element type (descending through one
   * generic element wrapper), else the declared type's own raw name. Empty when no parsed model type
   * is named.
   */
  private Optional<ModelSourceIndex.Type> fieldType(
      ModelSourceIndex.Type owner, VariableDeclarator var) {
    if (!(var.getType() instanceof ClassOrInterfaceType cit)) {
      return Optional.empty();
    }
    if (!COLLECTIONS.contains(cit.getNameAsString())) {
      return index.resolve(owner.unit, cit);
    }
    Optional<ClassOrInterfaceType> element = firstTypeArgument(cit);
    if (element.isEmpty()) {
      return Optional.empty();
    }
    ClassOrInterfaceType inner = firstTypeArgument(element.get()).orElse(element.get());
    return index.resolve(owner.unit, inner);
  }

  /**
   * Whether the SDK's walk skips this field: static, {@code @JsonIgnore}, an existing
   * {@code @CCD(ignore = true)}, or one the patch is about to add that annotation to.
   */
  private boolean isExcluded(
      ModelSourceIndex.Type owner, FieldDeclaration field, Set<String> ignoredMembers) {
    if (field.hasModifier(Modifier.Keyword.STATIC)) {
      return true;
    }
    if (Annotations.has(field, "JsonIgnore")) {
      return true;
    }
    if (Annotations.find(field, "CCD")
        .map(ann -> Annotations.booleanMemberTrue(ann, "ignore"))
        .orElse(false)) {
      return true;
    }
    return field.getVariables().stream()
        .allMatch(var -> ignoredMembers.contains(owner.fqn + "#" + var.getNameAsString()));
  }

  private Optional<ModelSourceIndex.Type> superclassOf(ModelSourceIndex.Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return Optional.empty();
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    return extended.isEmpty() ? Optional.empty() : index.resolve(type.unit, extended.get(0));
  }

  private static Optional<ClassOrInterfaceType> firstTypeArgument(ClassOrInterfaceType type) {
    return type.getTypeArguments()
        .flatMap(args -> args.isEmpty() ? Optional.empty() : Optional.of(args.get(0)))
        .filter(t -> t instanceof ClassOrInterfaceType)
        .map(t -> (ClassOrInterfaceType) t);
  }
}
