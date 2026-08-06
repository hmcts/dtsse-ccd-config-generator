package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A parsed view of a team's model source tree: every {@code .java} file under
 * {@code --model-source-root} parsed to an AST (no symbol solver — the SDK only ever reflects
 * declared fields, so name-level resolution is all the retrofit matcher needs), with each declared
 * type indexed by both simple name and fully-qualified name.
 *
 * <p>Type references (a field's declared type, a class's {@code extends}) are resolved back to a
 * parsed {@link Type} the way {@code javac} would: an explicit import wins, then a same-package
 * sibling, then — as a last resort for the shared-layout models where a simple name is unique —
 * the global simple-name index. That is enough to walk superclass chains across files and descend
 * into {@code @JsonUnwrapped} sub-objects.
 */
final class ModelSourceIndex {

  /** One declared type (class/interface/enum), with the compilation unit it was parsed from. */
  static final class Type {
    final CompilationUnit unit;
    final TypeDeclaration<?> decl;
    final String packageName;
    final String simpleName;
    final String fqn;
    /**
     * The {@code .java} file this type was parsed from, for the phase-2 patch emitter.
     */
    final Path file;

    Type(CompilationUnit unit, TypeDeclaration<?> decl, String packageName, Path file) {
      this.unit = unit;
      this.decl = decl;
      this.packageName = packageName;
      this.simpleName = decl.getNameAsString();
      this.fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
      this.file = file;
    }

    boolean isEnum() {
      return decl.isEnumDeclaration();
    }

    /**
     * Whether this is a top-level type (declared directly in the compilation unit), as opposed to a
     * member type nested inside another. A CCD complex type / model class always maps to a
     * top-level class; a nested type sharing its simple name (e.g. the {@code Hearing} interface
     * nested in Civil's sealed {@code CaseDataPredicate}) is never the intended target.
     */
    boolean isTopLevel() {
      return decl.isTopLevelType();
    }

    /** Whether this is a class (not an interface, enum, record, or annotation). */
    boolean isClass() {
      return decl.isClassOrInterfaceDeclaration()
          && !decl.asClassOrInterfaceDeclaration().isInterface();
    }
  }

  private final Map<String, List<Type>> bySimpleName = new LinkedHashMap<>();
  private final Map<String, Type> byFqn = new LinkedHashMap<>();
  private final JavaParser parser;
  private int parsedFileCount;
  private Path sourceRoot;

  private ModelSourceIndex() {
    // Drop comments and token ranges: the resolver needs only the declaration structure, and
    // retaining tokens/comments for a large model (Civil parses 3500+ files) exhausts the heap.
    ParserConfiguration config = new ParserConfiguration()
        .setAttributeComments(false)
        .setLexicalPreservationEnabled(false)
        .setStoreTokens(false);
    this.parser = new JavaParser(config);
  }

