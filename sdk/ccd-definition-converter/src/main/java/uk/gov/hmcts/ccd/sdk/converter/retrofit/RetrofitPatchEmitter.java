package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeAuthGetter;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Emits the retrofit annotation patch (proposal §3 mechanism (b), §4): a {@code git apply}-able
 * unified diff that annotates the team's <em>existing</em> model sources so the SDK reads the
 * definition metadata straight off their fields. It edits with JavaParser's
 * {@code LexicalPreservingPrinter} (minimal churn — untouched lines are preserved byte-for-byte) and
 * renders the before/after of each touched file into a unified diff with java-diff-utils.
 *
 * <p>Per the taxonomy the linker + matcher produced, for each reachable model class it:
 * <ul>
 *   <li><b>Matched / type-conflict fields</b> — adds {@code @CCD(...)} carrying the definition
 *       metadata ({@link CcdAnnotationRenderer}, mirroring {@code FieldEmitHelper}); a type-conflict
 *       additionally carries {@code typeOverride}/{@code typeParameterOverride} (already on the
 *       {@link FieldModel} the linker computed).</li>
 *   <li><b>Unmatched Java fields</b> — adds {@code @CCD(ignore = true)} unless already
 *       {@code @JsonIgnore}/{@code @CCD(ignore=true)}, so the SDK does not reflect a field the
 *       definition has no row for into a spurious CaseField.</li>
 *   <li><b>Unmatched definition fields</b> (decision 4) — synthesises a new typed private field on
 *       {@code --model-class}, with {@code @CCD(...)} and a {@code @JsonProperty} when the id is not
 *       a legal bean name, grouped in one delimited block at the end of the class body.</li>
 *   <li><b>Complex-type members</b> — the same annotate/ignore/synthesise treatment on each model
 *       class the definition's ComplexTypes rows resolve to.</li>
 * </ul>
 *
 * <p><b>Idempotency.</b> Phase 2 targets unannotated models, so a field already carrying
 * {@code @CCD} is left untouched (a re-run produces no-op hunks for it). This is the documented
 * "skip fields already carrying @CCD" rule.
 */
public final class RetrofitPatchEmitter {

  private static final String SYNTH_BEGIN =
      "// ==== ccd-definition-converter: synthesised definition-only fields (retrofit) ====";
  private static final String SYNTH_END =
      "// ==== end synthesised definition-only fields ====";

  private final ModelSourceIndex index;
  private final Map<String, ResolvedProperty> properties;
  private final CaseTypeModel model;
  private final ModelSourceIndex.Type rootType;
  private final CcdAnnotationRenderer renderer;
  private final TypeReconciler reconciler;
  private final SynthesisPlacement placement;
  /** The team model package — companion complex types/enums are emitted here. */
  private final String modelPackage;
  /**
   * The source-root path relative to the model REPO root, prepended to every emitted diff path so all
   * lanes' patches are rooted at the repo root and {@code bin/retrofit-verify} applies them uniformly
   * (e.g. {@code service/src/main/java/}). Empty when the repo root is the source root.
   */
  private final String pathPrefix;
  /** Gaps recorded while planning (e.g. a synthesised field skipped on a name collision). */
  private final List<GapEntry> gaps = new ArrayList<>();

  // Parse the files we edit at a modern language level (real service models use sealed classes,
  // records, switch patterns — Civil's model/Result.java is a sealed interface), with lexical
  // preservation ON so LexicalPreservingPrinter reproduces untouched lines byte-for-byte. The
  // matcher's ModelSourceIndex parser drops tokens for heap reasons and cannot be reused for
  // printing; this one is scoped to just the handful of files a patch touches.
  private final JavaParser editParser = new JavaParser(new ParserConfiguration()
      .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
      .setLexicalPreservationEnabled(true));

  /**
   * Creates a patch emitter.
   *
   * @param index the parsed model source index (reused from the matcher)
   * @param resolution the property resolution (reused from the matcher)
   * @param model the linked, retrofit-rebound case type model (fields carry final @CCD metadata)
   * @param rootType the root model class (target for synthesised definition-only fields)
   * @param configPackage the package the generated access classes live in
   */
  RetrofitPatchEmitter(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      CaseTypeModel model, ModelSourceIndex.Type rootType, String configPackage) {
    this(index, resolution, model, rootType, configPackage, 0, "");
  }

  /**
   * Creates a patch emitter with an explicit constructor-limit override (finding B2) and a path
   * prefix rooting the emitted diff at the model repo root (patch-root consistency).
   *
   * @param constructorLimit the field-count threshold for CaseDataExtra overflow; {@code <= 0} uses
   *                          the default
   * @param pathPrefix the source-root path relative to the repo root (e.g. {@code service/src/main/java/}),
   *                   prepended to every emitted diff path; empty when repo root == source root
   */
  RetrofitPatchEmitter(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      CaseTypeModel model, ModelSourceIndex.Type rootType, String configPackage,
      int constructorLimit, String pathPrefix) {
    this.index = index;
    this.properties = resolution.properties;
    this.model = model;
    this.rootType = rootType;
    this.renderer = new CcdAnnotationRenderer(configPackage);
    this.reconciler = new TypeReconciler(index);
    this.placement = new SynthesisPlacement(index, constructorLimit);
    this.modelPackage = rootType != null ? rootType.packageName : null;
    this.pathPrefix = normalisePrefix(pathPrefix);
  }

