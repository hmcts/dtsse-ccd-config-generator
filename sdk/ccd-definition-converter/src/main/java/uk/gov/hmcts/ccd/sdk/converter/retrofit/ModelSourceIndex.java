package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
   * @param owner the class the field is declared on (walked up its {@code extends} chain)
   * @param fieldName the Java field name whose getter is needed
   * @return true when a name-matching public getter exists (Lombok-generated or hand-written)
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