  /**
   * Parses every {@code .java} file under a source root into an index.
   *
   * @param sourceRoot the {@code src/main/java} root of the team's model
   * @return the populated index
   */
  static ModelSourceIndex parse(Path sourceRoot) {
    ModelSourceIndex index = new ModelSourceIndex();
    index.sourceRoot = sourceRoot;
    List<Path> files = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      stream.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .forEach(files::add);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed walking model source root " + sourceRoot, e);
    }
    for (Path file : files) {
      index.addFile(file);
    }
    return index;
  }

  private void addFile(Path file) {
    CompilationUnit unit;
    try {
      ParseResult<CompilationUnit> result = parser.parse(file);
      if (result.getResult().isEmpty()) {
        // A file JavaParser could not fully parse (exotic syntax, a version gap) is skipped; its
        // types simply will not resolve, surfacing as unmatched rather than aborting the scan.
        return;
      }
      unit = result.getResult().get();
    } catch (Exception e) {
      return;
    }
    parsedFileCount++;
    String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    for (TypeDeclaration<?> decl : unit.findAll(TypeDeclaration.class)) {
      Type type = new Type(unit, decl, pkg, file);
      bySimpleName.computeIfAbsent(type.simpleName, k -> new ArrayList<>()).add(type);
      byFqn.putIfAbsent(type.fqn, type);
    }
  }

  /**
   * The source root this index was parsed from (for computing patch-relative paths).
   *
   * @return the source root
   */
  Path sourceRoot() {
    return sourceRoot;
  }

  int parsedFileCount() {
    return parsedFileCount;
  }

  /**
   * The simple names of every <em>enum</em> declared in the parsed model source. In retrofit mode
   * the converter seeds its generated-companion name allocation with these: a companion complex type
   * is emitted only when the definition type does <em>not</em> resolve to an existing model class
   * (see {@code RetrofitComplexTypeEmitter}), so the only same-named existing type it can collide
   * with is an enum (e.g. the definition {@code benefit} complex type PascalCased to {@code Benefit}
   * vs the sscs domain enum {@code Benefit}). Reserving enum names suffixes such a companion
   * ({@code Benefit2}) rather than emitting a {@code duplicate class}; the CCD wire ID round-trips
   * via {@code @ComplexType(name)}. Model <em>class</em> names are deliberately NOT reserved — a
   * definition complex type that matches an existing class binds to it (no companion), so reserving
   * it would wrongly suffix the shared reference to a companion that is never emitted.
   *
   * @return the set of simple names of all parsed model enums
   */
  Set<String> enumSimpleNames() {
    Set<String> names = new LinkedHashSet<>();
    for (Map.Entry<String, List<Type>> entry : bySimpleName.entrySet()) {
      if (entry.getValue().stream().anyMatch(Type::isEnum)) {
        names.add(entry.getKey());
      }
    }
    return names;
  }

  /**
   * Every simple type name declared anywhere in the parsed model source. Reserved when naming
   * generated <em>fixed-list</em> enum companions (finding #4): a fixed-list companion reuses a model
   * enum only on an exact list-ID match, so a machine {@code FL_}-prefixed or case-shifted ID emits a
   * fresh companion that can collide with a model enum OR class of the PascalCased name.
   *
   * <p>Callers MUST exclude the IDs that {@link #boundFixedListNames(java.util.Collection)} reports,
   * because reserving a name the rebinder then binds-rather-than-emits renames the reference to a
   * companion that is never generated — see that method for the failure this prevents.
   *
   * @return the set of simple names of all parsed model types
   */
  Set<String> allSimpleNames() {
    return new LinkedHashSet<>(bySimpleName.keySet());
  }

  /**
   * The subset of {@code fixedListIds} that {@link RetrofitModelRebinder} will <em>bind</em> to an
   * existing model type instead of emitting a companion enum for — i.e. those naming a top-level model
   * type outright. These names must NOT be reserved during companion naming.
   *
   * <p>Reserving them desyncs the two halves of the decision: the namer bumps the reference to
   * {@code <Id>2} (the name is "taken"), then the rebinder drops the list because the model already
   * declares that type, so nothing is ever emitted under the suffixed name and every reference to it
   * fails to compile. This is exactly the prl breakage — 34 patch-imported {@code …2} enums
   * ({@code AllocatedJudgeTypeEnum2}, {@code YesNoDontKnow2}, {@code UrgencyTimeFrameType2}, …) that
   * no file declared, plus 4 in fpl and 1 in Civil. Excluding them leaves the reference on the model's
   * own type, which is the type the definition list means: the ID matches the model enum name exactly,
   * and the wire ID round-trips because the SDK reads the list ID from the enum it reflects.
   *
   * <p>Uses the same {@link #hasTopLevelType} predicate the rebinder's drop test uses, so the two can
   * never drift apart again.
   *
   * @param fixedListIds the definition's FixedList IDs
   * @return those IDs that bind to an existing top-level model type (never reserve these)
   */
  Set<String> boundFixedListNames(java.util.Collection<String> fixedListIds) {
    Set<String> bound = new LinkedHashSet<>();
    for (String id : fixedListIds) {
      if (id != null && hasTopLevelType(id)) {
        bound.add(id);
      }
    }
    return bound;
  }

  /**
   * The subset of {@code complexTypeIds} that {@link RetrofitComplexTypeEmitter} will <em>bind</em> to
   * an existing model class instead of emitting a companion for. The PascalCase name these would be
   * allocated must NOT be reserved, for the same reason as
   * {@link #boundFixedListNames(java.util.Collection)}.
   *
   * <p>prl is the case in point: it declares BOTH a {@code class OrderAppliedFor} and an
   * {@code enum OrderAppliedFor}, and the definition has a complex type of that ID. The complex type
   * binds to the class (so no companion is emitted), but the enum of the same name was reserved — so
   * the reference was renamed {@code OrderAppliedFor2} and pointed at a file that never exists.
   *
   * <p>Uses the same {@link #complexTypeClass} lookup the emitter's filter uses, including its
   * case-insensitive fallback, so the reserve decision and the emit decision cannot diverge.
   *
   * @param complexTypeIds the definition's ComplexTypes IDs
   * @param packageHint the model package, preferred on an ambiguous simple name
   * @return the PascalCase names of complex types that bind to an existing class (never reserve these)
   */
  Set<String> boundComplexTypeNames(java.util.Collection<String> complexTypeIds, String packageHint) {
    Set<String> bound = new LinkedHashSet<>();
    for (String id : complexTypeIds) {
      if (id == null) {
        continue;
      }
      // Reserve-side names are the PascalCased class name the namer would allocate; the bind test is
      // the emitter's own, on the raw definition ID.
      complexTypeClass(id, packageHint)
          .ifPresent(type -> bound.add(type.simpleName));
    }
    return bound;
  }

  /**
   * A {@code derivedName → boundClassFqn} map for every definition complex type that BINDS to an
   * existing model class whose real simple name differs from the name the linker derives for it.
   *
   * <p>{@link uk.gov.hmcts.ccd.sdk.converter.link.TypeClassNamer#complexTypeName} PascalCases a
   * definition ID by upper-casing the leading character of each alphanumeric run, so ET's
   * {@code et3CaseDetailsLinksStatuses} becomes {@code Et3CaseDetailsLinksStatuses} — while the class
   * it binds to (via {@link #complexTypeClass}'s case-insensitive fallback) is the acronym-cased
   * {@code ET3CaseDetailsLinksStatuses}. Because the type binds, no companion is emitted under the
   * derived name, so every reference to it (a companion complex type's member, a synthesised field)
   * resolved to a {@code modelPackage.Et3CaseDetailsLinksStatuses} that exists nowhere:
   * {@code cannot find symbol}.
   *
   * <p>{@link #caseInsensitiveClassAliases()} does not cover this: it only aliases a class's
   * DECAPITALISED simple name, which handles a camelCase ID whose class differs from it in the leading
   * character alone ({@code panel → Panel}). Any other case divergence — an embedded acronym — needs
   * the definition ID itself as the starting point, which is what this does.
   *
   * @param complexTypeIds the definition's ComplexTypes IDs
   * @param packageHint the model package, preferred on an ambiguous simple name
   * @return derived reference name → bound class FQN, for the ids where the two names differ
   */
  Map<String, String> complexTypeIdClassAliases(java.util.Collection<String> complexTypeIds,
      String packageHint) {
    Map<String, String> aliases = new LinkedHashMap<>();
    for (String id : complexTypeIds) {
      if (id == null) {
        continue;
      }
      Optional<Type> bound = complexTypeClass(id, packageHint);
      if (bound.isEmpty()) {
        // No model class: a companion IS emitted under the derived name, so no alias is wanted.
        continue;
      }
      String derived = uk.gov.hmcts.ccd.sdk.converter.link.TypeClassNamer.complexTypeName(id);
      if (derived.isEmpty() || derived.equals(bound.get().simpleName)) {
        continue;
      }
      // Never shadow a type the model really declares under the derived name — that reference is
      // already correct and rebinding it would point it somewhere else.
      if (bySimpleName.containsKey(derived)) {
        continue;
      }
      aliases.putIfAbsent(derived, bound.get().fqn);
    }
    return aliases;
  }

  /**
   * The type with a fully-qualified name, if parsed.
   *
   * @param fqn the fully-qualified class name
   * @return the parsed type, or empty
   */
  Optional<Type> byFqn(String fqn) {
    return Optional.ofNullable(byFqn.get(fqn));
  }

  /**
   * Whether the parsed source declares an enum with the given simple name (used to decide, in
   * retrofit mode, that a definition FixedList reuses an existing model enum rather than getting a
   * freshly generated one).
   *
   * @param simpleName the enum's simple name
   * @return true when a same-named enum exists in the parsed model source
   */
  boolean hasEnum(String simpleName) {
    List<Type> candidates = bySimpleName.get(simpleName);
    return candidates != null && candidates.stream().anyMatch(Type::isEnum);
  }

  /**
   * Whether the parsed model declares a top-level type (class, interface or enum) with the given
   * simple name anywhere. Used to detect that generating a fresh FixedList enum would collide with an
   * EXISTING top-level type — e.g. fpl's {@code HearingVenue} is a {@code @Data} address class, not an
   * enum, so a generated {@code enum HearingVenue} in the model package is a duplicate-type compile
   * error (finding F1).
   *
   * @param simpleName the type's simple name
   * @return true when a matching top-level type exists
   */
  boolean hasTopLevelType(String simpleName) {
    List<Type> candidates = bySimpleName.get(simpleName);
    return candidates != null && candidates.stream().anyMatch(Type::isTopLevel);
  }

  /**
   * The single type with a simple name, preferring one whose package starts with a hint (the
   * model package), else the sole candidate, else empty when ambiguous or unknown.
   *
   * @param simpleName the simple class name
   * @param packageHint a package prefix to prefer on ambiguity, or null
   * @return the resolved type, or empty
   */
  Optional<Type> bySimpleName(String simpleName, String packageHint) {
    List<Type> candidates = bySimpleName.get(simpleName);
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    if (candidates.size() == 1) {
      return Optional.of(candidates.get(0));
    }
    if (packageHint != null) {
      for (Type candidate : candidates) {
        if (candidate.packageName.startsWith(packageHint)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.of(candidates.get(0));
  }

  /**
   * The fully-qualified name of a top-level type with this simple name, preferring one in the model
   * package. Used by the patch emitter to import a synthesised field's declared type when it names
   * an existing model class in a different sub-package (e.g. a synthesised field on
   * {@code model.bundle.Bundle} typed {@code RemoteHearing}, which lives in {@code model.dq}). A
   * qualified/primitive type never reaches here; only bare simple names.
   *
   * @param simpleName the type's simple name
   * @param packageHint a package prefix to prefer on ambiguity, or null
   * @return the resolved FQN, or empty when no top-level type matches
   */
  Optional<String> fqnForSimpleName(String simpleName, String packageHint) {
    Optional<String> exact = fqnForTopLevel(bySimpleName.get(simpleName), packageHint);
    if (exact.isPresent()) {
      return exact;
    }
    // A synthesised field / companion member typed by a camelCase definition complex-type ID
    // ({@code panel}, {@code name}, {@code contact}) whose model class is PascalCase ({@code Panel},
    // {@code Name}, {@code Contact}): the camelCase companion is no longer generated once the complex
    // type resolves to the existing class (finding A2), so the reference must bind to that class's
    // real simple name case-insensitively (otherwise it is a cannot-find-symbol error). Match only a
    // single top-level class case-insensitively so a wrong same-name-different-case type is never used.
    List<Type> caseInsensitive = new ArrayList<>();
    for (Map.Entry<String, List<Type>> entry : bySimpleName.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(simpleName)) {
        caseInsensitive.addAll(entry.getValue());
      }
    }
    return fqnForTopLevel(caseInsensitive, packageHint);
  }

  private Optional<String> fqnForTopLevel(List<Type> candidates, String packageHint) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    List<Type> topLevel = candidates.stream().filter(Type::isTopLevel).toList();
    if (topLevel.isEmpty()) {
      return Optional.empty();
    }
    if (packageHint != null) {
      for (Type candidate : topLevel) {
        if (candidate.packageName.startsWith(packageHint)) {
          return Optional.of(candidate.fqn);
        }
      }
    }
    return Optional.of(topLevel.get(0).fqn);
  }

  /**
   * A {@code camelCaseId → fully-qualified name} map aliasing every top-level model CLASS whose
   * PascalCase simple name differs only in leading case from a definition ComplexTypes ID (finding
   * A2's companion-reference fallout): {@code panel → …domain.Panel}, {@code name → …domain.Name},
   * {@code contact → …domain.Contact}. A synthesised field or companion member typed by the camelCase
   * ID must bind to the existing class, but the camelCase companion is no longer generated once the
   * complex type resolves to that class — so {@code JavaTypeParser} needs the alias to emit the real
   * class reference instead of a dangling {@code modelPackage.panel}.
   *
   * <p>Only classes whose leading character is upper-case get an alias (their decapitalised form),
   * and only when that decapitalised name is not itself a declared type (never shadow a real type) and
   * is unambiguous (one class), so a wrong binding is never introduced.
   *
   * @return camelCase alias → existing class FQN
   */
  Map<String, String> caseInsensitiveClassAliases() {
    Map<String, String> aliases = new LinkedHashMap<>();
    Set<String> ambiguous = new java.util.HashSet<>();
    for (Map.Entry<String, List<Type>> entry : bySimpleName.entrySet()) {
      String simple = entry.getKey();
      if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) {
        continue;
      }
      String alias = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
      if (alias.equals(simple) || bySimpleName.containsKey(alias)) {
        // The decapitalised form names a real declared type — never shadow it.
        continue;
      }
      List<Type> classes = entry.getValue().stream()
          .filter(Type::isTopLevel).filter(Type::isClass).toList();
      if (classes.size() != 1) {
        // Absent or ambiguous (same simple name in >1 package): do not guess.
        if (classes.size() > 1) {
          ambiguous.add(alias);
          aliases.remove(alias);
        }
        continue;
      }
      if (!ambiguous.contains(alias)) {
        aliases.putIfAbsent(alias, classes.get(0).fqn);
      }
    }
    return aliases;
  }

  /**
   * A {@code simpleName → fully-qualified name} map for every top-level model type declared OUTSIDE
   * a package (its own sub-package excluded), skipping simple names that are ambiguous (declared in
   * more than one package) so a wrong guess is never emitted. The retrofit companion complex-type
   * emitter uses this so a generated class in {@code modelPackage} can import a member type that
   * really lives in a sibling sub-package (Civil's {@code JudgmentAddress} in
   * {@code model.judgmentonline}, {@code PaymentStatus} in {@code enums}). Types in
   * {@code packageToExclude} are left out — a member there needs no import from a same-package class.
   *
   * @param packageToExclude the companion emit package (types already there need no override)
   * @return unambiguous simple name → FQN for out-of-package top-level types
   */
  Map<String, String> topLevelFqnsOutside(String packageToExclude) {
    return topLevelFqnsOutside(packageToExclude, Map.of());
  }

  /**
   * As {@link #topLevelFqnsOutside(String)}, but consults {@code packageHints} (simple name → chosen
   * package) to resolve an otherwise-ambiguous simple name to one candidate before dropping it
   * (finding D1). A hint naming a package that no candidate declares is ignored here — the CLI
   * validates hints against the parsed model up front and errors clearly on an unknown one.
   *
   * @param packageToExclude the companion emit package (types already there need no override)
   * @param packageHints operator-supplied simple name → fully-qualified package disambiguation
   * @return unambiguous simple name → FQN for out-of-package top-level types
   */
  Map<String, String> topLevelFqnsOutside(String packageToExclude, Map<String, String> packageHints) {
    Map<String, String> hints = packageHints == null ? Map.of() : packageHints;
    Map<String, String> result = new LinkedHashMap<>();
    Set<String> ambiguous = new java.util.HashSet<>();
    for (Map.Entry<String, List<Type>> entry : bySimpleName.entrySet()) {
      String simple = entry.getKey();
      List<Type> topLevel = entry.getValue().stream()
          .filter(Type::isTopLevel)
          .filter(t -> !t.packageName.equals(packageToExclude))
          .toList();
      if (topLevel.isEmpty()) {
        continue;
      }
      // Distinct packages among out-of-package declarations: >1 means ambiguous. An operator hint
      // pinning the simple name to one of those packages resolves it; otherwise skip it (never guess).
      long distinctPackages = topLevel.stream().map(t -> t.packageName).distinct().count();
      if (distinctPackages > 1 || ambiguous.contains(simple)) {
        String hintedPackage = hints.get(simple);
        Type hinted = hintedPackage == null ? null : topLevel.stream()
            .filter(t -> t.packageName.equals(hintedPackage))
            .findFirst().orElse(null);
        if (hinted != null) {
          result.put(simple, hinted.fqn);
        } else {
          ambiguous.add(simple);
          result.remove(simple);
        }
        continue;
      }
      // If the simple name is ALSO declared in the excluded package, the companion's own version
      // wins (a same-name type is generated there); don't override it.
      boolean alsoInExcluded = entry.getValue().stream()
          .anyMatch(t -> t.isTopLevel() && t.packageName.equals(packageToExclude));
      if (!alsoInExcluded) {
        result.put(simple, topLevel.get(0).fqn);
      }
    }
    return result;
  }

  /**
   * Whether the parsed model declares a top-level type with the given simple name in the given
   * package — used by the CLI to validate a {@code --type-package-hint} before the run (finding D1),
   * so an unknown hint errors clearly instead of being silently ignored.
   *
   * @param simpleName the type's simple name
   * @param packageName the fully-qualified package the hint names
   * @return true when a matching top-level declaration exists
   */
  boolean hasTopLevelTypeInPackage(String simpleName, String packageName) {
    List<Type> candidates = bySimpleName.get(simpleName);
    return candidates != null && candidates.stream()
        .anyMatch(t -> t.isTopLevel() && t.packageName.equals(packageName));
  }

  /**
   * Resolves a definition complex type's Java class by simple name for annotation-patching. Unlike
   * {@link #bySimpleName}, this only ever returns a <em>top-level class</em> and prefers one in the
   * model package: a CCD complex type maps to a top-level {@code @Data}/POJO class, never to a
   * nested interface or an unrelated class sharing the name. This is the fix for Civil, where the
   * complex type {@code Hearing} collided with the {@code Hearing} interface nested inside the
   * sealed {@code CaseDataPredicate}; resolving to that interface synthesised uninitialised fields
   * into an interface body (a compile error). When no top-level class matches, returns empty and
   * the caller emits the type as a fresh companion class instead of patching.
   *
   * @param simpleName the complex type's simple name (its ComplexTypes sheet ID)
   * @param packageHint the model package to prefer on ambiguity, or null
   * @return the resolved top-level class, or empty when none exists
   */
  Optional<Type> complexTypeClass(String simpleName, String packageHint) {
    Optional<Type> exact = topLevelClassBySimpleName(bySimpleName.get(simpleName), packageHint);
    if (exact.isPresent()) {
      return exact;
    }
    // A definition ComplexTypes ID is frequently camelCase ({@code reasonableAdjustmentsLetters},
    // {@code correspondence}) while the team's Java class is PascalCase
    // ({@code ReasonableAdjustmentsLetters}, {@code Correspondence}) — the SDK's ComplexTypeEmitter
    // maps the two by first-letter capitalisation. An exact case-sensitive lookup misses that class,
    // so the complex type falls through to a spuriously-generated companion and its members lose
    // their {@code @CCD}/{@code typeParameterOverride} (finding A2, SSCS's ReasonableAdjustmentsLetters
    // dropped silently). Fall back to a case-insensitive match, applying the same top-level-class +
    // package-hint preference as the exact path.
    List<Type> caseInsensitive = new ArrayList<>();
    for (Map.Entry<String, List<Type>> entry : bySimpleName.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(simpleName)) {
        caseInsensitive.addAll(entry.getValue());
      }
    }
    return topLevelClassBySimpleName(caseInsensitive, packageHint);
  }

  /**
   * The single top-level class among {@code candidates}, preferring one in {@code packageHint}. Used
   * by {@link #complexTypeClass} for both the exact and the case-insensitive (camelCase-ID →
   * PascalCase-class) lookup so both apply the same top-level-class + package-hint rules.
   */
  private Optional<Type> topLevelClassBySimpleName(List<Type> candidates, String packageHint) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    List<Type> classes = candidates.stream()
        .filter(Type::isTopLevel)
        .filter(Type::isClass)
        .toList();
    if (classes.isEmpty()) {
      return Optional.empty();
    }
    if (packageHint != null) {
      for (Type candidate : classes) {
        if (candidate.packageName.startsWith(packageHint)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.of(classes.get(0));
  }

  /**
   * Whether any parsed model class extends {@code target} and makes an explicit positional
   * {@code super(...)} call with arguments. Appending a field to a Lombok {@code @AllArgsConstructor}
   * (or builder-generated all-args) superclass grows its constructor by one parameter, so such a
   * subclass's hand-written {@code super(a, b, …)} no longer matches the widened constructor and the
   * previous arity is gone — {@code no suitable constructor found} (Civil's
   * {@code FixedRecoverableCostsSection} calling {@code super(5 args)} on {@code FixedRecoverableCosts}).
   * Synthesising into such a class is therefore unsafe (finding B4, the B3 family for subclass super
   * calls); the caller routes those members to the gap report for manual placement instead.
   *
   * <p>A no-arg {@code super()} (or an implicit one — no call at all) is unaffected: Lombok's
   * {@code @NoArgsConstructor} keeps a zero-arg constructor available, so it is not treated as a break.
   *
   * @param target the class synthesis would append fields to
   * @return true when a subclass makes a positional {@code super(...)} call that field growth breaks
   */
  boolean hasSubtypeWithExplicitSuperCall(Type target) {
    String targetSimple = target.simpleName;
    for (List<Type> types : bySimpleName.values()) {
      for (Type candidate : types) {
        if (!candidate.decl.isClassOrInterfaceDeclaration()) {
          continue;
        }
        var extended = candidate.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
        if (extended.isEmpty()) {
          continue;
        }
        // Match the extends target by simple name (the parsed model rarely qualifies it), then
        // confirm it resolves to the same declaration to avoid a same-name false positive.
        if (!extended.get(0).getNameAsString().equals(targetSimple)) {
          continue;
        }
        Optional<Type> resolved = resolve(candidate.unit, extended.get(0));
        if (resolved.isEmpty() || !resolved.get().fqn.equals(target.fqn)) {
          continue;
        }
        boolean hasPositionalSuper = candidate.decl
            .findAll(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class)
            .stream()
            .anyMatch(s -> !s.isThis() && !s.getArguments().isEmpty());
        if (hasPositionalSuper) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether any parsed model source makes a positional {@code new <Class>(...)} call (with at least
   * one argument) for {@code target}'s simple name. Used to decide whether it is safe to drop a
   * {@code @AllArgsConstructor} the patch would otherwise leave oversized (the constructor-limit
   * fix): a positional {@code new} call relies on the all-args constructor, so removing it would break
   * that call. A no-arg {@code new <Class>()} is unaffected (a {@code @NoArgsConstructor}/builder
   * keeps that path) and does not block the drop.
   *
   * <p>Matched by simple name (object-creation expressions in the parsed source rarely qualify the
   * type), which is conservative — a same-named class in another package with a positional
   * {@code new} would also block the drop, erring toward the safe overflow fallback rather than a
   * broken constructor.
   *
   * @param target the class whose all-args constructor might be dropped
   * @return true when a positional {@code new <target>(...)} appears anywhere in the parsed source
   */
  boolean hasPositionalConstructorCall(Type target) {
    String targetSimple = target.simpleName;
    Set<CompilationUnit> scanned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Type candidate : byFqn.values()) {
      if (!scanned.add(candidate.unit)) {
        continue;
      }
      boolean found = candidate.unit
          .findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)
          .stream()
          .anyMatch(expr -> expr.getType().getNameAsString().equals(targetSimple)
              && !expr.getArguments().isEmpty());
      if (found) {
        return true;
      }
    }
    return false;
  }

  /**
   * Every method NAME called anywhere in the parsed model source, computed once on first use.
   *
   * <p>Used by the retrofit retype planner: re-declaring a field as a generated companion class
   * changes the type its accessors return and take, so any caller that reads
   * {@code getDwpAT38Document()} into a {@code DwpResponseDocument} (or passes one to the setter) stops
   * compiling. Matched by NAME alone, with no receiver-type resolution — the index has no symbol
   * solver — which is deliberately conservative: an unrelated class's identically-named accessor also
   * blocks the retype, erring toward leaving the team's declaration alone.
   *
   * @return the set of called method names
   */
  Set<String> calledMethodNames() {
    if (calledMethodNames == null) {
      Set<String> names = new LinkedHashSet<>();
      Set<CompilationUnit> scanned =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (Type candidate : byFqn.values()) {
        if (!scanned.add(candidate.unit)) {
          continue;
        }
        candidate.unit.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
            .forEach(call -> names.add(call.getNameAsString()));
      }
      calledMethodNames = names;
    }
    return calledMethodNames;
  }

  private Set<String> calledMethodNames;

  /**
   * Whether {@code owner}'s own source refers to the field {@code memberName} as a bare identifier
   * anywhere OUTSIDE its own declaration — the third way a retype breaks compilation, alongside the
   * accessor call and the constructor binding {@link #calledMethodNames} and the constructor checks
   * cover.
   *
   * <p>A hand-written method inside the declaring class reaches its own field directly, with no
   * accessor to intercept: fpl's {@code CaseData.getOrders()} returns {@code ordersSolicitor} as an
   * {@code Orders}, and {@code HearingDocuments} passes {@code caseSummaryListLA} to
   * {@code defaultIfNull(…, new ArrayList<>())} typed on the field. Retyping the declaration alone
   * leaves both uncompilable ("OrdersSolicitor cannot be converted to Orders").
   *
   * <p>Scoped to the declaring type's own compilation unit because that is where an unqualified field
   * reference can resolve; matched on {@link com.github.javaparser.ast.expr.NameExpr} and on
   * {@code this.<field>}. A reference sitting inside a {@code VariableDeclarator} of the same name is
   * skipped — that is the declaration (or its initialiser, which the retype rewrites in step with the
   * type), not a use. The AST is never mutated here: it is the same tree the emitter renders the
   * patched declarations from.
   *
   * @param owner the type declaring the field
   * @param memberName the field name a retype would re-declare
   * @return true when the declaring source reads or writes the field directly
   */
  boolean referencesFieldDirectly(Type owner, String memberName) {
    boolean bare = owner.unit.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
        .filter(name -> name.getNameAsString().equals(memberName))
        .anyMatch(name -> !inOwnDeclarator(name, memberName));
    boolean qualified = owner.unit.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class)
        .stream()
        .anyMatch(access -> access.getNameAsString().equals(memberName)
            && access.getScope().isThisExpr());
    return bare || qualified;
  }

  /**
   * The other class in {@code owner}'s own {@code extends} hierarchy that ALSO declares a field called
   * {@code memberName}, or empty when the name is declared only once in that hierarchy — the fourth way
   * a retype breaks compilation.
   *
   * <p>Lombok generates a getter and setter per declaration, so a name declared on both a class and an
   * ancestor/descendant of it yields two accessor pairs of the SAME signature, one overriding the other.
   * That only compiles while the two declarations share a type. Retyping one and not the other makes the
   * subclass getter's return type incompatible with the one it overrides, and the two setters clash on
   * erasure: ET declares {@code referralCollection} on both {@code CaseData} and {@code BaseCaseData}
   * ("getReferralCollection() in CaseData cannot override getReferralCollection() in BaseCaseData" plus
   * "setReferralCollection(List&lt;ReferralDetails&gt;) … have the same erasure, yet neither overrides
   * the other"), and {@code documentCollection} on both {@code BaseCaseData} and its subclass
   * {@code MultipleData} — so BOTH directions have to be checked, not just the ancestors.
   *
   * <p>Ancestors are walked through {@code extends} from {@code owner}; descendants are found by
   * scanning for parsed classes that declare the same field name (a cheap string check) and only then
   * resolving their {@code extends} chain to see whether it reaches {@code owner} — so the resolution
   * cost is paid for a handful of same-named candidates rather than every parsed class.
   *
   * @param owner the type declaring the field a retype would re-declare
   * @param memberName the field name
   * @return the other declaring class in the same hierarchy, or empty when there is none
   */
  Optional<Type> shadowedFieldDeclaration(Type owner, String memberName) {
    Optional<Type> ancestor = ancestorDeclaring(owner, memberName);
    if (ancestor.isPresent()) {
      return ancestor;
    }
    Set<CompilationUnit> scanned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Type candidate : byFqn.values()) {
      if (candidate.fqn.equals(owner.fqn) || !scanned.add(candidate.unit)) {
        continue;
      }
      if (!declaresField(candidate, memberName)) {
        continue;
      }
      if (ancestorDeclaring(candidate, memberName).map(t -> t.fqn.equals(owner.fqn)).orElse(false)
          || reaches(candidate, owner)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * The nearest strict ancestor of {@code type} declaring {@code memberName}, walking {@code extends}.
   */
  private Optional<Type> ancestorDeclaring(Type type, String memberName) {
    Type current = superclassOf(type);
    int guard = 0;
    Set<String> visited = new java.util.HashSet<>();
    while (current != null && guard++ < 20 && visited.add(current.fqn)) {
      if (declaresField(current, memberName)) {
        return Optional.of(current);
      }
      current = superclassOf(current);
    }
    return Optional.empty();
  }

  /**
   * Whether {@code type}'s {@code extends} chain reaches {@code target}.
   */
  private boolean reaches(Type type, Type target) {
    Type current = superclassOf(type);
    int guard = 0;
    Set<String> visited = new java.util.HashSet<>();
    while (current != null && guard++ < 20 && visited.add(current.fqn)) {
      if (current.fqn.equals(target.fqn)) {
        return true;
      }
      current = superclassOf(current);
    }
    return false;
  }

  /**
   * {@code type}'s resolved superclass, or null when it has none inside the parsed source.
   */
  private Type superclassOf(Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return null;
    }
    var extended = type.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
    return extended.isEmpty() ? null : resolve(type.unit, extended.get(0)).orElse(null);
  }

  /**
   * Whether {@code node} sits inside the declaration of the field {@code memberName} itself.
   */
  private static boolean inOwnDeclarator(
      com.github.javaparser.ast.Node node, String memberName) {
    return node.findAncestor(com.github.javaparser.ast.body.VariableDeclarator.class)
        .map(declarator -> declarator.getNameAsString().equals(memberName))
        .orElse(false);
  }

  /**
   * The getter suppressions {@link #hasResolvableGetter} has resolved a placement through, which the
   * patch must delete. Off by default (an inert plan nothing reads) so the matcher's report-only pass,
   * generate mode and unit tests keep the historical refuse-and-fall-back answer; the retrofit
   * conversion installs the real plan via {@link #repairSuppressedGetters}.
   */
  private RetrofitUnsuppressedGetters unsuppressedGetters = RetrofitUnsuppressedGetters.empty();
  private boolean repairSuppressedGetters;
  /**
   * Source lines of the files a getter repair inspects, cached (see {@link #sourceLines}).
   */
  private final Map<Path, List<String>> sourceLinesByFile = new LinkedHashMap<>();
  /**
   * Files re-parsed WITH source positions for a getter repair, cached (see {@link #positionedUnit}).
   */
  private final Map<Path, CompilationUnit> positionedUnits = new LinkedHashMap<>();

  /**
   * Installs the plan {@link #hasResolvableGetter} records relied-upon getter un-suppressions into,
   * enabling the repair. Called once per retrofit run by {@link RetrofitConverter}, before any
   * placement runs, so every call site (the member walk, the page placement and the synthesis host
   * choice) reads and records through the SAME plan the patch then realises.
   *
   * @param plan the plan to record into
   */
  void repairSuppressedGetters(RetrofitUnsuppressedGetters plan) {
    this.unsuppressedGetters = plan;
    this.repairSuppressedGetters = true;
  }

  /**
   * The getter un-suppressions the placements have relied on so far, for the patch emitter to realise.
   *
   * @return the plan, empty when the repair is off or nothing needed it
   */
  RetrofitUnsuppressedGetters unsuppressedGetters() {
    return unsuppressedGetters;
  }

  /**
   * Whether the model exposes a public getter for {@code fieldName} on {@code owner} (or a
   * superclass) that the SDK's {@code PropertyUtils} would map back to that exact field — i.e. a
   * {@code get<Field>()}/{@code is<Field>()} the config can reference as {@code Owner::get<Field>}.
   *
   * <p>A retrofit config places an {@code @JsonUnwrapped} parent via {@code .complex(Owner::getX)};
   * that method reference only compiles if the getter exists. Lombok's {@code @Data}/{@code @Getter}
   * generates one <em>unless</em> the field is annotated {@code @Getter(AccessLevel.NONE)} — SSCS's
   * {@code finalDecisionCaseData}/{@code pipSscsCaseData}/{@code sscsDeprecatedFields} suppress it and
   * either hand-write a <em>differently-named</em> accessor ({@code getSscsFinalDecisionCaseData},
   * which {@code PropertyUtils} maps to the non-existent field {@code sscsFinalDecisionCaseData}, not
   * back to {@code finalDecisionCaseData}) or none at all. Emitting {@code SscsCaseData::getFinalDecisionCaseData}
   * for such a field is an "invalid method reference" compile error (finding Bug4). This lets the
   * rebinder detect that case and route the affected placements away from the missing getter.
   *
   * <p>A suppressed getter on a {@code @JsonUnwrapped} field is instead <em>repaired</em>: the retrofit
   * patch deletes the {@code @Getter(AccessLevel.NONE)} so Lombok generates the getter the placement
   * needs, and this method answers true. The reliance is recorded in
   * {@link RetrofitUnsuppressedGetters} at the moment it flips the answer, so the patch removes exactly
   * the suppressions the placements relied on and no others — see that class for why the removal is
   * wire-format-neutral, and why it is scoped to unwrapped fields. Without a plan wired in (generate
   * mode, the matcher's report-only pass, most unit tests) the suppression stands and the answer is
   * false, i.e. the historical refuse-and-fall-back behaviour.
   *
   * @param owner the class the field is declared on (walked up its {@code extends} chain)
   * @param fieldName the Java field name whose getter is needed
   * @return true when a name-matching public getter exists (Lombok-generated or hand-written), or when
   *     the patch will make Lombok generate one by un-suppressing it
   */
  boolean hasResolvableGetter(Type owner, String fieldName) {
    String capitalised = fieldName.isEmpty() ? fieldName
        : Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    String getter = "get" + capitalised;
    String isGetter = "is" + capitalised;
    Type current = owner;
    int guard = 0;
    java.util.Set<String> visited = new java.util.HashSet<>();
    while (current != null && guard++ < 20 && visited.add(current.fqn)) {
      // A hand-written accessor of the exact standard name resolves regardless of Lombok.
      boolean handWritten = current.decl.getMethods().stream()
          .anyMatch(m -> m.getParameters().isEmpty()
              && (m.getNameAsString().equals(getter) || m.getNameAsString().equals(isGetter)));
      if (handWritten) {
        return true;
      }
      // Lombok generates the getter from @Data/@Getter at type level, unless the field is annotated
      // @Getter(AccessLevel.NONE) (or the type carries no such Lombok annotation at all).
      if (declaresField(current, fieldName)) {
        boolean lombokGetters = hasTypeLevelGetterGeneration(current);
        boolean suppressed = fieldGetterSuppressed(current, fieldName);
        if (repairSuppressedGetters && lombokGetters && suppressed
            && canUnsuppress(current, fieldName)) {
          unsuppressedGetters.record(current, fieldName);
          return true;
        }
        return lombokGetters && !suppressed;
      }
      if (!current.decl.isClassOrInterfaceDeclaration()) {
        break;
      }
      var extended = current.decl.asClassOrInterfaceDeclaration().getExtendedTypes();
      current = extended.isEmpty() ? null : resolve(current.unit, extended.get(0)).orElse(null);
    }
    return false;
  }

  /**
   * Whether a suppressed getter can be repaired by deleting the {@code @Getter(AccessLevel.NONE)}
   * rather than refusing the placement. Two conditions:
   *
   * <ul>
   *   <li>the field carries {@code @JsonUnwrapped} — Jackson already treats it as a visible property
   *       off the FIELD, so adding the getter cannot change the wire format (see
   *       {@link RetrofitUnsuppressedGetters}). An un-annotated private field with no getter is
   *       invisible to Jackson today and un-suppressing it would start serialising a brand-new
   *       property, so it is left refused;</li>
   *   <li>the suppressing annotation sits ALONE on its own source line — which is what the patch
   *       deletes. This is deliberately the same predicate {@code RetrofitPatchEmitter.renderFile}
   *       applies (the trimmed source line equals the annotation's own text), read off the same file,
   *       so a placement can never resolve through a repair the patch then declines to make. Where the
   *       shapes could differ they differ safely: this index's parser pretty-prints the annotation
   *       while the emitter's lexically-preserving parser reproduces the source verbatim, so an oddly
   *       spaced {@code @Getter( AccessLevel.NONE )} fails HERE and the placement refuses as before.</li>
   * </ul>
   */
  private boolean canUnsuppress(Type type, String fieldName) {
    for (FieldDeclaration field : type.decl.getFields()) {
      if (field.getVariables().stream().noneMatch(v -> v.getNameAsString().equals(fieldName))) {
        continue;
      }
      boolean unwrapped = field.getAnnotations().stream().anyMatch(a -> {
        String name = a.getNameAsString();
        return name.equals("JsonUnwrapped") || name.endsWith(".JsonUnwrapped");
      });
      return unwrapped && suppressionIsSoloOnItsLine(type.file, fieldName);
    }
    return false;
  }

  /**
   * Whether the field's {@code @Getter(AccessLevel.NONE)} occupies a whole source line by itself — the
   * precondition the patch's line-deletion needs.
   *
   * <p>This index's own parser stores no token ranges (a deliberate heap trade-off: Civil parses 3500+
   * files), so the AST it holds has no source positions to check. The one file in question is therefore
   * re-parsed WITH positions, cached — only files that actually reach here are re-read, a handful per
   * run. The check is then the same one {@code RetrofitPatchEmitter.renderFile} applies (the trimmed
   * source line equals the annotation's own text) against the same file, so a placement cannot resolve
   * through a repair the emitter would decline to make.
   */
  private boolean suppressionIsSoloOnItsLine(Path file, String fieldName) {
    List<String> lines = sourceLines(file);
    CompilationUnit positioned = positionedUnit(file);
    if (positioned == null || lines.isEmpty()) {
      return false;
    }
    for (TypeDeclaration<?> decl : positioned.findAll(TypeDeclaration.class)) {
      for (FieldDeclaration field : decl.getFields()) {
        if (field.getVariables().stream().noneMatch(v -> v.getNameAsString().equals(fieldName))) {
          continue;
        }
        for (com.github.javaparser.ast.expr.AnnotationExpr a : field.getAnnotations()) {
          String name = a.getNameAsString();
          boolean isGetter = name.equals("Getter") || name.endsWith(".Getter");
          if (!isGetter || !a.toString().contains("NONE")) {
            continue;
          }
          int begin = a.getBegin().map(p -> p.line).orElse(-1);
          int end = a.getEnd().map(p -> p.line).orElse(-1);
          if (begin >= 1 && begin == end && begin <= lines.size()
              && lines.get(begin - 1).trim().equals(a.toString())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * The source lines of a parsed file, cached — read only for the handful of files a repair touches.
   */
  private List<String> sourceLines(Path file) {
    return sourceLinesByFile.computeIfAbsent(file, f -> {
      try {
        return Files.readAllLines(f);
      } catch (IOException e) {
        return List.of();
      }
    });
  }

  /**
   * One file re-parsed with source positions, cached. Null when it cannot be read or parsed (the caller
   * then refuses the repair, which is the safe direction).
   */
  private CompilationUnit positionedUnit(Path file) {
    return positionedUnits.computeIfAbsent(file, f -> {
      JavaParser positioning = new JavaParser(new ParserConfiguration()
          .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
      try {
        return positioning.parse(f).getResult().orElse(null);
      } catch (IOException e) {
        return null;
      }
    });
  }

  private static boolean declaresField(Type type, String fieldName) {
    return type.decl.getFields().stream()
        .flatMap(f -> f.getVariables().stream())
        .anyMatch(v -> v.getNameAsString().equals(fieldName));
  }

  private static boolean hasTypeLevelGetterGeneration(Type type) {
    return type.decl.getAnnotations().stream().anyMatch(a -> {
      String name = a.getNameAsString();
      return name.equals("Data") || name.endsWith(".Data")
          || name.equals("Getter") || name.endsWith(".Getter");
    });
  }

  /**
   * Whether the field declaration carries {@code @Getter(AccessLevel.NONE)}, suppressing Lombok.
   */
  private static boolean fieldGetterSuppressed(Type type, String fieldName) {
    return type.decl.getFields().stream()
        .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName)))
        .anyMatch(f -> f.getAnnotations().stream().anyMatch(a -> {
          String name = a.getNameAsString();
          boolean isGetter = name.equals("Getter") || name.endsWith(".Getter");
          return isGetter && a.toString().contains("NONE");
        }));
  }

  /**
   * Resolves a type reference appearing inside a compilation unit back to a parsed type, the way
   * {@code javac} would: a qualified name directly, then an explicit import, then a same-package
   * sibling, then the global simple-name index.
   *
   * @param context the compilation unit the reference appears in
   * @param ref the referenced type
   * @return the parsed type, or empty when it lives outside the parsed source (a JDK/library type)
   */
  Optional<Type> resolve(CompilationUnit context, ClassOrInterfaceType ref) {
    String name = ref.getNameWithScope();
    if (name.contains(".")) {
      Optional<Type> byName = byFqn(name);
      if (byName.isPresent()) {
        return byName;
      }
    }
    String simple = ref.getNameAsString();
    for (ImportDeclaration imp : context.getImports()) {
      if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + simple)) {
        Optional<Type> imported = byFqn(imp.getNameAsString());
        if (imported.isPresent()) {
          return imported;
        }
      }
    }
    String pkg = context.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    if (!pkg.isEmpty()) {
      Optional<Type> samePackage = byFqn(pkg + "." + simple);
      if (samePackage.isPresent()) {
        return samePackage;
      }
    }
    return bySimpleName(simple, pkg);
  }
}
