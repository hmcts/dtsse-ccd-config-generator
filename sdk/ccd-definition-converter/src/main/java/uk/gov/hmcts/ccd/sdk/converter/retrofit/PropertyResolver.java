package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.ModelSourceIndex.Type;

/**
 * Walks a model class and produces the CCD field ID → property map the SDK itself would derive at
 * generation time. This is the heart of the retrofit matcher, and it mirrors the SDK's own
 * resolution rules exactly:
 *
 * <ul>
 *   <li><b>Field name</b> is the default ID — {@code FieldUtils.getFieldId} reads
 *       {@code field.getName()}.</li>
 *   <li><b>{@code @JsonProperty("id")}</b> on a field overrides the name — same method.</li>
 *   <li><b>{@code @JsonUnwrapped}</b> recursion descends into the unwrapped type, composing IDs as
 *       {@code prefix + capitalize(childName)} for a non-empty prefix and {@code childName}
 *       verbatim for a prefix-less unwrap — {@code CaseFieldGenerator.appendUnwrapped} plus
 *       {@code getFieldId}'s {@code prefix.isEmpty()} branch (so SSCS's 23 prefix-less unwraps
 *       flatten with no capitalisation change).</li>
 *   <li><b>Superclass fields</b> are included, subclass-first — {@code FieldUtils.getCaseFields}
 *       delegates to Spring's {@code ReflectionUtils.doWithFields}, which walks up the
 *       {@code extends} chain.</li>
 *   <li><b>{@code @JsonIgnore}</b> (present in any form) or <b>{@code @CCD(ignore = true)}</b>
 *       excludes a field — {@code FieldUtils.isFieldIgnored}. Static fields are excluded too
 *       (Jackson never serialises them and the SDK's clustering walk skips them).</li>
 * </ul>
 */
final class PropertyResolver {

  private final ModelSourceIndex index;

  PropertyResolver(ModelSourceIndex index) {
    this.index = index;
  }

  /** The outcome of resolving one model class: its emittable properties plus notable counts. */
  static final class Resolution {
    /** CCD field ID to the resolved property. Insertion order = generation order. */
    final Map<String, ResolvedProperty> properties = new LinkedHashMap<>();
    /**
     * Distinct {@code @JsonUnwrapped} sub-objects walked (across the whole tree).
     */
    int unwrappedCount;
    /**
     * Prefix-less {@code @JsonUnwrapped} sub-objects among {@link #unwrappedCount}.
     */
    int prefixlessUnwrappedCount;
    /**
     * Fields excluded by {@code @JsonIgnore} / {@code @CCD(ignore=true)}.
     */
    int ignoredCount;
    /**
     * Superclasses walked above the root model class (its {@code extends} chain depth).
     */
    int superclassCount;
  }

  /**
   * Resolves every emittable CCD property of a root model class, walking superclasses and
   * {@code @JsonUnwrapped} clusters exactly as the SDK does.
   *
   * @param root the root model type (e.g. {@code CaseData})
   * @return the resolution
   */
  Resolution resolve(Type root) {
    Resolution resolution = new Resolution();
    walk(root, "", resolution, new LinkedHashSet<>(), true, null);
    return resolution;
  }

  /**
   * Walks a type and its superclass chain, collecting fields under the running {@code prefix}.
   *
   * @param type the type to walk
   * @param prefix the {@code @JsonUnwrapped} prefix composed so far
   * @param resolution the accumulating result
   * @param visiting cycle guard of FQNs on the current path
   * @param countSuperclasses true only for the root's own extends chain (so nested unwrapped
   *                          sub-objects' superclasses do not inflate the reported chain depth)
   */
  private void walk(Type type, String prefix, Resolution resolution, Set<String> visiting,
      boolean countSuperclasses, ResolvedProperty.UnwrapRef unwrap) {
    if (type == null || !visiting.add(type.fqn)) {
      // Guard against a cyclic @JsonUnwrapped/extends graph (defensive; real models are acyclic).
      return;
    }
    // Subclass fields are collected before the superclass is walked; putIfAbsent then keeps the
    // subclass's field when both declare the same CCD ID — matching ReflectionUtils.doWithFields,
    // which visits the subclass first.
    collectFields(type, prefix, resolution, visiting, unwrap);

    Optional<Type> superType = superclassOf(type);
    if (superType.isPresent()) {
      if (countSuperclasses) {
        resolution.superclassCount++;
      }
      walk(superType.get(), prefix, resolution, visiting, countSuperclasses, unwrap);
    }

    visiting.remove(type.fqn);
  }