  /** Normalises a path prefix to empty or a single-trailing-slash form with no leading slash. */
  private static String normalisePrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      return "";
    }
    String p = prefix.replace('\\', '/').trim();
    while (p.startsWith("/")) {
      p = p.substring(1);
    }
    if (!p.isEmpty() && !p.endsWith("/")) {
      p = p + "/";
    }
    return p;
  }

  /**
   * Builds the patch.
   *
   * @return the emitted patch (empty diff when nothing needed annotating)
   */
  public RetrofitPatch emit() {
    // Plan the edits per source file. A file may host the root class, complex-type classes and/or
    // @JsonUnwrapped sub-objects, so accumulate all field edits keyed by the file they live in.
    Map<Path, FileEdits> byFile = new LinkedHashMap<>();

    // 1. Matched/conflict CaseData fields → @CCD; the root class also receives synthesised fields.
    Map<String, FieldModel> caseFieldsById = new LinkedHashMap<>();
    for (FieldModel field : model.getCaseFields()) {
      caseFieldsById.put(field.getId(), field);
    }
    for (FieldModel field : model.getCaseFields()) {
      ResolvedProperty property = properties.get(field.getId());
      if (property == null) {
        continue; // synthesised below
      }
      editsFor(byFile, property.ownerFile)
          .annotate(property.memberName, field, renameFor(property, field));
    }

    // 2. Unmatched Java fields → @CCD(ignore = true).
    Set<String> definitionIds = new LinkedHashSet<>(caseFieldsById.keySet());
    // Complex-type member IDs never mean a top-level CaseData match, but a model property whose CCD
    // id equals a data-bearing CaseField id is matched; anything else on the CaseData tree is
    // unmatched Java.
    for (ResolvedProperty property : properties.values()) {
      if (!definitionIds.contains(property.ccdId)) {
        editsFor(byFile, property.ownerFile).ignore(property.memberName);
      }
    }

    // 3. Complex-type members: annotate/ignore/synthesise on each resolved model complex class.
    planComplexTypeMembers(byFile);

    // 4. Synthesised definition-only fields onto the root model class.
    List<FieldModel> synthesised = new ArrayList<>();
    for (FieldModel field : model.getCaseFields()) {
      if (!properties.containsKey(field.getId())) {
        synthesised.add(field);
      }
    }
    RetrofitPatch.FilePatch extraClassFile = null;
    if (rootType != null && !synthesised.isEmpty()) {
      List<FieldModel> placeable = dropExistingFieldCollisions(rootType, synthesised);
      SynthesisPlacement.Plan plan = placement.plan(rootType, placeable);
      if (plan.overflow && plan.existingHost != null) {
        // B2 borderline: even the single added @JsonUnwrapped CaseDataExtra member would tip the root
        // over the constructor limit (SSCS: 254 + 1 > 254). Nest ALL synthesised fields into an
        // EXISTING prefix-less @JsonUnwrapped member's class instead, adding ZERO fields to the root.
        // Prefix-less unwrapping flattens the added members to the same CCD IDs, and the config
        // references them through that member's existing getter.
        SynthesisPlacement.ExistingHost host = plan.existingHost;
        List<FieldModel> hostPlaceable = dropExistingFieldCollisions(host.type, placeable);
        editsFor(byFile, host.type.file).synthesise(host.type.simpleName, hostPlaceable);
        gaps.add(GapEntry.builder()
            .sheet("CaseField")
            .rowKey(rootType.simpleName)
            .column("FieldType")
            .value("(constructor limit)")
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.MANUAL_PLACEMENT)
            .detail(rootType.simpleName + " is at the constructor-argument limit, so even a single "
                + "added @JsonUnwrapped CaseDataExtra member would not compile. The " + placeable.size()
                + " synthesised definition-only field(s) were nested into the existing prefix-less "
                + "@JsonUnwrapped member '" + host.memberName + "' (type " + host.type.simpleName
                + ", chosen as the first alphabetical prefix-less unwrapped member that is neither a "
                + "@JsonCreator/@Builder idiom nor missing a getter), so ZERO fields are added to "
                + rootType.simpleName + " and its constructor stays within the limit. The CCD field "
                + "IDs are unchanged (prefix-less unwrapping flattens verbatim).")
            .build());
      } else if (plan.overflow) {
        // Field synthesis would push the root class's all-args constructor past the JVM/Lombok limit
        // (finding B2): move ALL synthesised fields into a new CaseDataExtra class and add ONE
        // prefix-less @JsonUnwrapped member to the root, whose members flatten to the same CCD ids.
        extraClassFile = renderExtraClass(plan.extraClassName, placeable);
        editsFor(byFile, rootType.file).addUnwrappedMember(plan.extraClassName);
        if (plan.borderlineStillOverLimit) {
          gaps.add(GapEntry.builder()
              .sheet("CaseField")
              .rowKey(rootType.simpleName)
              .column("FieldType")
              .value("(constructor limit)")
              .category(GapCategory.UNSUPPORTED_VALUE)
              .action(GapAction.MANUAL_PLACEMENT)
              .detail(rootType.simpleName + " is within one field of the constructor-argument limit; "
                  + "the synthesised fields were moved to " + plan.extraClassName + ", but even the "
                  + "single added @JsonUnwrapped member leaves the class at the limit, and no existing "
                  + "prefix-less @JsonUnwrapped member was a safe host. Move an "
                  + "existing field into " + plan.extraClassName + " by hand if the class still fails "
                  + "to compile.")
              .build());
        }
      } else {
        editsFor(byFile, rootType.file).synthesise(rootType.simpleName, placeable);
      }
    }

    // 5. Delegating no-arg getters on the root class for AuthorisationComplexType grants whose complex
    // field is reached only through a @JsonUnwrapped member (so the flat CCD id has no direct getter).
    // The config emits CaseData::get<FieldId>; without a real method of that name grantComplexType's
    // serialized-lambda resolver fails at generation. Each is @JsonIgnore (adds no Jackson property)
    // and delegates through the model's real parent/member getters (mirroring fpl's own
    // getOrderCollection()); the SDK reads CaseFields from FIELDS not getters, so it adds no CaseField.
    if (rootType != null && model.getComplexTypeAuthGetters() != null
        && !model.getComplexTypeAuthGetters().isEmpty()) {
      for (ComplexTypeAuthGetter getter : model.getComplexTypeAuthGetters().values()) {
        editsFor(byFile, rootType.file).addDelegatingGetter(getter);
      }
    }

    // Render each touched file.
    List<RetrofitPatch.FilePatch> filePatches = new ArrayList<>();
    StringBuilder diff = new StringBuilder();
    // Deterministic order: sort by relative path.
    Map<String, FileEdits> byRelative = new TreeMap<>();
    for (Map.Entry<Path, FileEdits> entry : byFile.entrySet()) {
      byRelative.put(relativePath(entry.getKey()), entry.getValue());
    }
    for (Map.Entry<String, FileEdits> entry : byRelative.entrySet()) {
      String relative = entry.getKey();
      FileEdits edits = entry.getValue();
      RetrofitPatch.FilePatch patch = renderFile(relative, edits);
      if (patch == null) {
        continue;
      }
      filePatches.add(patch);
      diff.append(unifiedDiffFor(relative, patch));
    }
    // The added CaseDataExtra class is a NEW file: emit it after the edits, sorted deterministically
    // by its position in the diff (a git "new file" hunk with an empty old side).
    if (extraClassFile != null) {
      filePatches.add(extraClassFile);
      diff.append(newFileDiff(extraClassFile));
    }
    return new RetrofitPatch(diff.toString(), filePatches);
  }

  /**
   * Renders the added {@code CaseDataExtra} class holding the synthesised definition-only fields, as
   * a new file in the model package. Its file path mirrors the model package layout under the source
   * root so {@code git apply} creates it in the right place; its content is the synthesised block
   * wrapped in a {@code @Data} class with the imports its field types need.
   */
  private RetrofitPatch.FilePatch renderExtraClass(String className, List<FieldModel> fields) {
    // A fresh file starts with no imports, so the binder has a clean slate.
    SynthResult synth = renderSynthFields(fields, "  ", new ImportBinder(new LinkedHashMap<>()));
    StringBuilder body = new StringBuilder();
    body.append("package ").append(modelPackage).append(";\n\n");
    List<String> imports = new ArrayList<>();
    imports.add("import lombok.Data;");
    if (synth.usesCcd) {
      imports.add("import uk.gov.hmcts.ccd.sdk.api.CCD;");
    }
    if (synth.usesFieldType) {
      imports.add("import uk.gov.hmcts.ccd.sdk.type.FieldType;");
    }
    if (synth.usesJsonProperty) {
      imports.add("import com.fasterxml.jackson.annotation.JsonProperty;");
    }
    for (String access : synth.accessClasses) {
      imports.add(renderer.accessImport(access));
    }
    imports.addAll(synth.typeImports);
    imports.forEach(i -> body.append(i).append('\n'));
    body.append('\n');
    body.append("/**\n")
        .append(" * Synthesised definition-only fields for the retrofit that would otherwise push the "
            + "root\n")
        .append(" * case-data class past the JVM/Lombok all-args-constructor limit. Added to the root "
            + "as a\n")
        .append(" * prefix-less {@code @JsonUnwrapped} member, so these members flatten to the same "
            + "CCD field\n")
        .append(" * IDs. Generated by ccd-definition-converter (retrofit).\n")
        .append(" *\n")
        .append(" * <p>").append(SynthesisPlacement.EXTRA_CLASS_MARKER).append('\n')
        .append(" */\n");
    body.append("@Data\n");
    body.append("public class ").append(className).append(" {\n\n");
    body.append(synth.text);
    body.append("}\n");
    String relative = pathPrefix + modelPackage.replace('.', '/') + "/" + className + ".java";
    return new RetrofitPatch.FilePatch(relative, "", body.toString());
  }

  /**
   * A git "new file" unified diff for an added file (empty old side): every line is an addition.
   */
  private String newFileDiff(RetrofitPatch.FilePatch patch) {
    final List<String> after = splitGitLines(patch.patchedContent());
    StringBuilder out = new StringBuilder();
    out.append("diff --git a/").append(patch.relativePath())
        .append(" b/").append(patch.relativePath()).append('\n');
    out.append("new file mode 100644\n");
    out.append("--- /dev/null\n");
    out.append("+++ b/").append(patch.relativePath()).append('\n');
    out.append("@@ -0,0 +1,").append(after.size()).append(" @@\n");
    for (String line : after) {
      out.append('+').append(line).append('\n');
    }
    if (!patch.patchedContent().endsWith("\n") && !patch.patchedContent().isEmpty()) {
      out.append(NO_NEWLINE_MARKER).append('\n');
    }
    return out.toString();
  }

  /**
   * The gaps recorded while planning the patch (populated by {@link #emit()}): synthesised
   * definition-only fields skipped because the target class already declares a member of that name
   * (finding B1). Empty when the patch introduced no collisions.
   *
   * @return the recorded gaps
   */
  public List<GapEntry> gaps() {
    return gaps;
  }

  private void planComplexTypeMembers(Map<Path, FileEdits> byFile) {
    // Prefer a complex-type class in the team's model package; the root class's package is the
    // anchor (e.g. uk.gov.hmcts.reform.civil.model). Falling back to null hint would let a
    // same-named type in an unrelated package win.
    String modelPackage = rootType != null ? rootType.packageName : null;
    for (ComplexTypeModel complexType : model.getComplexTypes()) {
      Optional<ModelSourceIndex.Type> type =
          index.complexTypeClass(complexType.getId(), modelPackage);
      if (type.isEmpty()) {
        // No top-level model CLASS for this definition complex type (absent, or only a nested/
        // interface type shares the name — e.g. Civil's Hearing interface nested in the sealed
        // CaseDataPredicate): it is emitted as a fresh generated class in the companion sources
        // (see the retrofit companion emitter), not patched here.
        continue;
      }
      ModelSourceIndex.Type complexClass = type.get();
      // Resolve the complex class's own members so we know which are matched vs unmatched-Java.
      PropertyResolver.Resolution memberResolution =
          new PropertyResolver(index).resolve(complexClass);
      Set<String> definedMembers = new LinkedHashSet<>();
      for (FieldModel member : complexType.getMembers()) {
        definedMembers.add(member.getId());
        ResolvedProperty property = memberResolution.properties.get(member.getId());
        if (property != null) {
          // Reconcile the member's declared type against the model member's real Java type, exactly
          // as the root CaseData fields are reconciled in RetrofitModelRebinder — so a nested
          // List<Wrapper> member (SSCS's ReasonableAdjustmentsLetters.List<Correspondence>) gets its
          // typeParameterOverride instead of a bare label-only @CCD.
          FieldModel reconciled = reconciler.reconcile(member, property);
          editsFor(byFile, property.ownerFile)
              .annotate(property.memberName, reconciled, renameFor(property, reconciled));
        }
      }
      // Unmatched Java members of the complex class → ignore.
      for (ResolvedProperty property : memberResolution.properties.values()) {
        if (!definedMembers.contains(property.ccdId)) {
          editsFor(byFile, property.ownerFile).ignore(property.memberName);
        }
      }
      // Definition members with no model field → synthesise onto the complex class.
      List<FieldModel> synthesised = new ArrayList<>();
      for (FieldModel member : complexType.getMembers()) {
        if (!memberResolution.properties.containsKey(member.getId())) {
          synthesised.add(member);
        }
      }
      if (!synthesised.isEmpty()) {
        String unsafeReason = synthesisUnsafeReason(complexClass);
        if (unsafeReason != null) {
          // Appending a field to this class would break its constructor contract (finding B3/B4):
          // either a hand-written single-arg @JsonCreator + @Builder idiom Lombok binds the builder
          // to (SSCS's Bundle/ScannedDocument), or a Lombok all-args constructor a subclass calls
          // positionally via super(...) (Civil's FixedRecoverableCosts, whose subclass
          // FixedRecoverableCostsSection calls super(5 args) — a synthesised 6th field widens the
          // constructor and breaks that call). Route these members to the gap report for manual
          // placement rather than synthesising into the class.
          for (FieldModel member : synthesised) {
            gaps.add(GapEntry.builder()
                .sheet("ComplexTypes")
                .rowKey(complexType.getId() + "/" + member.getId())
                .column("ListElementCode")
                .value(member.getId())
                .category(GapCategory.UNSUPPORTED_VALUE)
                .action(GapAction.MANUAL_PLACEMENT)
                .detail("Complex type '" + complexType.getId() + "' member '" + member.getId()
                    + "' would be synthesised onto " + complexClass.simpleName + ", " + unsafeReason)
                .build());
          }
        } else {
          editsFor(byFile, complexClass.file)
              .synthesise(complexClass.simpleName,
                  dropExistingFieldCollisions(complexClass, synthesised));
        }
      }
    }
  }

  /**
   * A human-readable reason why appending a synthesised field to {@code complexClass} would break its
   * compilation, or null when synthesis is safe. Two cases:
   * <ul>
   *   <li>a hand-written single-arg {@code @JsonCreator} + {@code @Builder} idiom (finding B3) —
   *       {@link #hasBuilderBoundJsonCreator};</li>
   *   <li>a Lombok all-args constructor a subclass calls positionally via {@code super(...)} (finding
   *       B4) — {@link ModelSourceIndex#hasSubtypeWithExplicitSuperCall}. Growing the all-args
   *       constructor by one parameter leaves the subclass's fixed-arity {@code super(...)} with no
   *       matching constructor.</li>
   * </ul>
   */
  private String synthesisUnsafeReason(ModelSourceIndex.Type complexClass) {
    if (hasBuilderBoundJsonCreator(complexClass)) {
      return "which uses a hand-written single-arg @JsonCreator + @Builder idiom; appending a field "
          + "would break the builder's constructor binding. Add the field and extend the "
          + "@JsonCreator constructor by hand.";
    }
    if (hasBuilderBoundExplicitConstructor(complexClass)) {
      // A @Data/@Builder class with a hand-written explicit constructor (even without @JsonCreator):
      // Lombok's @Builder binds to that constructor, so appending a field makes the generated builder
      // pass one more argument than the constructor declares — "constructor X cannot be applied to
      // given types" (SSCS's Appeal: @Data @Builder + an 11-arg @JsonProperty constructor). Route to
      // manual placement rather than synthesising a field the constructor cannot accept.
      return "which is a @Builder class with a hand-written explicit constructor the builder binds to; "
          + "appending a field would make the generated builder pass an argument the constructor does "
          + "not declare. Add the field and extend the constructor by hand.";
    }
    if (hasFinalFields(complexClass) && !complexClass.decl.getConstructors().isEmpty()) {
      // A @Value class (or one whose fields are otherwise final) makes the synthesised field final
      // too; a hand-written explicit constructor that does not assign it leaves it "might not have
      // been initialized" (Civil's Bundle: @Value + a @JsonCreator Bundle(value) ctor that only sets
      // value). Route to manual placement rather than synthesising an uninitialisable final field.
      return "which is a @Value/final-field class with a hand-written constructor that would not "
          + "initialise the synthesised final field. Add the field and update the constructor by hand.";
    }
    if (generatesAllArgsConstructor(complexClass)
        && index.hasSubtypeWithExplicitSuperCall(complexClass)) {
      return "whose Lombok all-args constructor a subclass calls positionally via super(...); "
          + "appending a field would widen that constructor and leave the subclass's super(...) call "
          + "with no matching constructor. Add the field and update the subclass constructor by hand.";
    }
    return null;
  }

  /**
   * Whether a class's fields are final — either declared {@code @Value} (Lombok makes every field
   * {@code private final}) or {@code @Data} with explicitly-final fields. A synthesised field on such
   * a class becomes final too, so any explicit constructor must assign it or the class does not
   * compile.
   */
  private static boolean hasFinalFields(ModelSourceIndex.Type target) {
    if (hasTypeAnnotation(target.decl, "Value")) {
      return true;
    }
    return target.decl.getFields().stream()
        .filter(f -> !f.isStatic())
        .anyMatch(FieldDeclaration::isFinal);
  }

  /**
   * Whether a class would have a Lombok-generated all-args constructor whose parameter count grows
   * with the field count: {@code @AllArgsConstructor}, or {@code @Builder}/{@code @Value} without an
   * explicit constructor. Mirrors {@link SynthesisPlacement}'s own check for the subclass-super guard.
   */
  private static boolean generatesAllArgsConstructor(ModelSourceIndex.Type target) {
    boolean allArgs = hasTypeAnnotation(target.decl, "AllArgsConstructor");
    boolean builderOrValue =
        hasTypeAnnotation(target.decl, "Builder") || hasTypeAnnotation(target.decl, "Value");
    boolean explicitCtor = !target.decl.getConstructors().isEmpty();
    return allArgs || (builderOrValue && !explicitCtor);
  }

  /**
   * Whether a class uses the hand-written single-arg {@code @JsonCreator} + Lombok {@code @Builder}
   * idiom that field synthesis would break (finding B3): it declares a {@code @Builder} at the class
   * level AND an explicit constructor annotated {@code @JsonCreator}. Lombok's {@code @Builder} binds
   * to that explicit constructor, so appending fields makes the generated builder require parameters
   * the constructor never declared — {@code constructor X cannot be applied to given types}. A class
   * whose builder is Lombok-generated from its fields (no explicit constructor) takes new fields fine
   * and is not flagged.
   */
  private static boolean hasBuilderBoundJsonCreator(ModelSourceIndex.Type target) {
    if (!hasTypeAnnotation(target.decl, "Builder")) {
      return false;
    }
    return target.decl.getConstructors().stream()
        .anyMatch(ctor -> ctor.getAnnotations().stream()
            .anyMatch(a -> a.getNameAsString().equals("JsonCreator")
                || a.getNameAsString().endsWith(".JsonCreator")));
  }

  /**
   * Whether a class declares a class-level {@code @Builder} AND a hand-written explicit constructor
   * (of any annotation) that the builder binds to. Lombok's {@code @Builder} on a class with an
   * explicit constructor generates its builder against that constructor's parameters, so appending a
   * field makes the builder call it with one extra argument — {@code constructor X cannot be applied
   * to given types} (SSCS's {@code Appeal}: {@code @Data @Builder} + an 11-arg {@code @JsonProperty}
   * constructor with no {@code @JsonCreator}). Broader than {@link #hasBuilderBoundJsonCreator}, which
   * only catches the {@code @JsonCreator}-annotated flavour. A class with no explicit constructor
   * (Lombok generates the all-args form from its fields) takes new fields fine and is not flagged.
   */
  private static boolean hasBuilderBoundExplicitConstructor(ModelSourceIndex.Type target) {
    return hasTypeAnnotation(target.decl, "Builder")
        && !target.decl.getConstructors().isEmpty();
  }

  private static boolean hasTypeAnnotation(TypeDeclaration<?> decl, String simpleName) {
    return decl.getAnnotations().stream()
        .anyMatch(a -> a.getNameAsString().equals(simpleName)
            || a.getNameAsString().endsWith("." + simpleName));
  }

  /**
   * Drops from the synthesise list any field whose Java name already names a declared field on the
   * target class (or a superclass in the parsed source). Synthesis fills the definition-only gap —
   * fields the resolver could NOT bind to a model property — but a field can be unresolved yet still
   * <em>declared</em>: a {@code @JsonUnwrapped} parent (prl's {@code CaseData.allegationOfHarm}, whose
   * leaves are resolved through it, so the parent itself is never a leaf property) or a
   * {@code @JsonProperty}-renamed member whose CCD id the definition also lists as a separate field
   * (prl's {@code Court.courtName}). Re-declaring it produces {@code variable X is already defined}
   * (the prl compile break, finding B1). Skip it and record a gap so the drop is visible — the
   * existing member already carries the data; the field just needs {@code @CCD} the operator can add
   * (mirrors the Civil PascalCase-collision fix, generalised from resolved to <em>declared</em>
   * members).
   */
  private List<FieldModel> dropExistingFieldCollisions(
      ModelSourceIndex.Type target, List<FieldModel> synthesised) {
    Set<String> declared = declaredFieldNames(target);
    if (declared.isEmpty()) {
      return synthesised;
    }
    List<FieldModel> kept = new ArrayList<>();
    for (FieldModel field : synthesised) {
      if (declared.contains(field.getJavaName())) {
        gaps.add(GapEntry.builder()
            .sheet("CaseField")
            .rowKey(field.getId())
            .column("FieldType")
            .value(field.getFieldType())
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.OMITTED_FAIL)
            .detail("Definition field '" + field.getId() + "' would be synthesised as member '"
                + field.getJavaName() + "' onto " + target.simpleName + ", which already declares a "
                + "field of that name (e.g. a @JsonUnwrapped parent or a @JsonProperty-renamed "
                + "member); skipped to avoid a duplicate-field compile error. Annotate the existing "
                + "member with @CCD by hand if it should carry this definition field's metadata.")
            .build());
        continue;
      }
      kept.add(field);
    }
    return kept;
  }

  /**
   * The Java field names declared directly on a type and every superclass reachable in the parsed
   * source — the names a synthesised field must not collide with. Delegates to
   * {@link SynthesisPlacement#declaredFieldNames} so the emitter and the rebinder agree.
   */
  private Set<String> declaredFieldNames(ModelSourceIndex.Type target) {
    return placement.declaredFieldNames(target);
  }

  private FileEdits editsFor(Map<Path, FileEdits> byFile, Path file) {
    return byFile.computeIfAbsent(file, FileEdits::new);
  }

  /**
   * The {@code @JsonProperty} value the patch must add to a matched field, or null when none is
   * needed. A directly-declared member needs one when its Java name differs from the CCD id (so the
   * SDK's {@code FieldUtils.getFieldId} resolves the id). An {@code @JsonUnwrapped} leaf needs NONE:
   * its CCD id is composed from the parent's prefix plus the member's local name, so a
   * {@code @JsonProperty} carrying the fully-composed id would be re-prefixed and diverge.
   */
  private String renameFor(ResolvedProperty property, FieldModel field) {
    if (property.unwrap != null) {
      return null;
    }
    return property.memberName.equals(field.getId()) ? null : field.getId();
  }

  /**
   * Applies a file's planned edits and returns its before/after, or null when the edits were all
   * no-ops (every target already annotated).
   *
   * <p>Field annotations are inserted <em>textually</em>, as whole new lines immediately above the
   * field's own first line (below any of its existing annotations), indented to match — never
   * appended to the AST via {@code FieldDeclaration.addAnnotation}. JavaParser's
   * {@code LexicalPreservingPrinter} always renders an appended annotation on the SAME line as
   * whatever token already precedes the field (the last existing annotation, or the field's
   * modifiers when it has none), which (a) makes a reviewer's diff show the pre-existing annotation
   * line as *changed* even though its content is untouched, and (b) routinely blows past the team's
   * checkstyle line-length limit once a real {@code @CCD(...)} is appended. Working from the
   * PARSED (not lexically-preserved) tree and inserting lines by original source position keeps
   * every untouched line byte-for-byte identical while giving each added annotation its own line.
   */
  private RetrofitPatch.FilePatch renderFile(String relative, FileEdits edits) {
    String original = read(edits.file);
    CompilationUnit unit = parseCompilationUnit(original, relative);
    List<String> sourceLines = new ArrayList<>(Arrays.asList(original.split("\n", -1)));
    boolean droppedTrailingNewline = original.endsWith("\n") && !sourceLines.isEmpty();
    if (droppedTrailingNewline) {
      sourceLines.remove(sourceLines.size() - 1);
    }
    boolean needsCcdImport = false;
    boolean needsJsonPropertyImport = false;
    boolean needsFieldTypeImport = false;
    Set<String> accessClasses = new LinkedHashSet<>();
    Set<String> typeImports = new LinkedHashSet<>();

    // Collect one insertion (own-line block of annotation text) per field, keyed by the 1-based
    // source line its FIRST token (existing annotation, else modifier/type) begins on — so the
    // block lands directly above that line, below any annotations the field already carries.
    Map<Integer, List<String>> insertionsByLine = new TreeMap<>(Comparator.reverseOrder());

    for (TypeDeclaration<?> type : unit.getTypes()) {
      for (FieldDeclaration fieldDecl : type.getFields()) {
        String member = fieldDecl.getVariable(0).getNameAsString();
        // Skip fields already carrying @CCD (idempotency rule).
        if (hasAnnotation(fieldDecl, "CCD")) {
          continue;
        }
        int fieldLine = fieldFirstLine(fieldDecl);
        String indent = leadingWhitespace(sourceLines.get(fieldLine - 1));
        AnnotationPlan plan = edits.annotate.get(member);
        if (plan != null) {
          FieldModel field = plan.field;
          List<String> added = new ArrayList<>();
          if (plan.renameTo != null && !hasAnnotation(fieldDecl, "JsonProperty")) {
            added.add("@JsonProperty(\"" + plan.renameTo + "\")");
            needsJsonPropertyImport = true;
          }
          String ccd = renderer.render(field, indent.length());
          if (ccd != null) {
            added.add(ccd);
            needsCcdImport = true;
            needsFieldTypeImport |= renderer.usesFieldType(field);
            if (field.getAccessClassNames() != null) {
              accessClasses.addAll(field.getAccessClassNames());
            }
          }
          if (!added.isEmpty()) {
            insertionsByLine.computeIfAbsent(fieldLine, k -> new ArrayList<>())
                .addAll(indentEachLine(added, indent));
          }
          continue;
        }
        if (edits.ignore.contains(member) && !hasAnnotation(fieldDecl, "JsonIgnore")) {
          insertionsByLine.computeIfAbsent(fieldLine, k -> new ArrayList<>())
              .addAll(indentEachLine(List.of("@CCD(ignore = true)"), indent));
          needsCcdImport = true;
        }
      }
    }

    // Insert bottom-up (line numbers descending) so an earlier insertion never shifts a later
    // field's still-to-be-processed line number.
    for (Map.Entry<Integer, List<String>> entry : insertionsByLine.entrySet()) {
      sourceLines.addAll(entry.getKey() - 1, entry.getValue());
    }
    String printed = String.join("\n", sourceLines) + (droppedTrailingNewline ? "\n" : "");
    boolean needsJsonUnwrappedImport = false;

    // Synthesised definition-only fields: build one clearly-delimited block and insert it before
    // the target class's closing brace (textually, so the marker comments and indentation are
    // exactly as intended and the diff is one contiguous hunk at the end of the class body).
    if (!edits.synthesise.isEmpty()) {
      ImportBinder binder = new ImportBinder(existingImports(unit));
      SynthResult synth = renderSynthBlock(edits, binder);
      printed = insertBeforeClassEnd(printed, synth.text);
      needsCcdImport |= synth.usesCcd;
      needsJsonPropertyImport |= synth.usesJsonProperty;
      needsFieldTypeImport |= synth.usesFieldType;
      accessClasses.addAll(synth.accessClasses);
      typeImports.addAll(synth.typeImports);
    }

    // B2 overflow: instead of a synthesised block, add ONE prefix-less @JsonUnwrapped member of the
    // added CaseDataExtra class (its members flatten to the same CCD ids, keeping the root class's
    // constructor within the JVM limit).
    if (edits.unwrappedMemberType != null) {
      String memberBlock = "  " + SYNTH_BEGIN + '\n'
          + "  @JsonUnwrapped private " + edits.unwrappedMemberType + " "
          + SynthesisPlacement.EXTRA_MEMBER + ";\n"
          + "  " + SYNTH_END + '\n';
      printed = insertBeforeClassEnd(printed, memberBlock);
      needsJsonUnwrappedImport = true;
      // The CaseDataExtra class lives in the model package (same as the root class), so no import is
      // needed for it.
    }

    // Delegating no-arg getters for @JsonUnwrapped-reached complex-type grants: a @JsonIgnore
    // get<FieldId>() delegating through the model's real parent/member getters, so the config can
    // reference CaseData::get<FieldId> as a real method reference (grantComplexType's serialized-lambda
    // resolver needs one). Rendered as a delimited block before the class's closing brace.
    boolean needsJsonIgnoreImport = false;
    if (!edits.delegatingGetters.isEmpty()) {
      ImportBinder binder = new ImportBinder(existingImports(unit));
      SynthResult getters = renderDelegatingGetters(edits.delegatingGetters, binder);
      printed = insertBeforeClassEnd(printed, getters.text);
      needsJsonIgnoreImport = true;
      typeImports.addAll(getters.typeImports);
    }

    // Add imports on the printed text (doing it textually keeps the diff minimal and deterministic).
    printed = addImports(printed, needsCcdImport, needsJsonPropertyImport, needsFieldTypeImport,
        needsJsonUnwrappedImport, needsJsonIgnoreImport, accessClasses, typeImports);

    if (printed.equals(original)) {
      return null;
    }
    return new RetrofitPatch.FilePatch(relative, original, printed);
  }

  private SynthResult renderSynthBlock(FileEdits edits, ImportBinder binder) {
    SynthResult fields = renderSynthFields(edits.synthesise, "  ", binder);
    SynthResult wrapped = new SynthResult();
    wrapped.text = "  " + SYNTH_BEGIN + '\n' + fields.text + "  " + SYNTH_END + '\n';
    wrapped.usesCcd = fields.usesCcd;
    wrapped.usesJsonProperty = fields.usesJsonProperty;
    wrapped.usesFieldType = fields.usesFieldType;
    wrapped.accessClasses.addAll(fields.accessClasses);
    wrapped.typeImports.addAll(fields.typeImports);
    return wrapped;
  }

  /**
   * Renders one {@code private} field declaration per synthesised field, each preceded by its own
   * {@code @JsonProperty}/{@code @CCD} line(s) — never sharing a line with the {@code private ...;}
   * declaration or with each other — indented by {@code indent}, routing every bare type reference
   * through {@code binder} so a simple name already bound to a different type in the compilation
   * unit is written fully-qualified (finding C1) rather than emitting a clashing import. Shared by
   * the in-class synthesised block and the added {@code CaseDataExtra} class body.
   */
  private SynthResult renderSynthFields(
      List<FieldModel> synthesised, String indent, ImportBinder binder) {
    SynthResult result = new SynthResult();
    StringBuilder text = new StringBuilder();
    for (FieldModel field : synthesised) {
      boolean renamed = !field.getJavaName().equals(field.getId());
      if (renamed) {
        text.append(indent).append("@JsonProperty(\"").append(field.getId()).append("\")\n");
        result.usesJsonProperty = true;
      }
      String ccd = renderer.render(field, indent.length());
      if (ccd != null) {
        for (String line : indentEachLine(List.of(ccd), indent)) {
          text.append(line).append('\n');
        }
        result.usesCcd = true;
        result.usesFieldType |= renderer.usesFieldType(field);
        if (field.getAccessClassNames() != null) {
          result.accessClasses.addAll(field.getAccessClassNames());
        }
      }
      String javaType = bindTypeReferences(SyntheticFieldTypes.javaType(field), binder, result);
      text.append(indent).append("private ").append(javaType)
          .append(' ').append(field.getJavaName()).append(";\n");
    }
    result.text = text.toString();
    return result;
  }

  /**
   * Renders the delegating no-arg getters for {@code @JsonUnwrapped}-reached complex-type grants, in
   * one delimited block. Each getter is {@code @JsonIgnore} (so it introduces no Jackson property that
   * would double-serialise the already-flattened unwrapped value) and returns the leaf value by
   * chaining the model's real getters — {@code return getParent().getHop().getMember();}. Bare type
   * names in the return type are bound to imports through {@code binder} (finding C1), exactly as the
   * synthesised-field block does. The SDK never invokes the getter (it only reads the method name off
   * the serialized lambda), so the return type merely has to compile.
   */
  private SynthResult renderDelegatingGetters(
      List<ComplexTypeAuthGetter> getters, ImportBinder binder) {
    SynthResult result = new SynthResult();
    StringBuilder text = new StringBuilder();
    text.append("  ").append(SYNTH_BEGIN).append('\n');
    for (ComplexTypeAuthGetter getter : getters) {
      String returnType = bindTypeReferences(getter.getReturnTypeSource(), binder, result);
      StringBuilder chain = new StringBuilder();
      for (int i = 0; i < getter.getDelegationChain().size(); i++) {
        chain.append(i == 0 ? "" : ".").append(getter.getDelegationChain().get(i)).append("()");
      }
      text.append("  @JsonIgnore\n");
      text.append("  public ").append(returnType).append(' ').append(getter.getGetterName())
          .append("() {\n");
      text.append("    return ").append(chain).append(";\n");
      text.append("  }\n");
    }
    text.append("  ").append(SYNTH_END).append('\n');
    result.text = text.toString();
    return result;
  }

  /**
   * Rewrites a synthesised field's declared type so every bare (unqualified) simple-name token is
   * replaced by the binder's decision — its simple name (with an import registered) when the name is
   * free, or its fully-qualified name when the simple name is already bound to a different type in the
   * compilation unit (finding C1 — prl's {@code OtherDocuments}/{@code Miam}, fpl's {@code Document}).
   * Already-qualified tokens, primitives and {@code java.lang} types pass through untouched.
   */
  private String bindTypeReferences(String javaType, ImportBinder binder, SynthResult result) {
    if (modelPackage == null) {
      return javaType;
    }
    String rewritten = javaType;
    for (String simple : bareSimpleTypeNames(javaType)) {
      // A type the model source actually declares → its real FQN (an existing model class in another
      // sub-package). Otherwise a companion type freshly emitted into the model package.
      String fqn = index.fqnForSimpleName(simple, modelPackage)
          .orElse(modelPackage + "." + simple);
      String reference = binder.reference(fqn);
      if (!reference.equals(simple)) {
        // Qualify the token in the type string (word-boundary match so a substring is not touched).
        rewritten = rewritten.replaceAll("\\b" + java.util.regex.Pattern.quote(simple) + "\\b",
            java.util.regex.Matcher.quoteReplacement(reference));
      }
    }
    result.typeImports.addAll(binder.addedImports());
    return rewritten;
  }

  /**
   * The bare (unqualified) type simple-names inside a declared type string that need an import,
   * unwrapping generics. {@code java.util.List<uk.gov.hmcts...ListValue<BundleFolder>>} yields just
   * {@code BundleFolder}. Skips already-qualified tokens (they carry their package), primitives, and
   * {@code java.lang} types like {@code String}/{@code Integer} (auto-imported — adding
   * {@code import <modelPackage>.String} would shadow {@code java.lang.String} and break the whole
   * file, the regression this guards against). Case is deliberately NOT used to filter: Civil has
   * lower-cased fixed-list types ({@code paginationStyle}), so any remaining token is treated as an
   * importable model/companion type.
   */
  private static Set<String> bareSimpleTypeNames(String javaType) {
    Set<String> names = new LinkedHashSet<>();
    for (String token : javaType.split("[<>,\\s]+")) {
      if (token.isEmpty() || token.contains(".") || PRIMITIVES.contains(token)
          || JAVA_LANG_TYPES.contains(token)) {
        continue;
      }
      names.add(token);
    }
    return names;
  }

  private static final Set<String> PRIMITIVES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  // java.lang types the synthesised fields can legitimately use; these are auto-imported and must
  // NOT be re-imported from the model package.
  private static final Set<String> JAVA_LANG_TYPES = Set.of(
      "String", "Integer", "Long", "Short", "Byte", "Boolean", "Character", "Double", "Float",
      "Number", "Object", "CharSequence");

  /**
   * The compilation unit's existing single-type imports as simple name → fully-qualified name, so the
   * {@link ImportBinder} knows which simple names are already taken before the patch adds more.
   */
  private static Map<String, String> existingImports(CompilationUnit unit) {
    Map<String, String> imports = new LinkedHashMap<>();
    unit.getImports().forEach(imp -> {
      if (!imp.isAsterisk() && !imp.isStatic()) {
        String fqn = imp.getNameAsString();
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot >= 0) {
          imports.putIfAbsent(fqn.substring(lastDot + 1), fqn);
        }
      }
    });
    return imports;
  }

  /**
   * Inserts the synthesised block just before the final closing brace of the file — the top-level
   * model class's closing brace (model sources declare one top-level type per file). A blank line
   * precedes the block to separate it from the last existing member.
   */
  private static String insertBeforeClassEnd(String source, String block) {
    int lastBrace = source.lastIndexOf('}');
    if (lastBrace < 0) {
      return source;
    }
    String head = source.substring(0, lastBrace);
    String tail = source.substring(lastBrace);
    String separator = head.endsWith("\n\n") ? "" : head.endsWith("\n") ? "\n" : "\n\n";
    return head + separator + block + tail;
  }

  private static boolean hasAnnotation(FieldDeclaration field, String simpleName) {
    return field.getAnnotations().stream()
        .anyMatch(a -> a.getNameAsString().equals(simpleName)
            || a.getNameAsString().endsWith("." + simpleName));
  }

  /**
   * The 1-based source line a field declaration's own text begins on — its first existing
   * annotation when it has one, otherwise its first modifier/type token. Excludes any preceding
   * comment (JavaParser's {@code getBegin()} already does — a comment is attached but not part of
   * the node's own token range), so an added annotation lands directly above the field, below any
   * doc/line comment the team wrote for it.
   */
  private static int fieldFirstLine(FieldDeclaration fieldDecl) {
    return fieldDecl.getBegin()
        .orElseThrow(() -> new IllegalStateException("Field has no source position: " + fieldDecl))
        .line;
  }

  /** The leading run of spaces/tabs on a source line — the indent an inserted line above it must
   * match so the added annotation lines up with the field it decorates. */
  private static String leadingWhitespace(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  /**
   * Prefixes {@code indent} onto every physical line of every entry in {@code annotations} — an
   * entry rendered by {@link CcdAnnotationRenderer#render} may itself be multi-line (wrapped
   * members), so each of ITS lines needs the field's indent too, matching the continuation-line
   * shape the wrapped form already carries relative to its own first line.
   */
  private static List<String> indentEachLine(List<String> annotations, String indent) {
    List<String> out = new ArrayList<>();
    for (String annotation : annotations) {
      for (String line : annotation.split("\n", -1)) {
        out.add(indent + line);
      }
    }
    return out;
  }

  /**
   * Inserts any needed imports after the last existing import (or after the package declaration),
   * skipping ones already present. Keeps the emitted diff minimal and deterministic.
   */
  private String addImports(String source, boolean ccd, boolean jsonProperty, boolean fieldType,
      boolean jsonUnwrapped, boolean jsonIgnore, Set<String> accessClasses, Set<String> typeImports) {
    List<String> wanted = new ArrayList<>();
    if (ccd) {
      wanted.add("import uk.gov.hmcts.ccd.sdk.api.CCD;");
    }
    if (fieldType) {
      wanted.add("import uk.gov.hmcts.ccd.sdk.type.FieldType;");
    }
    if (jsonProperty) {
      wanted.add("import com.fasterxml.jackson.annotation.JsonProperty;");
    }
    if (jsonUnwrapped) {
      wanted.add("import com.fasterxml.jackson.annotation.JsonUnwrapped;");
    }
    if (jsonIgnore) {
      wanted.add("import com.fasterxml.jackson.annotation.JsonIgnore;");
    }
    for (String access : accessClasses) {
      wanted.add(renderer.accessImport(access));
    }
    wanted.addAll(typeImports);
    List<String> missing = new ArrayList<>();
    for (String imp : wanted) {
      if (!source.contains(imp)) {
        missing.add(imp);
      }
    }
    if (missing.isEmpty()) {
      return source;
    }
    List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
    int insertAt = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      if (line.startsWith("package ") || line.startsWith("import ")) {
        insertAt = i + 1;
      }
    }
    lines.addAll(insertAt, missing);
    return String.join("\n", lines);
  }

  private String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading model source " + file, e);
    }
  }

  /**
   * Parses one model source file at the configured (JAVA_21, lexical-preservation) level, surfacing
   * a precise error naming the file when the source will not parse (rather than the raw
   * {@code ParseProblemException} from {@link StaticJavaParser}, which names no file).
   */
  private CompilationUnit parseCompilationUnit(String source, String relative) {
    ParseResult<CompilationUnit> result = editParser.parse(source);
    return result.getResult().orElseThrow(() -> new IllegalStateException(
        "Failed to parse model source " + relative + " for patching: " + result.getProblems()));
  }

  private String relativePath(Path file) {
    return pathPrefix + index.sourceRoot().relativize(file).toString().replace('\\', '/');
  }

  static final String NO_NEWLINE_MARKER = "\\ No newline at end of file";

  /**
   * The trailing-context line count passed to {@link UnifiedDiffUtils#generateUnifiedDiff}.
   */
  private static final int DIFF_CONTEXT_LINES = 3;

  private String unifiedDiffFor(String relative, RetrofitPatch.FilePatch patch) {
    List<String> before = splitGitLines(patch.originalContent());
    List<String> after = splitGitLines(patch.patchedContent());
    Patch<String> diff = DiffUtils.diff(before, after);
    List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
        "a/" + relative, "b/" + relative, before, diff, DIFF_CONTEXT_LINES);
    unified = annotateMissingFinalNewline(
        unified, diff, before.size(), after.size(),
        !patch.originalContent().endsWith("\n") && !patch.originalContent().isEmpty(),
        !patch.patchedContent().endsWith("\n") && !patch.patchedContent().isEmpty());
    return String.join("\n", unified) + "\n";
  }

  /**
   * Inserts git's {@code \ No newline at end of file} marker after the diff line that carries a
   * file's final (unterminated) line. {@code java-diff-utils} never emits this marker, but
   * {@code git apply} <em>requires</em> it: without it, git treats the final line as newline-
   * terminated, its byte-image of the hunk no longer matches the file, and it rejects the hunk. 18
   * of Civil's model sources end without a trailing newline (e.g. {@code CCJPaymentDetails.java}),
   * so the omission broke every hunk whose context reached their closing brace.
   *
   * <p>The marker is warranted only when the LAST hunk's trailing context actually reaches the
   * file's true final line — i.e. the unchanged tail after the last delta is no longer than the
   * {@link #DIFF_CONTEXT_LINES}-line context window {@code generateUnifiedDiff} prints. A file
   * whose last change is followed by MORE unchanged lines than the context window never prints the
   * true final line at all (it is scrolled off both the printed hunk and by extension any
   * no-newline concern), so no marker belongs there — annotating anyway lands the marker after an
   * arbitrary trailing CONTEXT line instead of the file's real last line, and {@code git apply}
   * concatenates that context line with whatever source line follows it (the exact corruption seen
   * on Civil's {@code CorrectEmail.java}: the marker landed after the unmodified
   * {@code public boolean isCorrect()} opening line, three lines short of the file's actual last
   * line).
   */
  private static List<String> annotateMissingFinalNewline(
      List<String> unified, Patch<String> diff, int beforeSize, int afterSize,
      boolean oldMissing, boolean newMissing) {
    if (!oldMissing && !newMissing) {
      return unified;
    }
    List<AbstractDelta<String>> deltas = diff.getDeltas();
    if (deltas.isEmpty()) {
      return unified;
    }
    AbstractDelta<String> lastDelta = deltas.get(deltas.size() - 1);
    int oldTail = beforeSize
        - (lastDelta.getSource().getPosition() + lastDelta.getSource().size());
    int newTail = afterSize
        - (lastDelta.getTarget().getPosition() + lastDelta.getTarget().size());
    // The unchanged tail is identical on both sides (no further deltas past the last one), so
    // either measurement works; guard with both in case a delta type reports sizes asymmetrically.
    if (Math.max(oldTail, newTail) > DIFF_CONTEXT_LINES) {
      // The true final line sits beyond the printed context window — untouched by this diff, so no
      // marker is needed regardless of the file's newline status.
      return unified;
    }
    // The last hunk's trailing context reaches true EOF: the final printed body line IS the file's
    // last line. Find it (skipping any trailing header lines — there are none after the last hunk's
    // body, but guard defensively) and annotate the side(s) that actually lack a trailing newline.
    List<String> out = new ArrayList<>(unified);
    int last = out.size() - 1;
    while (last >= 0 && (out.get(last).startsWith("@@") || out.get(last).startsWith("+++")
        || out.get(last).startsWith("---"))) {
      last--;
    }
    if (last < 0) {
      return out;
    }
    char kind = out.get(last).isEmpty() ? ' ' : out.get(last).charAt(0);
    // A context (' ') or add ('+') final line is the new side's last line; a delete ('-') is the
    // old side's. Only annotate when that side actually lacks the newline.
    boolean annotate = switch (kind) {
      case '+' -> newMissing;
      case '-' -> oldMissing;
      default -> newMissing || oldMissing;
    };
    if (annotate) {
      out.add(last + 1, NO_NEWLINE_MARKER);
    }
    return out;
  }

  /**
   * Splits source into {@code git}'s unified-diff line model: a file's <em>trailing</em> newline is
   * a line terminator, not the start of an empty final line. A naive {@code split("\n", -1)}
   * fabricates a phantom empty element for every newline-terminated file, which shifts the line
   * numbering of any hunk that reaches end-of-file — so {@code git apply} rejects a
   * synthesised-fields hunk inserted before the class's closing brace (the exact failure hit on
   * Civil). Dropping that single trailing empty element makes the emitted hunk offsets match what
   * {@code git apply} expects. A file with no trailing newline keeps every element (its final line
   * is real content); the {@code \ No newline at end of file} marker for that case is added by
   * {@link #annotateMissingFinalNewline}.
   */
  public static List<String> splitGitLines(String content) {
    List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
    if (content.endsWith("\n") && !lines.isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }

  /** Accumulated edits for one source file. */
  private static final class FileEdits {
    private final Path file;
    private final Map<String, AnnotationPlan> annotate = new LinkedHashMap<>();
    private final Set<String> ignore = new LinkedHashSet<>();
    private final List<FieldModel> synthesise = new ArrayList<>();
    /** Delegating getters to add for @JsonUnwrapped-reached complex-type grants (retrofit). */
    private final List<ComplexTypeAuthGetter> delegatingGetters = new ArrayList<>();
    /** Simple name of a CaseDataExtra class to add as a prefix-less @JsonUnwrapped member (B2). */
    private String unwrappedMemberType;

    FileEdits(Path file) {
      this.file = file;
    }

    void annotate(String member, FieldModel field, String renameTo) {
      annotate.put(member, new AnnotationPlan(field, renameTo));
    }

    void ignore(String member) {
      // A field is never both annotated and ignored; annotate wins.
      if (!annotate.containsKey(member)) {
        ignore.add(member);
      }
    }

    void synthesise(String targetClass, List<FieldModel> fields) {
      this.synthesise.addAll(fields);
    }

    void addUnwrappedMember(String extraClassType) {
      this.unwrappedMemberType = extraClassType;
    }

    void addDelegatingGetter(ComplexTypeAuthGetter getter) {
      this.delegatingGetters.add(getter);
    }
  }

  /**
   * A planned field annotation: the field model plus the {@code @JsonProperty} value, if any.
   */
  private static final class AnnotationPlan {
    private final FieldModel field;
    private final String renameTo;

    AnnotationPlan(FieldModel field, String renameTo) {
      this.field = field;
      this.renameTo = renameTo;
    }
  }

  private static final class SynthResult {
    String text = "";
    boolean usesCcd;
    boolean usesJsonProperty;
    boolean usesFieldType;
    final Set<String> accessClasses = new LinkedHashSet<>();
    /** Fully-qualified imports for synthesised-field types declared by simple name. */
    final Set<String> typeImports = new LinkedHashSet<>();
  }
}
