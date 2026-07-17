package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Decides how synthesised definition-only fields are placed onto the root model class (proposal
 * decision 4), avoiding the JVM/Lombok constructor-argument limit.
 *
 * <p>A Lombok {@code @AllArgsConstructor}/{@code @Builder} generates a constructor taking one
 * argument per non-static field. The JVM caps a method at 255 argument slots (254 usable after the
 * implicit {@code this}), with {@code long}/{@code double} taking two each. SSCS's {@code CaseData}
 * sits at 254 fields already; synthesising 78 more pushes {@code @AllArgsConstructor} past the limit
 * and {@code javac} rejects it ("too many parameters" — finding B2).
 *
 * <p><b>Maintainer-decided placement (2026-07-15):</b> when synthesising onto such a class would
 * exceed a safe threshold, the fields are NOT appended directly. Instead a new class
 * {@code CaseDataExtra} is added (by the patch) in the model package holding <em>all</em> the
 * synthesised fields, and the root class gains ONE prefix-less
 * {@code @JsonUnwrapped private CaseDataExtra caseDataExtra;} member. Prefix-less unwrapping flattens
 * member names verbatim ({@link uk.gov.hmcts.ccd.sdk.FieldUtils#getFieldId} with an empty prefix
 * returns the member name unchanged), so the CCD field IDs are identical — the same SDK path SSCS's
 * own 23 prefix-less unwraps already use. Only the single extra member is added to the root
 * constructor. The borderline case where even that one member tips the class over is still handled
 * (CaseDataExtra absorbs everything) and reported via {@link Plan#borderlineStillOverLimit}.
 *
 * <p>The decision is a pure function of the root's existing field count + the synthesised set, so
 * {@link RetrofitModelRebinder} (which routes the config's field references through the unwrapped
 * parent) and {@link RetrofitPatchEmitter} (which generates the class + the unwrapped member) compute
 * the same {@link Plan} independently.
 */
final class SynthesisPlacement {

  /**
   * A safe field-count threshold below the JVM's 254-usable-slot constructor limit; leaves headroom
   * for the implicit {@code this} slot and any {@code long}/{@code double} field taking two slots.
   */
  static final int DEFAULT_LIMIT = 250;

  /** The base simple name of the added overflow class. */
  static final String EXTRA_CLASS_BASE = "CaseDataExtra";

  /**
   * The member name of the single prefix-less unwrapped field added to the root class.
   */
  static final String EXTRA_MEMBER = "caseDataExtra";

  /**
   * A stable marker the emitted overflow companion carries in its class javadoc, so a later run can
   * recognise its OWN previously-generated companion in the team's model tree and reuse the base name
   * rather than bumping to {@code CaseDataExtra2} (Bug B: a suffix bumped by a fossil companion left
   * the patch's {@code CaseData} field on {@code CaseDataExtra2} while the freshly-generated event
   * classes referenced {@code CaseDataExtra} — a name desync — and stranded the prior companion as
   * dead code). Matched against the file's raw text, so it survives the index parse dropping comments.
   */
  static final String EXTRA_CLASS_MARKER =
      "ccd-definition-converter:retrofit-overflow-companion";

  private final ModelSourceIndex index;
  private final int limit;

  SynthesisPlacement(ModelSourceIndex index) {
    this(index, 0);
  }

  /**
   * Creates a placement decider with a threshold override.
   *
   * @param index the parsed model source index
   * @param limit the field-count threshold; {@code <= 0} uses {@link #DEFAULT_LIMIT}
   */
  SynthesisPlacement(ModelSourceIndex index, int limit) {
    this.index = index;
    this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
  }

  /** The placement decision for a root class and its synthesised fields. */
  static final class Plan {
    /**
     * True when the fields must be moved to a {@code CaseDataExtra} class.
     */
    final boolean overflow;
    /**
     * The {@code CaseDataExtra} class simple name (suffixed if that name already exists).
     */
    final String extraClassName;
    /** True when even the single added unwrapped member leaves the root class over the limit. */
    final boolean borderlineStillOverLimit;
    /** Existing constructor slots on the root class (slot-weighted, superclasses included). */
    final int existingSlots;
    /** Synthesised constructor slots (slot-weighted). */
    final int synthesisedSlots;
    /**
     * The existing prefix-less {@code @JsonUnwrapped} member the synthesised fields are nested into
     * when even the single added {@code caseDataExtra} member would tip the root over the limit
     * (finding B2 borderline). Null when a fresh {@code CaseDataExtra} member is added to the root
     * instead (the common overflow case). Adding the fields to this existing member's class adds ZERO
     * fields to the limited root class.
     */
    final ExistingHost existingHost;

    Plan(boolean overflow, String extraClassName, boolean borderlineStillOverLimit,
        int existingSlots, int synthesisedSlots, ExistingHost existingHost) {
      this.overflow = overflow;
      this.extraClassName = extraClassName;
      this.borderlineStillOverLimit = borderlineStillOverLimit;
      this.existingSlots = existingSlots;
      this.synthesisedSlots = synthesisedSlots;
      this.existingHost = existingHost;
    }
  }

  /**
   * An existing prefix-less {@code @JsonUnwrapped} member of the root class chosen to host the
   * synthesised fields in the borderline case (finding B2): its Java member name (the getter is
   * derived from it), its resolved type class, and that type's package. Because the member is
   * prefix-less {@code @JsonUnwrapped}, fields added to its class flatten to the same CCD IDs as they
   * would on {@code CaseDataExtra} or the root — but NO field is added to the constructor-limited root.
   */
  static final class ExistingHost {
    final String memberName;
    final ModelSourceIndex.Type type;

    ExistingHost(String memberName, ModelSourceIndex.Type type) {
      this.memberName = memberName;
      this.type = type;
    }
  }

  /**
   * Plans placement of the synthesised fields onto the root class.
   *
   * @param root the root model class (may be null → no overflow)
   * @param synthesised the definition-only fields to place
   * @return the placement plan
   */
  Plan plan(ModelSourceIndex.Type root, List<FieldModel> synthesised) {
    if (root == null || synthesised.isEmpty()) {
      return new Plan(false, null, false, 0, 0, null);
    }
    int existing = existingSlots(root);
    int synthSlots = synthesised.stream().mapToInt(f -> slotsFor(f.getJavaType())).sum();
    // Only classes that actually generate an all-args constructor hit the limit. A @SuperBuilder
    // class generates a constructor taking a single builder argument (fpl's 642-field @SuperBuilder
    // model compiles fine), and a plain POJO with a hand-written no-arg constructor takes new fields
    // fine — so never introduce the wrapper for either.
    if (!generatesAllArgsConstructor(root) || existing + synthSlots <= limit) {
      return new Plan(false, null, false, existing, synthSlots, null);
    }
    // The root loses all synthesised fields and gains exactly one unwrapped member; it is still over
    // the limit only if it was already within one slot of it.
    boolean borderline = existing + 1 > limit;
    if (borderline) {
      // Adding even the single @JsonUnwrapped CaseDataExtra member tips the root over the limit
      // (SSCS: 254 fields + 1 = 255 > 254). Nest the synthesised fields into an EXISTING prefix-less
      // @JsonUnwrapped member's class instead, so ZERO fields are added to the root.
      ExistingHost host = chooseExistingHost(root, synthSlots);
      if (host != null) {
        return new Plan(true, null, true, existing, synthSlots, host);
      }
      // No usable existing host (no prefix-less @JsonUnwrapped member is a safe, resolvable,
      // headroom-having target): fall back to the CaseDataExtra member and flag it still-over-limit
      // (the maintainer must relocate one field by hand), the pre-fix behaviour.
      return new Plan(true, uniqueExtraClassName(root.packageName), true, existing, synthSlots, null);
    }
    return new Plan(true, uniqueExtraClassName(root.packageName), false, existing, synthSlots, null);
  }

  /**
   * Chooses an existing prefix-less {@code @JsonUnwrapped} member of the root class to host the
   * synthesised fields in the borderline case: the FIRST alphabetically (by member name, for a
   * deterministic, documentable choice) whose type resolves to an in-model class that (a) is not the
   * hand-written single-arg {@code @JsonCreator}+{@code @Builder} idiom (appending fields breaks its
   * builder binding, finding B3), (b) has a getter the config can reference back to the unwrapped
   * field (finding Bug4), and (c) has enough constructor headroom to absorb the synthesised fields.
   * Returns null when none qualifies.
   */
  private ExistingHost chooseExistingHost(ModelSourceIndex.Type root, int synthSlots) {
    java.util.TreeMap<String, ExistingHost> candidates = new java.util.TreeMap<>();
    for (FieldDeclaration fieldDecl : root.decl.getFields()) {
      if (fieldDecl.isStatic() || !isPrefixlessUnwrapped(fieldDecl)) {
        continue;
      }
      String memberName = fieldDecl.getVariable(0).getNameAsString();
      ModelSourceIndex.Type hostType = index
          .resolve(root.unit, (com.github.javaparser.ast.type.ClassOrInterfaceType)
              fieldDecl.getElementType())
          .orElse(null);
      if (hostType == null || !hostType.isClass()) {
        continue;
      }
      // A @Builder bound to a hand-written explicit constructor (whether or not @JsonCreator): the
      // builder passes the constructor exactly its declared arguments, so appending synthesised fields
      // breaks it (finding B3, generalised — SSCS's Appeal). Never a synthesis host.
      if (hasBuilderBoundExplicitConstructor(hostType)) {
        continue;
      }
      if (!index.hasResolvableGetter(root, memberName)) {
        continue;
      }
      // The host class must have room for all the synthesised fields under its own constructor limit.
      if (generatesAllArgsConstructor(hostType)
          && existingSlots(hostType) + synthSlots > limit) {
        continue;
      }
      candidates.putIfAbsent(memberName, new ExistingHost(memberName, hostType));
    }
    return candidates.isEmpty() ? null : candidates.firstEntry().getValue();
  }

  /**
   * Whether a field declaration is a prefix-less {@code @JsonUnwrapped} (no {@code prefix} member).
   */
  private static boolean isPrefixlessUnwrapped(FieldDeclaration fieldDecl) {
    return fieldDecl.getAnnotations().stream().anyMatch(a -> {
      String name = a.getNameAsString();
      boolean unwrapped = name.equals("JsonUnwrapped") || name.endsWith(".JsonUnwrapped");
      return unwrapped && !a.toString().contains("prefix");
    });
  }

  /**
   * Whether a class declares a class-level {@code @Builder} bound to a hand-written explicit
   * constructor (finding B3, generalised): the builder passes that constructor exactly its declared
   * arguments, so appending synthesised fields breaks it. Covers both the {@code @JsonCreator}
   * flavour and a plain {@code @JsonProperty} constructor (SSCS's {@code Appeal}). Never a synthesis
   * host.
   */
  private static boolean hasBuilderBoundExplicitConstructor(ModelSourceIndex.Type type) {
    boolean builder = type.decl.getAnnotations().stream()
        .anyMatch(a -> a.getNameAsString().equals("Builder")
            || a.getNameAsString().endsWith(".Builder"));
    return builder && !type.decl.getConstructors().isEmpty();
  }

  /**
   * The overflow companion's simple name: the base {@code CaseDataExtra} unless a <em>foreign</em>
   * (hand-written) type of that name already sits in the model package, in which case the name is
   * suffixed to avoid a genuine collision.
   *
   * <p>Crucially, a companion THIS converter emitted on a prior run (recognised by
   * {@link #EXTRA_CLASS_MARKER} in its source) is NOT a collision: the patch recreates it as a new
   * file, so bumping the suffix on account of it would (a) desync the name from the freshly-generated
   * event classes that reference the base name and (b) strand the old companion as dead code (Bug B).
   * Reusing the base name lets the fresh patch overwrite the stale companion in place and keeps every
   * reference on one name.
   */
  private String uniqueExtraClassName(String packageName) {
    String candidate = EXTRA_CLASS_BASE;
    int suffix = 2;
    while (isForeignType(packageName + "." + candidate)) {
      candidate = EXTRA_CLASS_BASE + suffix++;
    }
    return candidate;
  }

  /**
   * Whether a same-named type exists in the model source that is NOT this converter's own prior
   * overflow companion — i.e. a real collision that warrants a suffix. A prior-run companion (its
   * source carrying {@link #EXTRA_CLASS_MARKER}) is treated as absent, so the name is reused and the
   * fresh patch replaces it. Matched against the file's raw text because the index parse drops
   * comments, so the marker is not visible on the parsed declaration.
   */
  private boolean isForeignType(String fqn) {
    ModelSourceIndex.Type existing = index.byFqn(fqn).orElse(null);
    if (existing == null) {
      return false;
    }
    if (existing.file == null) {
      return true;
    }
    try {
      return !java.nio.file.Files.readString(existing.file).contains(EXTRA_CLASS_MARKER);
    } catch (java.io.IOException e) {
      // Unreadable → treat as a real, foreign type (conservative: suffix rather than risk clobbering).
      return true;
    }
  }

  /**
   * The constructor argument slots a Lombok all-args constructor would use for the root class and
   * every superclass reachable in the parsed source: one per non-static field, two for a
   * {@code long}/{@code double}.
   */
  private int existingSlots(ModelSourceIndex.Type root) {
    int slots = 0;
    ModelSourceIndex.Type current = root;
    int guard = 0;
    Set<String> counted = new java.util.HashSet<>();
    while (current != null && guard++ < 20) {
      if (!counted.add(current.fqn)) {
        break;
      }
      for (FieldDeclaration fieldDecl : current.decl.getFields()) {
        if (fieldDecl.isStatic()) {
          continue;
        }
        int perVar = slotsFor(fieldDecl.getElementType().asString());
        slots += perVar * fieldDecl.getVariables().size();
      }
      if (!current.decl.isClassOrInterfaceDeclaration()) {
        break;
      }
      var extended = current.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
      current = extended.isEmpty()
          ? null
          : index.resolve(current.unit, extended.get(0)).orElse(null);
    }
    return slots;
  }

  private static int slotsFor(String javaType) {
    if (javaType == null) {
      return 1;
    }
    String trimmed = javaType.trim();
    return "long".equals(trimmed) || "double".equals(trimmed) ? 2 : 1;
  }

  /**
   * Whether the class would have a Lombok-generated all-args constructor whose parameter count grows
   * with the field count — the only shape that can hit the JVM/Lombok constructor-argument limit
   * (finding B2). This is the case for {@code @AllArgsConstructor}, or {@code @Builder}/{@code @Value}
   * without an explicit constructor (Lombok then generates the all-args form the builder binds to).
   *
   * <p><b>{@code @SuperBuilder} is explicitly NOT this shape.</b> Lombok's {@code @SuperBuilder}
   * generates a single protected constructor taking one {@code builder} argument (plus a
   * {@code @NoArgsConstructor}/{@code @RequiredArgsConstructor} if requested) — never a per-field
   * all-args constructor — so its parameter count does not grow with the field count and it never
   * trips the limit (fpl's 642-field {@code @SuperBuilder} model compiles fine). It is treated as
   * generating an all-args constructor ONLY when it is also annotated {@code @AllArgsConstructor}.
   */
  private static boolean generatesAllArgsConstructor(ModelSourceIndex.Type root) {
    boolean allArgs = hasAnnotation(root, "AllArgsConstructor");
    // @SuperBuilder alone does not synthesise a per-field constructor, so a plain @Builder detection
    // must not be fooled by it — @SuperBuilder's simple name is "SuperBuilder", distinct from
    // "Builder", so hasAnnotation(root, "Builder") already excludes it; assert that intent here.
    boolean plainBuilder = hasAnnotation(root, "Builder");
    boolean value = hasAnnotation(root, "Value");
    boolean explicitCtor = !root.decl.getConstructors().isEmpty();
    return allArgs || ((plainBuilder || value) && !explicitCtor);
  }

  private static boolean hasAnnotation(ModelSourceIndex.Type root, String simpleName) {
    return root.decl.getAnnotations().stream()
        .anyMatch(a -> a.getNameAsString().equals(simpleName)
            || a.getNameAsString().endsWith("." + simpleName));
  }

  /**
   * The Java field names declared directly on a type and every superclass reachable in the parsed
   * source — the names a synthesised field must not collide with (finding B1). Shared by the patch
   * emitter (which skips + reports the collision) and the rebinder (which must NOT emit a config
   * reference to a field the patch did not place), so both agree on the placeable set.
   *
   * @param target the class synthesised fields would be appended to
   * @return the declared field names on the class and its superclasses
   */
  Set<String> declaredFieldNames(ModelSourceIndex.Type target) {
    Set<String> names = new java.util.LinkedHashSet<>();
    ModelSourceIndex.Type current = target;
    int guard = 0;
    while (current != null && guard++ < 20) {
      for (FieldDeclaration fieldDecl : current.decl.getFields()) {
        fieldDecl.getVariables().forEach(v -> names.add(v.getNameAsString()));
      }
      if (!current.decl.isClassOrInterfaceDeclaration()) {
        break;
      }
      var extended = current.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
      current = extended.isEmpty()
          ? null
          : index.resolve(current.unit, extended.get(0)).orElse(null);
    }
    return names;
  }

  static List<FieldModel> copy(List<FieldModel> fields) {
    return new ArrayList<>(fields);
  }
}