  private void collectFields(Type type, String prefix, Resolution resolution,
      Set<String> visiting, ResolvedProperty.UnwrapRef unwrap) {
    for (FieldDeclaration field : type.decl.findAll(FieldDeclaration.class)) {
      // findAll descends into nested classes; keep only fields declared directly on this type.
      if (!field.getParentNode().map(p -> p == type.decl).orElse(false)) {
        continue;
      }
      if (field.hasModifier(Modifier.Keyword.STATIC)) {
        continue;
      }
      if (Annotations.has(field, "JsonUnwrapped")) {
        descendUnwrapped(type, field, prefix, resolution, visiting, unwrap);
        continue;
      }
      if (isIgnored(field)) {
        resolution.ignoredCount++;
        continue;
      }
      for (VariableDeclarator var : field.getVariables()) {
        String id = fieldId(field, var, prefix);
        resolution.properties.putIfAbsent(id, new ResolvedProperty(
            id, type.simpleName, var.getNameAsString(), var.getType(), type.unit, unwrap,
            type.file));
      }
    }
  }

  private void descendUnwrapped(Type owner, FieldDeclaration field, String prefix,
      Resolution resolution, Set<String> visiting, ResolvedProperty.UnwrapRef unwrap) {
    String childPrefix = Annotations.find(field, "JsonUnwrapped")
        .flatMap(ann -> Annotations.stringMember(ann, "prefix"))
        .orElse("");
    resolution.unwrappedCount++;
    if (childPrefix.isEmpty()) {
      resolution.prefixlessUnwrappedCount++;
    }
    // Compose the prefix exactly as CaseFieldGenerator.appendUnwrapped does: an empty running
    // prefix takes the child prefix verbatim; otherwise concat(capitalize(childPrefix)).
    String composed = prefix.isEmpty() ? childPrefix : prefix + capitalize(childPrefix);
    field.getVariables().stream().findFirst().ifPresent(var ->
        resolveFieldType(owner, var.getType())
            .ifPresent(t -> {
              // Each unwrap step appends a hop to the chain: a top-level unwrap of the ROOT class
              // starts the chain (.complex(CaseData::getParent)…); a nested unwrap extends it with a
              // further .complex(ParentType::getChild) so the leaf getter is finally emitted on the
              // type that actually declares it. The SDK's FieldCollectionBuilder supports nested
              // .complex() blocks and re-flattens the @JsonUnwrapped prefixes, so a multi-level chain
              // round-trips (Civil's CaseData.mediation → Mediation.mediationSuccessful leaves).
              ResolvedProperty.Hop hop =
                  new ResolvedProperty.Hop(var.getNameAsString(), t.simpleName, t.packageName);
              ResolvedProperty.UnwrapRef childRef = unwrap != null ? unwrap.plus(hop)
                  : new ResolvedProperty.UnwrapRef(java.util.List.of(hop));
              walk(t, composed, resolution, visiting, false, childRef);
            }));
  }

  /**
   * The CCD ID for a field: {@code @JsonProperty("x")} value if present and non-empty, else the
   * field name, prefixed via {@code prefix + capitalize(name)} for a non-empty prefix.
   */
  private String fieldId(FieldDeclaration field, VariableDeclarator var, String prefix) {
    String name = Annotations.find(field, "JsonProperty")
        .flatMap(Annotations::stringValue)
        .filter(v -> !v.isEmpty())
        .orElse(var.getNameAsString());
    return prefix.isEmpty() ? name : prefix + capitalize(name);
  }

  private boolean isIgnored(FieldDeclaration field) {
    if (Annotations.has(field, "JsonIgnore")) {
      return true;
    }
    return Annotations.find(field, "CCD")
        .map(ann -> Annotations.booleanMemberTrue(ann, "ignore"))
        .orElse(false);
  }

  private Optional<Type> superclassOf(Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return Optional.empty();
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    if (extended.isEmpty()) {
      return Optional.empty();
    }
    return index.resolve(type.unit, extended.get(0));
  }

  private Optional<Type> resolveFieldType(Type owner, com.github.javaparser.ast.type.Type type) {
    if (type instanceof ClassOrInterfaceType cit) {
      return index.resolve(owner.unit, cit);
    }
    return Optional.empty();
  }

  /**
   * Exposes the resolution's properties as a list, in generation (insertion) order.
   *
   * @param resolution a resolution produced by {@link #resolve}
   * @return the resolved properties as a list
   */
  List<ResolvedProperty> asList(Resolution resolution) {
    return new ArrayList<>(resolution.properties.values());
  }

  /**
   * Capitalises the first character, leaving the rest unchanged — matching
   * {@code org.apache.commons.lang3.StringUtils.capitalize}, which the SDK's
   * {@code CaseFieldGenerator.appendUnwrapped} and {@code FieldUtils.getFieldId} use to compose an
   * unwrapped prefix. An empty string is returned unchanged, so a prefix-less unwrap composes the
   * child name verbatim.
   *
   * @param value the string to capitalise
   * @return the capitalised string
   */
  private static String capitalize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
