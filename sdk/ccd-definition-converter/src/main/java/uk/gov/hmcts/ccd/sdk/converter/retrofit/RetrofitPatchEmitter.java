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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.DelegatingGetter;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;
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
  /** Checkstyle's line-length ceiling, which emitted lines must respect in the team's repo too. */
  private static final int MAX_EMITTED_LINE = 120;
  /**
   * Types an annotation is normally referenced WITH, whose import a removal may also leave unused:
   * deleting {@code @Getter(AccessLevel.NONE)} typically removes the file's only mention of
   * {@code AccessLevel}. Each is only dropped when the printed source no longer names it at all.
   */
  private static final Map<String, Set<String>> ANNOTATION_ARGUMENT_TYPES =
      Map.of("Getter", Set.of("AccessLevel"));

  private final ModelSourceIndex index;
  private final Map<String, ResolvedProperty> properties;
  private final CaseTypeModel model;
  private final ModelSourceIndex.Type rootType;
  private final CcdAnnotationRenderer renderer;
  private final TypeReconciler reconciler;
  private final SynthesisPlacement placement;
  private final ValueWrapperUnwrapper unwrapper;
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
  /**
   * The complex-type members {@link #planComplexTypeMembers} decided to synthesise, recorded as it
   * decides so the {@code CaseEventToComplexTypes} member walk can resolve them too — see
   * {@link RetrofitPlannedSynthesis} and {@link #planSynthesisedMembers}.
   */
  private final RetrofitPlannedSynthesis plannedSynthesis = RetrofitPlannedSynthesis.empty();
  /**
   * Model class FQN → the class-level {@code @ComplexType} pin {@link #planComplexTypeId} recorded for
   * it. Kept alongside the per-file edits because the collision that matters is per CLASS, not per
   * file: two definition complex types backed by one class can only pin one ID, and this map is what
   * detects the second attempt.
   */
  private final Map<String, ComplexTypeIdPlan> complexTypeIdPins = new LinkedHashMap<>();
  /**
   * The definition complex types with no model class of their own, which the converter emits as
   * generated companions ({@link RetrofitComplexTypeEmitter} filters on exactly the complement of
   * {@link #planComplexTypeMembers}'s own lookup, so a companion exists for precisely these IDs).
   * {@link #planRetypes} re-declares the fields that reference them as the companion, which is the only
   * thing that makes the companion reachable at all.
   */
  private final Map<String, ComplexTypeModel> companionComplexTypes = new LinkedHashMap<>();
  /**
   * The field re-declarations {@link #planRetypes} decided on, recorded as it decides so the
   * {@code CaseEventToComplexTypes} member walk descends into the companion rather than the class the
   * parsed source still names — see {@link RetrofitPlannedRetypes}.
   */
  private final RetrofitPlannedRetypes plannedRetypes = RetrofitPlannedRetypes.empty();
  /**
   * The {@code @CCD(hint)} values this patch will pin onto existing complex-type members, read by the
   * {@code CaseEventToComplexTypes} walk because a member's hint CASCADES onto every event row placing it.
   * See {@link RetrofitPlannedHints}.
   */
  private final RetrofitPlannedHints plannedHints = RetrofitPlannedHints.empty();
  /**
   * The retype refusals {@link #planRetype} recorded, keyed {@code ownerFile#memberName} of the field
   * that references the definition type. Read by {@link #withComplexCompanion} so the field instead
   * NAMES the companion with {@code @CCD(typeParameterClass)} — which needs no declaration change and so
   * has none of the retype's refusals — and, for those it does not cover, reported as gaps at the end of
   * {@link #emit()}.
   */
  private final Map<String, RefusedRetype> refusedRetypes = new LinkedHashMap<>();
  /**
   * The {@code ownerFile#memberName} keys {@link #withComplexCompanion} named a companion on, so a
   * refusal it covered is not also reported as a gap.
   */
  private final Set<String> companionNamedFields = new LinkedHashSet<>();

  /** A refused retype, held until it is known whether the companion-naming fallback covers it. */
  private record RefusedRetype(String sheet, String rowKey, String definitionId,
      ResolvedProperty property, String target, String reason) {
  }
  /**
   * Every ID the definition's {@code ComplexTypes} sheet declares. A class whose own simple name is one
   * of these is never renamed to a different ID: that name already has a definition row of its own.
   */
  private final Set<String> definitionComplexTypeIds = new LinkedHashSet<>();
  /**
   * Definition type ID → the model type its own referencing field is declared as, for the IDs no
   * name-based lookup reaches ({@link RetrofitTypeBinder}). Empty unless the run installs it, so
   * generate mode, the matcher's report-only pass and every test that does not exercise it keep the
   * historical name-only behaviour.
   *
   * <p>These are pinned with {@code @ComplexType(name = <id>, generate = true)} exactly as the
   * name-matched bindings are — the pin is what makes the SDK emit the type under the definition's ID
   * instead of the class's Java name — and are excluded from the companion set, because a type with a
   * real backing class must not ALSO be generated as a standalone companion.
   */
  private Map<String, ModelSourceIndex.Type> declaredTypeBindings = Map.of();
  /**
   * The definition's fixed lists as the linker produced them — before the rebinder drops the ones a model
   * enum already serves — supplying the {@code ListElement} labels pinned onto those enums' constants.
   * See {@link #bindDefinitionFixedLists} for why the pre-drop list is the load-bearing one.
   */
  private List<FixedListModel> definitionFixedLists = List.of();
  /**
   * The team's State enum when the run REUSES it, else null — supplying the constants that carry the
   * definition's {@code State} sheet labels. See {@link #bindReusedStateEnum}.
   */
  private ModelSourceIndex.Type stateEnum;
  /**
   * CCD state ID → Java constant name for that reused enum, empty when none is reused.
   * See {@link #bindReusedStateEnum}.
   */
  private Map<String, String> stateConstantsByStateId = Map.of();
  /**
   * The naming-strategy-derived names the {@code CaseEventToComplexTypes} member walk relied on, which
   * this patch pins with an explicit {@code @JsonProperty} so the naming-strategy-blind SDK generator
   * derives the same CCD id. Empty for the throwaway planning instance (whose graph has not run yet)
   * and in generate mode. See {@link RetrofitPinnedNames}.
   */
  private final RetrofitPinnedNames pinnedNames;
  /**
   * The {@code @Getter(AccessLevel.NONE)} suppressions the placements resolved through, which this
   * patch deletes so Lombok generates the getters the emitted config references. Read off the index
   * itself, so the plan the patch realises is by construction the one the placements recorded into —
   * see {@link RetrofitUnsuppressedGetters}.
   */
  private final RetrofitUnsuppressedGetters unsuppressedGetters;
  /**
   * The SAME {@code simpleName → fqn} decisions the companion/config emitters bind their type
   * references with ({@code ConversionOptions.retrofitTypeFqnOverrides}), consulted before this
   * emitter's own index lookup.
   *
   * <p>Without it the two paths resolve an ambiguous simple name independently and can disagree:
   * prl declares {@code Miam} in both {@code complextypes.applicationtab} and
   * {@code complextypes.citizen.response.miam}. The overrides map (which honours
   * {@code --type-package-hint}) picks the citizen one, so the config emitter emits
   * {@code Miam::getAttendedMiam} — while {@link ModelSourceIndex#fqnForSimpleName} knows nothing of
   * the hint and, finding neither candidate under the model package, takes the first arbitrarily, so
   * the patch declared the synthesised field as {@code applicationtab.Miam}, which has no such member:
   * {@code no suitable method found for mandatory(Miam::getAttendedMiam)}. Sharing one map is what
   * makes the two impossible to disagree (the same principle as {@link RetrofitPinnedNames}).
   */
  private final Map<String, String> typeFqnOverrides;

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
   * Creates a patch emitter with no naming-strategy pins — for the throwaway planning pass, whose
   * member walk has not run yet, and for tests exercising no {@code @JsonNaming} model.
   *
   * @param constructorLimit the field-count threshold for CaseDataExtra overflow; {@code <= 0} uses
   *                          the default
   * @param pathPrefix the source-root path relative to the repo root, prepended to every diff path
   */
  RetrofitPatchEmitter(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      CaseTypeModel model, ModelSourceIndex.Type rootType, String configPackage,
      int constructorLimit, String pathPrefix) {
    this(index, resolution, model, rootType, configPackage, constructorLimit, pathPrefix,
        RetrofitPinnedNames.empty());
  }

  /**
   * Creates a patch emitter with an explicit constructor-limit override (finding B2) and a path
   * prefix rooting the emitted diff at the model repo root (patch-root consistency).
   *
   * @param constructorLimit the field-count threshold for CaseDataExtra overflow; {@code <= 0} uses
   *                          the default
   * @param pathPrefix the source-root path relative to the repo root (e.g. {@code service/src/main/java/}),
   *                   prepended to every emitted diff path; empty when repo root == source root
   * @param pinnedNames the naming-strategy-derived names the {@code CaseEventToComplexTypes} member
   *                    walk relied on, which this patch pins with an explicit {@code @JsonProperty};
   *                    must come from the SAME run's graph so reliance and pin cannot disagree
   */
  RetrofitPatchEmitter(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      CaseTypeModel model, ModelSourceIndex.Type rootType, String configPackage,
      int constructorLimit, String pathPrefix, RetrofitPinnedNames pinnedNames) {
    this(index, resolution, model, rootType, configPackage, constructorLimit, pathPrefix,
        pinnedNames, Map.of());
  }

  /**
   * Creates a patch emitter that binds bare type references with the same FQN decisions the
   * companion/config emitters use.
   *
   * @param typeFqnOverrides the run's {@code ConversionOptions.retrofitTypeFqnOverrides} — see
   *                         {@link #typeFqnOverrides} for why sharing this map is load-bearing
   */
  RetrofitPatchEmitter(ModelSourceIndex index, PropertyResolver.Resolution resolution,
      CaseTypeModel model, ModelSourceIndex.Type rootType, String configPackage,
      int constructorLimit, String pathPrefix, RetrofitPinnedNames pinnedNames,
      Map<String, String> typeFqnOverrides) {
    this.typeFqnOverrides = typeFqnOverrides == null ? Map.of() : typeFqnOverrides;
    this.pinnedNames = pinnedNames;
    this.unsuppressedGetters = index.unsuppressedGetters();
    this.index = index;
    this.properties = resolution.properties;
    this.model = model;
    this.rootType = rootType;
    this.renderer = new CcdAnnotationRenderer(configPackage);
    this.reconciler = new TypeReconciler(index);
    this.placement = new SynthesisPlacement(index, constructorLimit);
    this.unwrapper = new ValueWrapperUnwrapper(index);
    this.modelPackage = rootType != null ? rootType.packageName : null;
    this.pathPrefix = normalisePrefix(pathPrefix);
  }

  /**
   * Installs the declared-type bindings for the definition IDs no name-based lookup reaches, so those
   * types are pinned onto the classes their referencing fields name instead of being emitted as
   * companions nothing references. Called once per retrofit run by {@link RetrofitConverter}, on BOTH
   * the throwaway planning emitter and the real one, so the plan the member walk resolves against and
   * the patch that realises it bind every ID the same way.
   *
   * @param bindings definition ID → bound model type, from {@link RetrofitTypeBinder}
   */
  void bindDeclaredTypes(Map<String, ModelSourceIndex.Type> bindings) {
    this.declaredTypeBindings = bindings == null ? Map.of() : bindings;
  }

  /**
   * Installs the definition's fixed lists as the LINKER produced them, before
   * {@link RetrofitModelRebinder} drops the ones a model enum already serves.
   *
   * <p>Load-bearing that this is the pre-drop list: the rebinder removes exactly the lists whose rows the
   * team's own enum emits (so no companion is generated for them), which is precisely the set whose
   * constants need the {@code @CCD(label)} pin. Reading {@code model.getFixedLists()} instead would see
   * only the companion-backed remainder and pin nothing where it matters — the whole ≈2,205-line bucket.
   *
   * @param lists the linked model's fixed lists, or null to leave label pinning off (generate mode, the
   *              report-only pass, and tests that do not exercise it)
   */
  void bindDefinitionFixedLists(List<FixedListModel> lists) {
    this.definitionFixedLists = lists == null ? List.of() : lists;
  }

  /**
   * Installs the team's State enum as the one this run REUSES, with the CCD-ID → constant-name mapping
   * the reuse resolved through, so the definition's {@code State} sheet labels can be pinned onto its
   * constants.
   *
   * <p>Load-bearing that the caller passes these only on the reuse decision (every definition state ID
   * resolves — proposal decision 3). When the converter generates a fresh State enum instead, that
   * generated enum carries the same three columns itself and the team's enum must be left alone; passing
   * nothing here is what expresses that.
   *
   * @param stateEnumFqn the reused enum's FQN, or null to leave state-label pinning off (generate mode,
   *                     a conflicting enum, the report-only pass, and tests that do not exercise it)
   * @param constantByStateId CCD state ID → Java constant name, from {@code StateEnumAnalyser}
   */
  void bindReusedStateEnum(String stateEnumFqn, Map<String, String> constantByStateId) {
    this.stateEnum = stateEnumFqn == null ? null : index.byFqn(stateEnumFqn).orElse(null);
    this.stateConstantsByStateId =
        constantByStateId == null ? Map.of() : constantByStateId;
  }

  /**
   * The bindings this emitter resolved, for {@link RetrofitConverter} to hand the companion emitter and
   * the reserved-name sets so they agree on which IDs have a backing class.
   *
   * @return definition ID → bound model type
   */
  Map<String, ModelSourceIndex.Type> declaredTypeBindings() {
    return declaredTypeBindings;
  }

  /**
   * The model class backing a definition complex type: the name-based match if there is one, else the
   * class its referencing field is declared as ({@link RetrofitTypeBinder}).
   *
   * <p>Every decision keyed on "does this ID have a model class" goes through here, so the companion
   * set, the retype set and the ID pin cannot disagree about a given ID — the disagreement that leaves a
   * generated companion referenced by nothing.
   */
  private Optional<ModelSourceIndex.Type> boundClass(String definitionId) {
    Optional<ModelSourceIndex.Type> named = index.complexTypeClass(definitionId, modelPackage);
    if (named.isPresent()) {
      return named;
    }
    return Optional.ofNullable(declaredTypeBindings.get(definitionId));
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
    final Map<Path, FileEdits> byFile = new LinkedHashMap<>();

    // 0. Which definition complex types have a model class and which are emitted as companions, then
    // which fields can be re-declared as their companion. Both run BEFORE the annotation claims below
    // because a field whose retype is REFUSED is annotated differently — it names the companion with
    // @CCD(typeParameterClass) instead (see withComplexCompanion) — so the claim cannot be made until
    // the retype decision exists. The file edits either pass records are independent of the other's:
    // a retype rewrites a token on the declaration line in place, an annotation inserts lines above it.
    indexDefinitionComplexTypes();
    planRetypes(byFile);

    // Every claim about a field goes through here rather than straight onto its declaration, so a
    // member several classes INHERIT is decided once with all their claims in hand: one annotation on
    // the shared declaration when they agree, and a class-level @CCD(member = …) on each class that
    // does not (see RetrofitInheritedMembers). Committed to the per-file edits below, after all four
    // claim sites have run.
    RetrofitInheritedMembers inherited = new RetrofitInheritedMembers(renderer);

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
      FieldModel annotated = withTypeParameterClass(field, property);
      inherited.annotate(property, annotated, renameFor(property, annotated));
    }

    // 2. Unmatched Java fields → @CCD(ignore = true).
    Set<String> definitionIds = new LinkedHashSet<>(caseFieldsById.keySet());
    // Complex-type member IDs never mean a top-level CaseData match, but a model property whose CCD
    // id equals a data-bearing CaseField id is matched; anything else on the CaseData tree is
    // unmatched Java.
    for (ResolvedProperty property : properties.values()) {
      if (!definitionIds.contains(property.ccdId)) {
        inherited.ignore(property);
      }
    }

    // 3. Complex-type members: annotate/ignore/synthesise on each resolved model complex class.
    planComplexTypeMembers(byFile, inherited);

    // 3x. Reconcile the ROOT class's definition-only fields against the names it already declares, so
    // any member the root itself already has a provably-identical field for is CLAIMED as an adoption
    // here rather than left to step 4. The reconciliation has to precede 3z because an adoption is a
    // claim about a declaration like any other — one that must be weighed against the
    // @CCD(ignore = true) step 2 makes about the same field — and 3z is where the claims are settled.
    // Only the decision is hoisted; the placement it feeds (which class the surviving fields land on,
    // and the constructor-limit plan) still happens in step 4. Safe to compute this early because it is
    // a pure function of the resolved properties and the model's case fields, neither of which any step
    // between here and there touches.
    List<FieldModel> synthesised = new ArrayList<>();
    for (FieldModel field : model.getCaseFields()) {
      if (!properties.containsKey(field.getId())) {
        synthesised.add(field);
      }
    }
    final List<FieldModel> rootPlaceable = rootType == null || synthesised.isEmpty()
        ? synthesised
        : dropExistingFieldCollisions(byFile, inherited, rootType, synthesised);

    // 3z. Commit the field claims: one @CCD per declaration, plus a class-level @CCD(member = …) on
    // each class whose rows the definition configures differently. Runs after every claim site so no
    // decision is taken on a partial view.
    for (RetrofitInheritedMembers.Decision decision : inherited.decisions()) {
      RetrofitInheritedMembers.Claim base = decision.base();
      if (base.isIgnore()) {
        editsFor(byFile, base.ownerFile()).ignore(base.memberName());
      } else {
        editsFor(byFile, base.ownerFile()).annotate(base.memberName(), base.field(),
            base.renameTo());
      }
      for (RetrofitInheritedMembers.Claim override : decision.overrides()) {
        index.byFqn(override.reachedThroughFqn()).ifPresent(type ->
            editsFor(byFile, type.file).overrideMember(type.simpleName,
                new MemberOverridePlan(override.memberName(),
                    inherited.overrideMembers(override), inherited.usesFieldType(override),
                    inherited.accessClasses(override))));
      }
    }

    // 3y. Suppress the ComplexTypes rows of every reachable model class the definition never declares.
    // Must run AFTER step 3 (which fills definitionComplexTypeIds and takes the real ID pins, which
    // win) and after 3z (whose ignore decisions remove fields from the SDK's reachability walk).
    planSuppressedComplexTypes(byFile, inherited);

    // (Retypes were planned in step 0, before the annotation claims that read their outcome.)

    // 3b. Pin the @JsonNaming-derived member names the CaseEventToComplexTypes walk resolved through
    // a class-level naming strategy. Must run AFTER step 3 so a field that is also a definition
    // complex-type member keeps that plan's own @JsonProperty rather than gaining a second one.
    planPinnedNames(byFile);

    // 3c. Pin the definition's FixedList IDs onto the model enums their referencing fields are declared
    // as. Must run AFTER step 3, whose complex-type pins take precedence in complexTypeIdPins: a type
    // reachable as both is a complex type first (only ComplexTypeGenerator emits its members).
    planFixedListIds(byFile);

    // 3d. Pin the definition's State sheet Name/TitleDisplay/Description onto the constants of the
    // team's reused State enum. Runs BEFORE the FixedLists labels because a constant carries at most one
    // @CCD (it is not @Repeatable) so the two passes share one per-constant claim, and the State sheet's
    // three columns are always compared whereas a State enum that ALSO backs a fixed list is the rarer
    // case. Pins are merged per CONSTANT, not per enum, so an enum serving both roles still gets every
    // label either pass can supply.
    planStateLabels(byFile);

    // 3e. Pin the definition's ListElement onto each constant of every model enum backing a FixedLists
    // ID. Must run AFTER 3c so an enum's ID pin and its label pins resolve the same list.
    planFixedListLabels(byFile);

    // 4. Synthesised definition-only fields onto the root model class. The placeable set was reconciled
    // against the root's declared names in 3x, whose adoption claims 3z has since settled.
    RetrofitPatch.FilePatch extraClassFile = null;
    if (rootType != null && !synthesised.isEmpty()) {
      List<FieldModel> placeable = rootPlaceable;
      SynthesisPlacement.Plan plan = placement.plan(rootType, placeable);
      if (plan.overflow && plan.existingHost != null) {
        // B2 borderline: even the single added @JsonUnwrapped CaseDataExtra member would tip the root
        // over the constructor limit (SSCS: 254 + 1 > 254). Nest ALL synthesised fields into an
        // EXISTING prefix-less @JsonUnwrapped member's class instead, adding ZERO fields to the root.
        // Prefix-less unwrapping flattens the added members to the same CCD IDs, and the config
        // references them through that member's existing getter.
        SynthesisPlacement.ExistingHost host = plan.existingHost;
        List<FieldModel> hostPlaceable = placement.renameCaseInsensitiveCollisions(
            host.type, reportExistingFieldCollisions(host.type, placeable));
        editsFor(byFile, host.type.file).synthesise(host.type.simpleName, hostPlaceable);
        if (synthesisedFieldsNeedNonNull(host.type.decl)) {
          editsFor(byFile, host.type.file).includeSynthesisedWhenNonNull();
        }
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
      } else if (plan.dropAllArgsConstructor) {
        // The root's OWN field count already exceeds the constructor limit, so no CaseDataExtra member
        // can help — the generated all-args constructor counts every own field wherever the
        // synthesised ones live. The class builds through a builder that survives the drop and nothing
        // constructs it positionally (SynthesisPlacement verified both), so the safe fix is to remove
        // its @AllArgsConstructor: synthesise directly onto the root and drop the annotation.
        editsFor(byFile, rootType.file).synthesise(rootType.simpleName,
            placement.renameCaseInsensitiveCollisions(rootType, placeable));
        if (synthesisedFieldsNeedNonNull(rootType.decl)) {
          editsFor(byFile, rootType.file).includeSynthesisedWhenNonNull();
        }
        editsFor(byFile, rootType.file).removeTypeAnnotation("AllArgsConstructor");
        gaps.add(GapEntry.builder()
            .sheet("CaseField")
            .rowKey(rootType.simpleName)
            .column("FieldType")
            .value("(constructor limit)")
            .category(GapCategory.UNSUPPORTED_VALUE)
            .action(GapAction.ADVISORY)
            .detail(rootType.simpleName + " has more fields than the JVM/Lombok all-args constructor "
                + "limit allows, so the patch drops its @AllArgsConstructor. This is safe here: the "
                + "class is constructed through its builder (verified no positional new "
                + rootType.simpleName + "(...) call site and no subclass super(...) call relies on the "
                + "all-args constructor). If code later needs an all-args constructor, use the builder "
                + "or add an explicit constructor.")
            .build());
      } else {
        editsFor(byFile, rootType.file).synthesise(rootType.simpleName,
            placement.renameCaseInsensitiveCollisions(rootType, placeable));
        if (synthesisedFieldsNeedNonNull(rootType.decl)) {
          editsFor(byFile, rootType.file).includeSynthesisedWhenNonNull();
        }
      }
    }

    // 4a. Un-suppress the Lombok getters the placements above resolved through: delete the
    // @Getter(AccessLevel.NONE) from each @JsonUnwrapped member ModelSourceIndex recorded while
    // answering hasResolvableGetter. Recorded, not re-derived, so the patch removes exactly the
    // suppressions the member walk / page placement / synthesis host choice relied on — see
    // RetrofitUnsuppressedGetters. Runs after every planning step so nothing recorded is missed.
    for (RetrofitUnsuppressedGetters.Unsuppression u : unsuppressedGetters.all()) {
      editsFor(byFile, u.file()).removeFieldAnnotation(u.memberName(), "Getter");
    }

    // 5. Delegating no-arg getters on the root class for AuthorisationComplexType grants whose complex
    // field is reached only through a @JsonUnwrapped member (so the flat CCD id has no direct getter).
    // The config emits CaseData::get<FieldId>; without a real method of that name grantComplexType's
    // serialized-lambda resolver fails at generation. Each is @JsonIgnore (adds no Jackson property)
    // and delegates through the model's real parent/member getters (mirroring fpl's own
    // getOrderCollection()); the SDK reads CaseFields from FIELDS not getters, so it adds no CaseField.
    if (rootType != null && model.getDelegatingGetters() != null
        && !model.getDelegatingGetters().isEmpty()) {
      for (DelegatingGetter getter : model.getDelegatingGetters().values()) {
        editsFor(byFile, rootType.file).addDelegatingGetter(getter);
      }
    }

    // 6. Report the retype refusals the companion-naming fallback did not cover. Last, so the report
    // states what the patch actually did rather than what one pass decided in isolation.
    reportUncoveredRetypeRefusals();

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
    // A fresh file starts with no imports, so the binder has a clean slate. The class this emitter
    // writes carries no @JsonInclude, so its defaults leave nulls out of nothing and the fields need
    // no per-field inclusion setting.
    SynthResult synth =
        renderSynthFields(fields, "  ", new ImportBinder(new LinkedHashMap<>()), false);
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

  /**
   * Runs <em>only</em> the complex-type member planning and returns the members this patch would
   * synthesise, so the {@code CaseEventToComplexTypes} member walk can be built against the model as
   * the applied patch will leave it (see {@link RetrofitPlannedSynthesis}).
   *
   * <p>This is the same {@link #planComplexTypeMembers} the real {@link #emit()} runs, not a
   * re-derivation, so the graph can never resolve a member the patch declines to add. The planned file
   * edits are discarded — the emitter instance this is called on is a throwaway whose gaps the real
   * run records again — and nothing is rendered or written.
   *
   * @return the members the patch would synthesise onto the team's complex classes
   */
  RetrofitPlannedSynthesis planSynthesisedMembers() {
    Map<Path, FileEdits> discarded = new LinkedHashMap<>();
    // A throwaway collector too: this pass is only after the synthesised members and retypes, so the
    // field claims it makes are discarded along with the edits.
    planComplexTypeMembers(discarded, new RetrofitInheritedMembers(renderer));
    // Runs here too so {@link #plannedRetypes()} is populated on the same throwaway instance: the graph
    // needs BOTH plans, and running them from one pass is what keeps the plan the walk resolves against
    // identical to the one the real emit produces.
    planRetypes(discarded);
    return plannedSynthesis;
  }

  /**
   * The field re-declarations this patch will make, valid once {@link #planSynthesisedMembers()} (or a
   * full {@link #emit()}) has run on this instance.
   *
   * @return the planned retypes, empty when the definition needs none
   */
  RetrofitPlannedRetypes plannedRetypes() {
    return plannedRetypes;
  }

  /**
   * The complex-type member hints this patch will pin, valid once {@link #planSynthesisedMembers()} (or a
   * full {@link #emit()}) has run on this instance.
   *
   * @return the planned hints, empty when the patch pins none
   */
  RetrofitPlannedHints plannedHints() {
    return plannedHints;
  }

  /**
   * Re-declares each field whose definition complex type has no model class of its own as the generated
   * companion class the converter emits for that type.
   *
   * <p>Binding is otherwise one-directional: {@link RetrofitComplexTypeEmitter} generates a companion
   * for every definition complex type {@link #planComplexTypeMembers} could not find a class for, but
   * nothing pointed the team's field at it, so {@code CaseFieldGenerator.resolveType} kept emitting the
   * declared class's own Java simple name as the {@code FieldType} and the companion was dead code —
   * 668 of 781 companions across the sweep. Rewriting the declaration is what closes that loop, and it
   * is the ONLY thing that can: {@code @CCD(typeOverride)} takes a {@code FieldType} enum constant, and
   * a definition type ID like {@code dwpAT38DocumentCT} is not one (which is why {@link TypeReconciler}
   * leaves such a field alone), while the collection case already works through the {@code String}
   * {@code typeParameterOverride}.
   *
   * <p>It also resolves, rather than contradicts, the collision {@link #planComplexTypeId} refuses:
   * sscs backs ten {@code dwp*DocumentCT} definition types with one {@code DwpResponseDocument}, and a
   * class can carry only one {@code @ComplexType(name)}. Giving each FIELD its own companion needs no
   * shared class to name, and reproduces members the team's class does not have at all (each
   * {@code dwp*DocumentCT} declares its own {@code Label} member).
   *
   * <p>Refused, leaving the declaration exactly as it is, when:
   * <ul>
   *   <li>the declared type is not a parsed model class — a field declared {@code String} or as an
   *       SDK-predefined type disagrees with the definition about far more than a name, and silently
   *       swapping a platform type for a generated companion is not a safe edit;</li>
   *   <li>the declared type IS already the companion (idempotency, as every op must be);</li>
   *   <li>anything in the parsed source calls the member's accessors — the retype changes what
   *       {@code getDwpAT38Document()} returns and what its setter takes, so a caller assigning it to
   *       the old type stops compiling ({@link ModelSourceIndex#calledMethodNames});</li>
   *   <li>the owning class has a constructor parameter of the old type for this member, is instantiated
   *       positionally, or has a subclass calling {@code super(...)} positionally — each binds the
   *       field's type into a signature the retype changes out from under.</li>
   * </ul>
   * Each refusal is reported as a gap so the row is visible rather than silently conceded, and is NOT
   * recorded in the plan: the member walk must keep resolving such a field against the class the source
   * still names.
   */
  private void planRetypes(Map<Path, FileEdits> byFile) {
    for (FieldModel field : model.getCaseFields()) {
      ResolvedProperty property = properties.get(field.getId());
      if (property == null) {
        continue; // definition-only: synthesised with the definition's own type already
      }
      ComplexTypeModel companion = companionFor(field);
      if (companion == null) {
        continue;
      }
      planRetype(byFile, property, companion, "CaseField", field.getId(), field.getId());
    }
    for (ComplexTypeModel complexType : model.getComplexTypes()) {
      Optional<ModelSourceIndex.Type> type = boundClass(complexType.getId());
      if (type.isEmpty()) {
        continue; // the companion case itself — its own members are generated, not patched
      }
      // The same class planComplexTypeMembers annotates, reached the same way, so a member is retyped
      // on exactly the class whose members carry this definition type's @CCD.
      ModelSourceIndex.Type complexClass = unwrapper.unwrap(type.get());
      PropertyResolver.Resolution memberResolution =
          new PropertyResolver(index).resolve(complexClass);
      for (FieldModel member : complexType.getMembers()) {
        ResolvedProperty property = memberResolution.properties.get(member.getId());
        if (property == null) {
          continue;
        }
        ComplexTypeModel companion = companionFor(member);
        if (companion == null) {
          continue;
        }
        planRetype(byFile, property, companion, "ComplexTypes",
            complexType.getId() + "/" + member.getId(), null);
      }
    }
  }

  /**
   * The generated companion a field's definition type refers to, or null when the field's type has a
   * model class of its own (or is a platform/leaf type). A collection field is decided on its ELEMENT
   * type — the class CCD addresses the members on — exactly as {@code resolveCollectionType} derives
   * the {@code FieldTypeParameter} from the element class.
   */
  private ComplexTypeModel companionFor(FieldModel field) {
    String parameter = field.getFieldTypeParameter();
    String typeId = parameter != null && !parameter.isEmpty() ? parameter : field.getFieldType();
    return typeId == null ? null : companionComplexTypes.get(typeId);
  }

  /**
   * Records one field's retype, applying the refusals documented on {@link #planRetypes}.
   *
   * @param sheet the gap sheet a refusal is reported against
   * @param rowKey the gap row key a refusal is reported against
   * @param caseFieldId the CCD case-field id when this is a root field, null for a complex-type member
   */
  private void planRetype(Map<Path, FileEdits> byFile, ResolvedProperty property,
      ComplexTypeModel companion, String sheet, String rowKey, String caseFieldId) {
    String target = companionSimpleName(companion);
    com.github.javaparser.ast.type.Type declared = retypeTarget(property.declaredType);
    if (!(declared instanceof ClassOrInterfaceType cit)) {
      // Null (a generic or raw token the single-name substitution cannot express) or a primitive/array
      // declaration. Either way there is no token to rewrite, and the definition type keeps no
      // counterpart — report it rather than dropping the row silently.
      recordRetypeGap(sheet, rowKey, companion.getId(), property, target,
          "is declared as " + property.declaredType
              + ", whose type token the retype cannot rewrite without moving type arguments onto "
              + target + ". Re-declare the field by hand");
      return;
    }
    if (target.equals(cit.getNameAsString())) {
      return; // already the companion: a re-applied patch is a no-op
    }
    Optional<ModelSourceIndex.Type> declaredClass = index.resolve(property.context, cit);
    if (declaredClass.isEmpty()) {
      // Declared as String, an SDK-predefined type, or anything else outside the parsed model. The
      // definition and the model disagree about the field's shape, not merely its name; report it and
      // leave the declaration alone.
      recordRetypeGap(sheet, rowKey, companion.getId(), property, target,
          "is declared as " + cit.getNameAsString() + ", which is not a model class — the definition "
              + "type and the declared type disagree on more than the name. Re-declare the field as "
              + target + " by hand, or give the definition type a model class");
      return;
    }
    String refusal = retypeRefusal(property, declaredClass.get());
    if (refusal != null) {
      recordRetypeGap(sheet, rowKey, companion.getId(), property, target, refusal);
      return;
    }
    boolean recorded = caseFieldId != null
        ? plannedRetypes.recordRootField(
            ownerFqnOf(property, declaredClass.get()), property.memberName, caseFieldId,
            new RetrofitPlannedRetypes.Retype(target, companion.getId()))
        : plannedRetypes.recordMember(
            ownerFqnOf(property, declaredClass.get()), property.memberName,
            new RetrofitPlannedRetypes.Retype(target, companion.getId()));
    if (!recorded) {
      // Another definition type already claimed this member (a root CaseData field that is also a
      // member of a complex type bound to the root class). Recording first and editing only on success
      // is what keeps the plan and the rendered declaration from naming two different companions.
      return;
    }
    editsFor(byFile, property.ownerFile).retype(property.memberName, target);
  }

  /**
   * Why re-declaring {@code property} would not compile, or null when the edit is safe.
   */
  private String retypeRefusal(ResolvedProperty property, ModelSourceIndex.Type declaredClass) {
    String accessorSuffix = capitalise(property.memberName);
    Set<String> called = index.calledMethodNames();
    if (called.contains("get" + accessorSuffix) || called.contains("set" + accessorSuffix)) {
      return "has accessors called from the model source (get/set" + accessorSuffix + "), which the "
          + "retype changes the type of — every caller assigning the old "
          + declaredClass.simpleName + " would stop compiling. Re-declare the field and update its "
          + "callers by hand";
    }
    // A Lombok @Builder setter is named after the FIELD, with no get/set prefix for the check above to
    // catch: fpl calls .respondents(respondentsInCase) on an OtherApplicationsBundle builder, typed on
    // the field the retype re-declares ("List<Element<Respondent>> cannot be converted to
    // List<Element<RespondentNew>>"). Same name-only matching, and conservative in the same direction.
    if (called.contains(property.memberName)) {
      return "is set through a builder method named after it (." + property.memberName + "(…)), which "
          + "the retype changes the parameter type of — every caller passing the old "
          + declaredClass.simpleName + " would stop compiling. Re-declare the field and update its "
          + "callers by hand";
    }
    Optional<ModelSourceIndex.Type> owner = ownerType(property);
    if (owner.isEmpty()) {
      return null; // no parsed owner to inspect; the declaration edit itself stands alone
    }
    ModelSourceIndex.Type ownerClass = owner.get();
    // A hand-written method in the declaring class reaches the field directly, with no accessor to
    // intercept — fpl's CaseData.getOrders() returns the retyped ordersSolicitor as an Orders, and
    // HearingDocuments passes caseSummaryListLA into a generic defaultIfNull(…) inferred from it.
    if (index.referencesFieldDirectly(ownerClass, property.memberName)) {
      return "is read directly by hand-written code in " + ownerClass.simpleName
          + ", which the retype changes the type of — that code would stop compiling against the old "
          + declaredClass.simpleName + ". Re-declare the field and update it by hand";
    }
    // The same field name declared TWICE in one extends hierarchy: Lombok generates an accessor pair
    // per declaration, one overriding the other, which only compiles while the two share a type.
    // Retyping one leaves ET's "getReferralCollection() in CaseData cannot override
    // getReferralCollection() in BaseCaseData" (and the setters clash on erasure).
    Optional<ModelSourceIndex.Type> shadowed =
        index.shadowedFieldDeclaration(ownerClass, property.memberName);
    if (shadowed.isPresent()) {
      return "is declared on both " + ownerClass.simpleName + " and " + shadowed.get().simpleName
          + " in one class hierarchy, where Lombok generates an overriding accessor pair per "
          + "declaration — retyping one alone makes the override incompatible. Re-declare BOTH fields "
          + "and update their callers by hand";
    }
    // Compared through retypeTarget's own descent, not on the parameter's raw name: sscs's NotePad
    // declares 'List<AppealNote> notesCollection' and assigns it from a @JsonCreator parameter typed
    // 'List<Note>', whose raw name is List. Retyping only the field leaves the assignment
    // 'List<Note> cannot be converted to List<AppealNote>'.
    boolean boundByConstructor = ownerClass.decl.getConstructors().stream()
        .flatMap(ctor -> ctor.getParameters().stream())
        .anyMatch(p -> p.getNameAsString().equals(property.memberName)
            && retypeTarget(p.getType()) instanceof ClassOrInterfaceType t
            && t.getNameAsString().equals(declaredClass.simpleName));
    if (boundByConstructor) {
      return "is assigned by a hand-written constructor parameter naming "
          + declaredClass.simpleName + " on " + ownerClass.simpleName
          + ", which the retype would no longer match. Re-declare the field and widen that "
          + "constructor by hand";
    }
    if (index.hasPositionalConstructorCall(ownerClass)) {
      return "is declared on " + ownerClass.simpleName + ", which is instantiated positionally — the "
          + "retype changes one all-args constructor parameter's type and breaks that call. "
          + "Re-declare the field and update the call site by hand";
    }
    if (index.hasSubtypeWithExplicitSuperCall(ownerClass)) {
      return "is declared on " + ownerClass.simpleName + ", whose all-args constructor a subclass "
          + "calls positionally via super(...) — the retype changes one parameter's type and breaks "
          + "that call. Re-declare the field and update the subclass by hand";
    }
    return null;
  }

  /**
   * The parsed class a resolved property was declared on, matched by simple name and confirmed by the
   * file it was resolved from so a same-named class elsewhere cannot stand in for it.
   */
  private Optional<ModelSourceIndex.Type> ownerType(ResolvedProperty property) {
    return index.bySimpleName(property.ownerSimpleName, modelPackage)
        .filter(t -> t.file.equals(property.ownerFile));
  }

  /**
   * The FQN the member walk will look this property's owner up by: the parsed owner's own FQN when it
   * is resolvable, else the declaring class's package with the resolved owner simple name — the graph
   * keys on {@code ModelSourceIndex.Type.fqn}, so a mismatch here silently loses the plan.
   */
  private String ownerFqnOf(ResolvedProperty property, ModelSourceIndex.Type declaredClass) {
    return ownerType(property)
        .map(t -> t.fqn)
        .orElse(declaredClass.packageName + "." + property.ownerSimpleName);
  }

  /**
   * The generated companion's Java simple name, derived exactly as {@code ComplexTypeEmitter} does.
   */
  private static String companionSimpleName(ComplexTypeModel companion) {
    return companion.getJavaClassName() != null && !companion.getJavaClassName().isEmpty()
        ? companion.getJavaClassName()
        : companion.getId();
  }

  /**
   * Records a retype refusal, to be reported as a gap only if {@link #withComplexCompanion} does not
   * then cover it by NAMING the companion on the field's {@code @CCD} — a fallback that changes no
   * declaration and so has none of the retype's refusals, and which reproduces both the type's rows and
   * this column's type ID. Flushed by {@link #reportUncoveredRetypeRefusals()} once every annotation
   * claim has been made, so the report describes what the patch actually did.
   */
  private void recordRetypeGap(String sheet, String rowKey, String definitionId,
      ResolvedProperty property, String target, String reason) {
    refusedRetypes.putIfAbsent(refusedRetypeKey(property),
        new RefusedRetype(sheet, rowKey, definitionId, property, target, reason));
  }

  /** Reports every retype refusal the companion-naming fallback did not cover. */
  private void reportUncoveredRetypeRefusals() {
    for (Map.Entry<String, RefusedRetype> entry : refusedRetypes.entrySet()) {
      if (companionNamedFields.contains(entry.getKey())) {
        continue;
      }
      RefusedRetype refused = entry.getValue();
      recordRetypeGapEntry(refused.sheet(), refused.rowKey(), refused.definitionId(),
          refused.property(), refused.target(), refused.reason());
    }
  }

  private void recordRetypeGapEntry(String sheet, String rowKey, String definitionId,
      ResolvedProperty property, String target, String reason) {
    gaps.add(GapEntry.builder()
        .sheet(sheet)
        .rowKey(rowKey)
        .column(Columns.FIELD_TYPE)
        .value(definitionId)
        .category(GapCategory.UNSUPPORTED_VALUE)
        .action(GapAction.MANUAL_PLACEMENT)
        .detail("Definition complex type '" + definitionId + "' has no model class, so it is emitted "
            + "as the generated companion " + target + "; but " + property.ownerSimpleName + "."
            + property.memberName + " " + reason + ". Until then the SDK emits this field's FieldType "
            + "as its declared class and the definition's '" + definitionId + "' rows have no "
            + "counterpart.")
        .build());
  }

  private static String capitalise(String value) {
    return value.isEmpty() ? value
        : Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  /**
   * Pins an explicit {@code @JsonProperty} on every field the {@code CaseEventToComplexTypes} member
   * walk resolved under an id the SDK would not derive — a class-level {@code @JsonNaming} strategy's
   * name for the field, or a {@code @JsonProperty} on the matching {@code @JsonCreator} constructor
   * parameter — carrying the id the walk itself matched.
   *
   * <p>Without this the config would emit {@code Address::getAddressLine1} while the SDK, which reads
   * {@code @JsonProperty} only off the field and the read method, regenerated the CCD id
   * {@code addressLine1} — silently changing the field id rather than failing to compile. The pin is a
   * Jackson no-op (a field-level {@code @JsonProperty} already overrides both idioms, and the value
   * pinned is the one they produce) and makes the SDK derive the definition's id. The full argument, and
   * why reliance and pin are recorded as one decision, is in {@link RetrofitPinnedNames}.
   *
   * <p>Nothing is pinned speculatively: only names an actual resolved member walk depended on appear
   * here, so a renaming class no {@code CaseEventToComplexTypes} row reaches is left untouched.
   */
  private void planPinnedNames(Map<Path, FileEdits> byFile) {
    for (String ownerFqn : pinnedNames.ownerFqns()) {
      Optional<ModelSourceIndex.Type> owner = index.byFqn(ownerFqn);
      if (owner.isEmpty()) {
        continue;
      }
      ModelSourceIndex.Type type = owner.get();
      if (carriesAnUnevaluableNamingStrategy(type)) {
        // A team-written @JsonNaming class is arbitrary Java the converter cannot evaluate, so it
        // cannot know what this class currently serialises the field as — and a field-level
        // @JsonProperty OVERRIDES the class strategy, so pinning here could change the runtime payload
        // rather than being the no-op every pin is required to be. Refused independently of the graph,
        // which also declines to resolve such a member (RetrofitEventComplexTypeGraph): neither half
        // may start guessing alone.
        continue;
      }
      // The id itself comes from the walk, NOT from re-deriving the idiom here: the walk resolves some
      // members through a class @JsonNaming and others through a @JsonCreator parameter's own
      // @JsonProperty, and re-deriving the strategy silently pinned nothing at all for the latter.
      pinnedNames.idsFor(ownerFqn)
          .forEach((javaName, id) -> editsFor(byFile, type.file).pinName(javaName, id));
    }
  }

  /**
   * Plans the class-level {@code @ComplexType} that pins a definition complex type's own ID onto the
   * model class(es) backing it, so the SDK emits the type under the ID the definition uses rather than
   * under the class's Java simple name.
   *
   * <p>Binding a definition type to an existing class fixes the type's <em>members</em> but not its
   * <em>ID</em>: {@code ComplexTypeGenerator} names the emitted type {@code c.getSimpleName()} unless
   * the class carries {@code @ComplexType(name)}. A CCD ComplexTypes ID is routinely camelCase
   * ({@code appeal}, {@code name}, {@code correspondence}) while the class is PascalCase — the very
   * divergence {@link ModelSourceIndex#complexTypeClass}'s case-insensitive fallback exists to bridge —
   * so every such type was emitted under a name the definition never mentions. The definition's own
   * rows then had no counterpart and the generated rows no home: measured on sscs, 354 ComplexTypes
   * diff lines and 54 {@code CaseField} {@code FieldType} lines ({@code expected <appeal> but was
   * <Appeal>}) are exactly this.
   *
   * <p>Two classes may need annotating, because CCD serialises collection elements as
   * {@code {id, value}} and so addresses a collection element type's members on its value class:
   * <ul>
   *   <li>the <b>value</b> class carries {@code (name = <id>, generate = true)} — its members ARE the
   *       definition's rows, so it must emit them under the definition's ID. {@code generate = true} is
   *       mandatory: the attribute defaults to {@code false} and both {@code ComplexTypeGenerator} and
   *       {@code FixedListGenerator} skip a named-but-not-generate type entirely;</li>
   *   <li>the <b>wrapper</b> class, when the value class was reached by unwrapping one, carries
   *       {@code (name = <id>, generate = false)}. The definition never declares the wrapper — CCD's
   *       element envelope is implicit — yet {@code ConfigResolver} registers a non-generic wrapper as a
   *       complex type in its own right (unlike {@code List<ListValue<X>>}, whose generic element it
   *       descends through), emitting a spurious {@code {value}} row. {@code generate = false}
   *       suppresses that row while {@code name} still gives {@code resolveCollectionType} the right
   *       {@code FieldTypeParameter} for every {@code List<Wrapper>} field.</li>
   * </ul>
   *
   * <p>Refused, leaving the class exactly as it is today, when:
   * <ul>
   *   <li>the class's simple name already IS the definition ID (nothing to pin — and no diff churn);</li>
   *   <li>the class already carries a {@code @ComplexType} (a team's own, or this patch re-applied:
   *       every patch op is required to be idempotent);</li>
   *   <li>the definition ALSO declares a complex type whose ID is exactly the class's simple name —
   *       pinning would rename the type out from under that other definition row;</li>
   *   <li>a second definition type already claimed this class (the one-class-many-IDs collision: sscs
   *       backs ten {@code dwp*DocumentCT} types with a single {@code DwpResponseDocument}). Only one ID
   *       can win, so rather than pick silently the collision is reported as a gap.</li>
   * </ul>
   */
  private void planComplexTypeId(Map<Path, FileEdits> byFile, String definitionId,
      ModelSourceIndex.Type boundClass, ModelSourceIndex.Type valueClass) {
    if (definitionId == null || definitionId.isEmpty()) {
      return;
    }
    // Renaming the value class is only needed when its Java name is not already the definition ID —
    // and only meaningful when no other definition row owns that Java name.
    if (!definitionId.equals(valueClass.simpleName)
        && !definitionComplexTypeIds.contains(valueClass.simpleName)) {
      pinComplexTypeId(byFile, definitionId, valueClass, true);
    }
    // The wrapper is suppressed whatever it is called, because the definition has no row for CCD's
    // implicit element envelope — so unlike the rename above, name equality is beside the point.
    if (!valueClass.fqn.equals(boundClass.fqn)) {
      pinComplexTypeId(byFile, definitionId, boundClass, false);
    }
  }

  /**
   * Records the {@code @ComplexType(name = definitionId, generate = generate)} to add to one class,
   * applying the refusals documented on {@link #planComplexTypeId}.
   */
  private void pinComplexTypeId(Map<Path, FileEdits> byFile, String definitionId,
      ModelSourceIndex.Type type, boolean generate) {
    if (Annotations.has(type.decl, "ComplexType")) {
      return; // team-written, or this patch already applied
    }
    ComplexTypeIdPlan planned = new ComplexTypeIdPlan(definitionId, generate);
    ComplexTypeIdPlan existing = complexTypeIdPins.putIfAbsent(type.fqn, planned);
    if (existing == null) {
      editsFor(byFile, type.file).nameComplexType(type.simpleName, planned);
      return;
    }
    if (!existing.equals(planned)) {
      gaps.add(GapEntry.builder()
          .sheet("ComplexTypes")
          .rowKey(definitionId)
          .column("ID")
          .value(definitionId)
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.MANUAL_PLACEMENT)
          .detail("Complex types '" + existing.definitionId() + "' and '" + definitionId
              + "' both bind to " + type.simpleName + ", which can carry only one @ComplexType(name);"
              + " give each definition type its own model class or a per-field typeParameterOverride")
          .build());
    }
  }

  /**
   * Suppresses the {@code ComplexTypes} rows of every model class the SDK's reachability walk reaches
   * but the definition never declares a {@code ComplexTypes} ID for.
   *
   * <p>Step 3 pins each definition complex type onto the model class that binds it, so those types
   * emit their rows under the definition's own ID. But the walk reaches more than the definition
   * declares: a team's own copy of a type the definition store knows natively (sscs declares no
   * {@code ComplexTypes} rows for its {@code DocumentLink}, {@code DynamicList}, {@code CaseLink} …
   * because the importer resolves those built-in), and the {@code {id, value}} envelope class of every
   * collection (the definition declares the element type; CCD's collection envelope is implicit). The
   * SDK knows neither, so {@code ComplexTypeGenerator} emits a full set of rows for each under its
   * Java simple name — rows the input has no counterpart for, and the largest single group of sscs's
   * residual diff.
   *
   * <p>{@code @ComplexType(generate = false)} is exactly the lever for this, and a NAME-LESS one is
   * inert everywhere else: of the five SDK sites reading the annotation, three
   * ({@code CaseFieldGenerator}'s {@code FieldType} / collection {@code FieldTypeParameter} overrides
   * and the {@code FixedList} list ID) are guarded on a non-empty {@code name()} or apply to enums
   * only, and {@code FixedListGenerator} likewise. {@code CaseFieldGenerator.referencedTypeParameters}
   * deliberately still walks a {@code generate = false} class, so its members' fixed lists keep their
   * {@code FixedLists} rows.
   *
   * <p>Only suppression is ever planned here, never a rename: a class the definition does not declare
   * has no ID to be named after. Where a definition ID DOES exist for the class, step 3's pin already
   * holds the {@code complexTypeIdPins} slot and {@link #pinComplexTypeId} leaves it alone.
   */
  private void planSuppressedComplexTypes(
      Map<Path, FileEdits> byFile, RetrofitInheritedMembers inherited) {
    if (rootType == null) {
      return;
    }
    // The fields step 2/3 decided to ignore leave the SDK's walk too, so a class only such a field
    // names is not reachable in the patched model and needs no suppression.
    Set<String> ignoredMembers = new LinkedHashSet<>();
    for (RetrofitInheritedMembers.Decision decision : inherited.decisions()) {
      if (decision.base().isIgnore()) {
        ignoredMembers.add(decision.base().ownerFqn() + "#" + decision.base().memberName());
      }
    }
    for (ModelSourceIndex.Type reachable
        : new RetrofitReachableTypes(index).from(rootType, ignoredMembers)) {
      if (!reachable.isClass() || reachable.fqn.equals(rootType.fqn)) {
        continue;
      }
      if (declaresComplexTypeId(reachable) || complexTypeIdPins.containsKey(reachable.fqn)) {
        // A definition type binds here, or step 3 already claimed the class for one under another
        // name — either way its rows are accounted for and the ID pin must stand.
        continue;
      }
      // Null ID: a suppression, not a rename — the renderer emits a name-less @ComplexType.
      pinComplexTypeId(byFile, null, reachable, false);
    }
  }

  /**
   * Whether the definition declares a {@code ComplexTypes} ID this class would emit its rows under —
   * matched case-insensitively because that is how {@link ModelSourceIndex#complexTypeClass} binds an
   * ID to a class, so a case-only difference is the same type by the linker's own reckoning.
   *
   * <p>An ID the linker DROPPED is not a declaration: an orphan complex type nothing reachable
   * references is removed from the model (and forgiven on the expected side), so a class matching only
   * such an ID still emits rows nothing accounts for. Testing membership of the live
   * {@code definitionComplexTypeIds} — populated from the linked model, post-drop — is what makes
   * sscs's {@code HearingRecordingDetails} a plain suppression rather than a spurious rename onto the
   * orphan {@code hearingRecordingDetails}.
   */
  private boolean declaresComplexTypeId(ModelSourceIndex.Type type) {
    for (String id : definitionComplexTypeIds) {
      if (id.equalsIgnoreCase(type.simpleName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Pins each definition {@code FixedLists} ID onto the model enum its referencing field is declared as,
   * for the IDs no name match reaches — probate's {@code handoffReasonFixedList} against the model's
   * {@code HandoffReasonId}, {@code enablementTypeFixedList} against
   * {@code ParagraphDetailEnablementType}.
   *
   * <p>The same {@code @ComplexType(name = <id>, generate = true)} the complex-type pin uses, because
   * {@code FixedListGenerator} reads the list ID from exactly that annotation, falling back to the
   * enum's simple name — the identical divergence, on the identical mechanism. {@code generate = true}
   * is as mandatory here as there: the attribute defaults to false and the generator skips a
   * named-but-not-generate type entirely.
   *
   * <p>Without the pin the team's enum emits its rows under its Java name (an ID the definition never
   * mentions) while the definition's own rows are answered by a generated companion enum that the retype
   * usually cannot point the field at — so the definition ID gets no rows at all. Pinning fixes both
   * sides at once, and {@link RetrofitModelRebinder} drops the companion for a pinned ID so only one
   * enum ever carries it.
   */
  private void planFixedListIds(Map<Path, FileEdits> byFile) {
    for (Map.Entry<String, ModelSourceIndex.Type> binding : declaredTypeBindings.entrySet()) {
      ModelSourceIndex.Type type = binding.getValue();
      if (!type.isEnum()) {
        continue; // a complex-type binding, already pinned by planComplexTypeMembers
      }
      pinComplexTypeId(byFile, binding.getKey(), type, true);
    }
  }

  /**
   * Pins the definition's own {@code ListElement} and {@code ListElementCode} onto each constant of every
   * model enum backing a {@code FixedLists} ID — the label as {@code @CCD(label = …)}, the code as
   * {@code @JsonProperty}.
   *
   * <p>Covers both ways an enum comes to back a list: the name match (the enum's simple name IS the ID,
   * which is most of them — prl's {@code PartyEnum}, {@code Gender}) and the declared-type binding
   * {@link RetrofitTypeBinder} established for the IDs no name reaches. Either way the enum emits the
   * list's rows, so either way its constants must carry the labels and codes.
   *
   * <p>A name match is resolved to EVERY enum of that name rather than to one of them, because the SDK
   * reflects whichever twin its reachability walk arrives at and the two are indistinguishable from here
   * — see {@link #backingEnums}.
   *
   * <p>Runs AFTER {@link #planFixedListIds} so a bound enum is already pinned to its ID; the constant pins
   * are independent of that annotation (they sit on the constants, not the type) but the two must agree
   * on WHICH list an enum serves, so both resolve it through the same lookup order. See
   * {@link RetrofitFixedListLabels} for why both values are copied from the definition rather than read
   * off whatever accessors the team's enum happens to carry, and for why the code pin — unlike the label
   * — changes how the team's own type serialises.
   */
  private void planFixedListLabels(Map<Path, FileEdits> byFile) {
    for (FixedListModel list : definitionFixedLists) {
      // Every enum that can emit this ID's rows, not just the one a tie-break picks: which twin the SDK
      // reflects is a property of the case-data graph, and a pin on the twin it does not reach leaves the
      // list's labels exactly as divergent as before. See #backingEnums.
      for (ModelSourceIndex.Type type : backingEnums(list.getId())) {
        Map<String, String> pins = RetrofitFixedListLabels.pins(type, list);
        if (!pins.isEmpty()) {
          Map<String, List<String>> members = new LinkedHashMap<>();
          pins.forEach((constant, label) ->
              members.put(constant, List.of("label = " + CcdAnnotationRenderer.quote(label))));
          editsFor(byFile, type.file).annotateConstants(type.simpleName, members);
        }
        Map<String, String> codes = RetrofitFixedListLabels.codePins(type, list);
        if (!codes.isEmpty()) {
          editsFor(byFile, type.file).pinConstantCodes(type.simpleName, codes);
        }
        List<RetrofitFixedListLabels.AddedConstant> added =
            RetrofitFixedListLabels.constantsToAdd(type, list);
        if (!added.isEmpty()) {
          editsFor(byFile, type.file).addConstants(type.simpleName, added);
        }
      }
    }
  }

  /**
   * Pins the definition's own {@code State} sheet {@code Name}/{@code TitleDisplay}/{@code Description}
   * onto each constant of the team's reused State enum, as
   * {@code @CCD(label = …, hint = …, description = …)} — and {@code @CCD(ignore = true)} on each constant
   * the definition has no state row for at all.
   *
   * <p>Only ever runs for an enum the conversion actually REUSES as the case type's State: the
   * {@code retrofitStateConstants} map is installed by {@link RetrofitConverter} on exactly that
   * decision (every definition state ID resolves), and is empty when the converter generates a fresh
   * State enum instead — which carries these same three columns itself, emitted by {@code EnumEmitter}.
   * So the two can never both claim a state's labels.
   *
   * <p>Runs BEFORE {@link #planFixedListLabels}: a State enum is frequently also reachable as a
   * declared field type, so both passes want the same constant, and a constant carries at most one
   * {@code @CCD} ({@code @CCD} is not {@code @Repeatable}). First write wins per constant and this pass
   * takes it — the State sheet's three columns are always compared. See {@link RetrofitStateLabels}.
   */
  private void planStateLabels(Map<Path, FileEdits> byFile) {
    if (stateConstantsByStateId.isEmpty() || model.getStates() == null) {
      return;
    }
    ModelSourceIndex.Type type = stateEnum;
    if (type == null) {
      return;
    }
    Map<String, List<String>> pins =
        RetrofitStateLabels.pins(type, model.getStates(), stateConstantsByStateId);
    if (!pins.isEmpty()) {
      editsFor(byFile, type.file).annotateConstants(type.simpleName, pins);
    }
    // The other half of the same divergence: a constant the definition has NO state row for emits a
    // State row the definition never had, because StateGenerator emits one per constant with no filter.
    // The team's own code switches on those constants so they cannot be deleted — @CCD(ignore = true) is
    // how the constant declares it contributes nothing to the definition.
    Map<String, List<String>> ignored =
        RetrofitStateLabels.ignorePins(type, model.getStates(), stateConstantsByStateId);
    if (!ignored.isEmpty()) {
      editsFor(byFile, type.file).annotateConstants(type.simpleName, ignored);
    }
  }

  /**
   * Names the companion enum behind a field's {@code typeParameterOverride} as
   * {@code @CCD(typeParameterClass)}, when nothing in the model declares that type.
   *
   * <p>A {@code typeParameterOverride} only writes the {@code FieldTypeParameter} column, while the
   * {@code FixedLists} rows come from the types reachable by reflection from the case-data class. So a
   * field left as {@code String} — which is how a team really models a large reference-data list, and
   * which the SDK cannot infer a list from — referenced a list whose rows nothing generated. Naming the
   * companion makes it reachable without retyping the field, so no caller or serialised payload in the
   * team's model changes.
   *
   * <p>Names the team's OWN enum in preference to a companion when one serves the ID, and otherwise only
   * ever names a companion that IS emitted. A list absent from the definition's own set gets nothing
   * rather than a guess at a class name.
   */
  private FieldModel withTypeParameterClass(FieldModel field, ResolvedProperty property) {
    return withComplexCompanion(withBackingEnum(withCompanionOverride(field, property), property),
        property);
  }

  /**
   * Names the generated companion CLASS behind a field's definition complex type as
   * {@code @CCD(typeParameterClass)}, for a field whose {@link #planRetype re-declaration} as that
   * companion was refused.
   *
   * <p>The retype is the primary fix and stays so: it makes the companion the field's actual type, which
   * is what the SDK reflects. But it is refused wherever rewriting the declaration would not compile —
   * a caller of the accessors, a constructor parameter bound to the old type, a positional instantiation
   * — and until now a refusal left the definition type with no counterpart at all: the companion was
   * emitted and referenced by nothing, while the declared class emitted rows under its own ID.
   *
   * <p>Naming the companion covers exactly that case. {@code typeParameterClass} makes the class part of
   * the definition (complex-type resolution walks it like a declared type, so it emits its
   * {@code ComplexTypes} rows) and {@code CaseFieldGenerator.resolveFieldType} reads its
   * {@code @ComplexType(name)} as this column's {@code FieldType} — while the field's declared type, and
   * so every caller and serialised payload, is untouched. sscs's {@code JointParty.name} is the case:
   * declared {@code Name} (the model class for the definition's own four-member {@code name} type) but
   * addressed by the definition's three-member {@code jointPartyName}, whose {@code title} is a
   * {@code FixedList} where {@code name}'s is {@code Text}. One class cannot carry both IDs, and
   * {@code RoboticsJsonMapper} calls {@code getName()}, so the retype is rightly refused.
   *
   * <p>Skipped when the field already carries a {@code typeParameterClass} (the FixedList paths above
   * name an enum on the same member, and only one class fits), when the retype was NOT refused — a
   * retyped field needs nothing, its declared type IS the companion — and when the field carries a
   * {@code typeOverride}, which short-circuits type resolution entirely
   * ({@code CaseFieldGenerator.populateFieldMetadata} returns before it reads the named class), so the
   * annotation would be inert and the refusal is still the honest thing to report. A COLLECTION field's
   * override is the exception the SDK makes too: there the named class is the element type, supplying the
   * {@code FieldTypeParameter} the override already writes, and the {@code FieldType} stays
   * {@code Collection}.
   */
  private FieldModel withComplexCompanion(FieldModel field, ResolvedProperty property) {
    if (property == null || field.getTypeParameterClassName() != null) {
      return field;
    }
    if (field.getTypeOverride() != null && !field.getTypeOverride().isEmpty()
        && !"Collection".equals(field.getTypeOverride())) {
      return field;
    }
    RefusedRetype refused = refusedRetypes.get(refusedRetypeKey(property));
    if (refused == null) {
      return field;
    }
    companionNamedFields.add(refusedRetypeKey(property));
    return field.toBuilder().typeParameterClassName(refused.target()).build();
  }

  /** The key a retype refusal and its covering annotation are matched on: the field itself. */
  private static String refusedRetypeKey(ResolvedProperty property) {
    return property.ownerFile + "#" + property.memberName;
  }

  private FieldModel withBackingEnum(FieldModel field, ResolvedProperty property) {
    String listId = field.getTypeParameterOverride();
    if (listId == null || listId.isEmpty() || field.getTypeParameterClassName() != null) {
      return field;
    }
    ModelSourceIndex.Type backing = backingEnum(listId);
    if (backing != null) {
      // A model enum serves this ID. Naming it is what makes it REACHABLE when THIS field does not
      // declare it: reflection reaches an enum only from a field's declared type, and sscs really spells
      // the column `private String type` with typeParameterOverride = "ScannedDocumentType" while the
      // real 14-constant enum sits unreferenced in …ccd.callback, so nothing generated the list's rows.
      // A field that declares the enum needs nothing — the annotation would be redundant noise on a
      // model the team reads.
      //
      // The ID the SDK then emits the list under agrees with this field's own typeParameterOverride
      // either way: backingEnum resolves the ID to a same-named enum, or to a declared binding whose ID
      // planFixedListIds pins onto the enum with @ComplexType(name).
      if (declares(property, backing)) {
        return field;
      }
      // Only when the enum can be made to emit the definition's own ListElementCodes: a constant is
      // pinned to its code with @JsonProperty where the team spells it in its own house style (sscs's
      // CHERISHED for the definition's `cherished`), but an enum whose codes no pin can reach — a
      // @JsonValue takes precedence over the pin, a code has no constant at all — would emit a list of
      // WRONG rows where today it emits none. See RetrofitFixedListLabels#canEmitTheDefinitionsCodes.
      boolean reproducesTheList = RetrofitFixedListLabels.byId(definitionFixedLists, listId)
          .filter(list -> RetrofitFixedListLabels.canEmitTheDefinitionsCodes(backing, list))
          .isPresent();
      if (!reproducesTheList) {
        return field;
      }
      return field.toBuilder()
          .typeParameterClassName(backing.simpleName)
          .typeParameterClassPackage(backing.packageName)
          .build();
    }
    // Otherwise name a companion, and only when one is actually emitted for the ID. RetrofitModelRebinder
    // drops a list whose ID names an existing top-level model type (fpl's HearingVenue is a @Data address
    // class, not an enum) or which binds by declaration — in both cases no companion is generated, and
    // naming one would emit a @CCD referencing a class that does not exist.
    if (index.hasTopLevelType(listId) || declaredTypeBindings.containsKey(listId)) {
      return field;
    }
    return definitionFixedLists.stream()
        .filter(list -> listId.equals(list.getId()))
        .findFirst()
        .map(list -> field.toBuilder().typeParameterClassName(list.getJavaClassName()).build())
        .orElse(field);
  }

  /**
   * Points a field at the companion enum for its list when the field DECLARES a team enum that cannot
   * serve the list — the enum the binder refused because it declares more constants than the definition
   * has codes.
   *
   * <p>Without this the refusal only half-lands. A {@code FixedRadioList} field whose declared type is an
   * enum needs no {@code typeParameterOverride} to round-trip normally — the SDK derives the
   * {@code FieldTypeParameter} from the declared enum itself ({@code CaseFieldGenerator.resolveSimpleType})
   * — so the linker emits none, and there is no override for {@link #withTypeParameterClass} to attach a
   * class to. The result is the worst of both: the companion holding the definition's real codes is
   * emitted but referenced by nothing (so contributes no rows), while the team's enum still emits a full
   * set of rows under its own Java name. sscs's {@code HmcHearingType} — 3 constants against the 2-code
   * {@code FL_hmcHearingType} — showed exactly that, its residual rising rather than falling.
   *
   * <p>So both halves are written explicitly: the {@code typeParameterOverride} names the definition's
   * list ID, and {@code typeParameterClass} names the companion that carries its codes. The field's own
   * declared type is left alone, so no caller or serialised payload changes; only what the generator
   * reads for this column's list does.
   *
   * <p>Scoped to exactly the refusal that creates the situation: the field declares an enum, the
   * definition has a list of that {@code FieldTypeParameter}, no binding was made for it, and a companion
   * IS emitted for the ID. Where the team's enum does serve the list it keeps serving it, pinned by
   * {@link #planFixedListIds} as before.
   */
  private FieldModel withCompanionOverride(FieldModel field, ResolvedProperty property) {
    String listId = field.getFieldTypeParameter();
    if (listId == null || listId.isEmpty()
        || field.getTypeParameterOverride() != null
        || field.getTypeParameterClassName() != null
        || declaredTypeBindings.containsKey(listId)) {
      return field;
    }
    // Only when the field's own declared type is the enum that would otherwise answer the list, and the
    // definition really declares that list. A companion is emitted for the ID precisely because no
    // binding claimed it — the rebinder's drop test — and its Java name is the linker's.
    ModelSourceIndex.Type declared = declaredEnum(property);
    if (declared == null || index.hasTopLevelType(listId)) {
      return field;
    }
    // The question is whether the declared enum SERVES this list ID — not whether it happens to
    // reproduce the list's codes. Those are different questions, and asking the second one left the
    // definition's rows with no counterpart at all wherever the answers diverged.
    //
    // An enum serves the ID only if the SDK will emit the list off it, which needs the ID pinned onto it:
    // either a declared-type binding (returned above) or the ID being the enum's own simple name (the
    // hasTopLevelType return above). Neither holds here, so nothing can make the declared enum answer
    // for this list — whatever its constants are. Meanwhile a companion IS generated for the ID, on
    // exactly the rebinder's drop test this path already mirrors.
    //
    // Testing reproduction instead assumed the only reason a list reaches here unbound is the binder's
    // superset refusal. It is not: the binder also refuses when the enum is claimed by ANOTHER
    // definition ID (Civil's ComplexityBand is named by five separate lists — ComplexityBand,
    // FastTrackComplexityBand, FinalOrdersIntermediateComplexityBand, ComplexityBandIntermediate,
    // IntermediateComplexityBand — all with the same BAND_1..BAND_4 codes, and one enum can carry one
    // @ComplexType(name)); when two referencing fields declare different enums (PaymentTypeList, read
    // from DJPaymentTypeSelection and PaymentType); and when two IDs claim one enum (TrialReadyList and
    // GAHearingScheduleGAspec both read from YesOrNo). In each of those the enum reproduces the codes
    // EXACTLY, so the old filter dropped the override — and the companion carrying the definition's
    // rows was emitted referenced by nothing, contributing no rows, while the definition's own rows had
    // no counterpart. 23 diff lines across seven Civil lists, all of the same shape.
    //
    // The two guards above are what establish that no enum serves the ID, and they are exactly the two
    // ways {@link #backingEnum} resolves one: the {@code declaredTypeBindings} early return covers a
    // declared-type binding, and {@code hasTopLevelType} covers the ID naming a type itself. So reaching
    // this point IS "no enum serves this list", and no further test on the declared enum's contents can
    // add anything.
    return RetrofitFixedListLabels.byId(definitionFixedLists, listId)
        .map(list -> field.toBuilder()
            .typeParameterOverride(listId)
            .typeParameterClassName(list.getJavaClassName())
            .build())
        .orElse(field);
  }

  /**
   * The model enum a resolved property's declaration names, or null when it declares no enum in the
   * parsed model. Read through the same token descent {@link #declares} uses.
   */
  private ModelSourceIndex.Type declaredEnum(ResolvedProperty property) {
    if (property == null) {
      return null;
    }
    com.github.javaparser.ast.type.Type token =
        RetrofitTypeTokens.elementToken(property.declaredType);
    if (!(token instanceof ClassOrInterfaceType cit)) {
      return null;
    }
    return index.resolve(property.context, cit)
        .filter(ModelSourceIndex.Type::isEnum)
        .orElse(null);
  }

  /**
   * Whether a field's own declaration names a type, descended to the token CCD addresses the definition
   * type on — the same descent {@link RetrofitTypeBinder} reads a declaration through, so the two cannot
   * disagree about what a field declares. A field the definition matched to nothing has no declaration to
   * read, so it declares nothing.
   */
  private boolean declares(ResolvedProperty property, ModelSourceIndex.Type type) {
    if (property == null) {
      return false;
    }
    com.github.javaparser.ast.type.Type token =
        RetrofitTypeTokens.elementToken(property.declaredType);
    if (!(token instanceof ClassOrInterfaceType cit)) {
      return false;
    }
    return index.resolve(property.context, cit)
        .filter(declared -> declared.fqn.equals(type.fqn))
        .isPresent();
  }

  /**
   * The model enum backing a definition {@code FixedLists} ID: the declared-type binding when there is
   * one, else the enum whose simple name is the ID itself.
   */
  private ModelSourceIndex.Type backingEnum(String id) {
    ModelSourceIndex.Type bound = declaredTypeBindings.get(id);
    if (bound != null && bound.isEnum()) {
      return bound;
    }
    return index.bySimpleName(id, modelPackage)
        .filter(ModelSourceIndex.Type::isEnum)
        .orElse(null);
  }

  /**
   * EVERY model enum that can emit a definition {@code FixedLists} ID's rows: the declared-type binding
   * when there is one, else every enum whose simple name IS the ID.
   *
   * <p>{@link #backingEnum} answers with the ONE enum the rest of the emitter reasons about — the type a
   * retype points a field at, the type an ID is pinned onto — and for that a single answer is required,
   * since only one class can take a name. The constant pins are the opposite case: they annotate
   * declarations rather than choose between them, and the enum whose annotations actually reach the
   * definition is the one the SDK's reachability walk arrives at, which depends on the case-data graph
   * rather than on any lookup here. Where a name is shared, one answer is therefore a guess, and a wrong
   * guess writes every label of the list onto a type that emits no rows. See
   * {@link ModelSourceIndex#enumsBySimpleName} for the five Civil pairs and why annotating a twin the SDK
   * never reflects costs nothing.
   *
   * <p>A declared binding is exempt because it is not a guess: the ID is pinned with
   * {@code @ComplexType(name)} onto exactly that enum, so that enum — and no twin of it — emits the
   * list's rows whatever the walk reaches.
   */
  private List<ModelSourceIndex.Type> backingEnums(String id) {
    ModelSourceIndex.Type bound = declaredTypeBindings.get(id);
    if (bound != null && bound.isEnum()) {
      return List.of(bound);
    }
    return index.enumsBySimpleName(id);
  }

  /**
   * Whether a class carries a {@code @JsonNaming} the converter cannot statically evaluate — a
   * team-written strategy class rather than one of Jackson's own.
   */
  private static boolean carriesAnUnevaluableNamingStrategy(ModelSourceIndex.Type type) {
    return Annotations.has(type.decl, "JsonNaming") && NamingStrategy.of(type).isEmpty();
  }

  /**
   * Records every definition {@code ComplexTypes} ID, and which of them have no model class and so are
   * emitted as generated companions — the same lookup {@link #planComplexTypeMembers} skips on, so the
   * companion set is exactly its complement.
   *
   * <p>Split out and run first because BOTH the retype plan and the annotation claims read it: a retype
   * points a field's declaration at its companion, and a field whose retype is refused instead NAMES the
   * companion in its {@code @CCD}. Neither decision can be taken on a partially-filled set.
   */
  private void indexDefinitionComplexTypes() {
    for (ComplexTypeModel complexType : model.getComplexTypes()) {
      definitionComplexTypeIds.add(complexType.getId());
      if (boundClass(complexType.getId()).isEmpty()) {
        companionComplexTypes.put(complexType.getId(), complexType);
      }
    }
  }

  private void planComplexTypeMembers(Map<Path, FileEdits> byFile,
      RetrofitInheritedMembers inherited) {
    // Prefer a complex-type class in the team's model package; the root class's package is the
    // anchor (e.g. uk.gov.hmcts.reform.civil.model). Falling back to null hint would let a
    // same-named type in an unrelated package win.
    String modelPackage = rootType != null ? rootType.packageName : null;
    indexDefinitionComplexTypes();
    for (ComplexTypeModel complexType : model.getComplexTypes()) {
      Optional<ModelSourceIndex.Type> type = boundClass(complexType.getId());
      if (type.isEmpty()) {
        // No top-level model CLASS for this definition complex type (absent, or only a nested/
        // interface type shares the name — e.g. Civil's Hearing interface nested in the sealed
        // CaseDataPredicate): it is emitted as a fresh generated class in the companion sources
        // (see the retrofit companion emitter), not patched here.
        continue;
      }
      // A definition complex type used as a Collection's element type addresses its members on the
      // element's VALUE class, because CCD serialises every collection element as {id, value}: sscs's
      // 'Bundle' ComplexTypes rows (title, documents, stitchStatus, …) describe BundleDetails, while
      // the model's Bundle is a hand-rolled wrapper declaring only `BundleDetails value`. Annotating
      // the wrapper made every one of those members look definition-only, so they were routed to
      // synthesis and then REFUSED by the wrapper's @Value + single-arg @JsonCreator idiom — 111
      // members across 22 sscs classes reported as "add the field by hand" when the fields already
      // existed on the *Details class. Unwrapping here targets the same class the
      // CaseEventToComplexTypes member walk already targets (RetrofitEventComplexTypeGraph), so the
      // two agree and the members are annotated in place instead.
      ModelSourceIndex.Type complexClass = unwrapper.unwrap(type.get());
      // The class BINDS to this definition type, but the SDK derives the emitted ComplexTypes ID from
      // the class's Java simple name — so a bound class whose name differs from the definition ID emits
      // the type under the wrong ID. Pin the ID with a class-level @ComplexType(name).
      planComplexTypeId(byFile, complexType.getId(), type.get(), complexClass);
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
          FieldModel reconciled =
              withTypeParameterClass(reconciler.reconcile(member, property), property);
          // A pinned hint does not stay on this ComplexTypes row: the SDK cascades it onto every
          // CaseEventToComplexTypes row that PLACES the member, unless the placement overrides it. The
          // linker chooses between cascade / .hintText(v) / .noHintText() by comparing the event row's
          // HintText against the member's declared hint, so it must compare against the hint this patch
          // is about to pin rather than the one the source currently reads — see RetrofitPlannedHints.
          plannedHints.record(property.ownerFqn, property.memberName, reconciled.getHint());
          inherited.annotate(property, reconciled, renameFor(property, reconciled));
        }
      }
      // Unmatched Java members of the complex class → ignore.
      for (ResolvedProperty property : memberResolution.properties.values()) {
        if (!definedMembers.contains(property.ccdId)) {
          inherited.ignore(property);
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
        // A constructor-bound idiom (a builder bound to a hand-written constructor, or a @Value class
        // whose constructor must initialise every final field) does not have to be refused: widening
        // that constructor with the synthesised fields as trailing parameters keeps the builder
        // binding valid and initialises the new final fields. Verified against Lombok 1.18.38 — a
        // @Value @Builder(toBuilder = true) class with an EXTENDED @JsonCreator constructor compiles,
        // and builder()/toBuilder() both set the added field.
        if (unsafeReason != null) {
          unsafeReason = repairConstructors(byFile, complexClass, synthesised);
        }
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
          // Case-INSENSITIVE-collision renaming (probate's TTL/ttl) is applied only on the root
          // CaseData paths, where the rebinder mirrors the rename so the config's typed getter matches
          // the renamed member; no real lane has a complex-member case-insensitive collision. The
          // exact-name reconciliation below does rename (sscs's Party.confidentialityRequiredChangedDate
          // pinned to a different id), and that rename is safe here because every getter reference to a
          // synthesised complex member comes from plannedSynthesis.record just below — which is fed the
          // placeable list, renames and all.
          List<FieldModel> placeable =
              dropExistingFieldCollisions(byFile, inherited, complexClass, synthesised);
          editsFor(byFile, complexClass.file).synthesise(complexClass.simpleName, placeable);
          if (synthesisedFieldsNeedNonNull(complexClass.decl)) {
            editsFor(byFile, complexClass.file).includeSynthesisedWhenNonNull();
          }
          // The patch adds these as real fields, so after it is applied the member IS addressable as
          // <complexClass>::get<JavaName>. Record them so the CaseEventToComplexTypes member walk —
          // which reads the model as PARSED, i.e. pre-patch — resolves them instead of dropping the
          // row to a verbatim passthrough (civil: 939 → 700 fallback rows). Recorded only here, where
          // the emitter has COMMITTED to adding the field: the refusal branch above and
          // dropExistingFieldCollisions both exclude members, and a graph that resolved those would
          // emit a getter reference to a field the patch never adds.
          for (FieldModel member : placeable) {
            plannedSynthesis.record(complexClass.fqn, member);
          }
        }
      }
    }
  }

  /**
   * Plans whatever constructor edits let the synthesised fields be added to {@code complexClass}
   * after all, returning null once the class is safe to patch or a gap reason when it is not.
   *
   * <p>Two independent repairs, either or both of which a class may need:
   * <ul>
   *   <li><b>Widening</b> — every hand-written constructor gains the synthesised fields as trailing
   *       parameters plus a NARROW delegating overload of its original signature. That keeps a
   *       {@code @Builder} bound to the constructor valid, initialises a {@code @Value} class's new
   *       final field, and leaves existing positional call sites (including a subclass's
   *       {@code super(...)}) binding to the overload.</li>
   *   <li><b>A narrow all-args constructor</b> — when the class's all-args constructor is
   *       LOMBOK-GENERATED and a subclass calls it positionally via {@code super(...)}, that generated
   *       constructor silently grows with the new field and the subclass's fixed-arity call loses its
   *       target. There is no source constructor to widen, so the patch adds one: an explicit
   *       constructor over the pre-synthesis field list delegating {@code this(<fields>, null…)}
   *       (civil's {@code FixedRecoverableCosts}, whose {@code FixedRecoverableCostsSection} calls
   *       {@code super(5 args)}). Verified against Lombok 1.18.38: the narrow constructor coexists with
   *       an explicit {@code @AllArgsConstructor}, the unchanged subclass binds to it, and
   *       {@code builder()}/{@code toBuilder()} still set every field.</li>
   * </ul>
   *
   * <p>The narrow all-args repair requires the {@code @AllArgsConstructor} to be EXPLICIT. A class
   * that only carries {@code @Builder}/{@code @Value} gets its all-args constructor by INFERENCE, and
   * Lombok infers one only while the class declares no constructor at all — adding the narrow one
   * suppresses it and the generated builder no longer compiles (verified: {@code constructor
   * BuilderOnly … required: String,String found: String,String,String}). Such a class stays a gap.
   */
  private String repairConstructors(
      Map<Path, FileEdits> byFile, ModelSourceIndex.Type complexClass,
      List<FieldModel> synthesised) {
    boolean widenable = !complexClass.decl.getConstructors().isEmpty();
    boolean needsNarrowAllArgs = generatesAllArgsConstructor(complexClass)
        && index.hasSubtypeWithExplicitSuperCall(complexClass);

    NarrowAllArgsPlan narrowAllArgs = null;
    if (needsNarrowAllArgs) {
      if (!hasTypeAnnotation(complexClass.decl, "AllArgsConstructor")) {
        return "whose INFERRED all-args constructor (from @Builder/@Value, with no explicit "
            + "@AllArgsConstructor) a subclass calls positionally via super(...); appending a field "
            + "widens it, and adding a narrow constructor to bind that call would suppress Lombok's "
            + "inference and break the builder. Add the field and update the subclass by hand.";
      }
      narrowAllArgs = planNarrowAllArgsConstructor(complexClass, synthesised);
      if (narrowAllArgs == null) {
        return "whose Lombok all-args constructor a subclass calls positionally via super(...), and "
            + "whose pre-synthesis field list cannot be expressed as an explicit constructor. Add the "
            + "field and update the subclass constructor by hand.";
      }
    }
    if (!widenable && narrowAllArgs == null) {
      return null;
    }

    String collision = constructorRepairCollision(complexClass, synthesised, narrowAllArgs);
    if (collision != null) {
      return collision;
    }
    FileEdits edits = editsFor(byFile, complexClass.file);
    if (widenable) {
      edits.extendConstructors(complexClass.simpleName);
    }
    if (narrowAllArgs != null) {
      edits.addNarrowAllArgs(narrowAllArgs);
    }
    return null;
  }

  /**
   * The explicit constructor to add over {@code complexClass}'s PRE-synthesis field list, or null when
   * the class has no such list to express.
   *
   * <p>Mirrors what Lombok's {@code @AllArgsConstructor} itself generates: one parameter per
   * non-static field in declaration order, skipping an initialised {@code final} field (Lombok never
   * takes a parameter for one). Because the synthesised fields are appended at the END of the class
   * body, the class's existing fields ARE the pre-synthesis list, so the delegation passes {@code null}
   * for each synthesised field at the tail.
   *
   * <p>Null (so the class stays a gap) when the field list is empty — the narrow constructor would be a
   * no-arg one, clashing with the {@code @NoArgsConstructor} these classes carry — or when
   * {@code @AllArgsConstructor} names an explicit {@code access} level, since guessing the visibility
   * of the constructor a subclass binds to is not a safe edit.
   */
  private static NarrowAllArgsPlan planNarrowAllArgsConstructor(
      ModelSourceIndex.Type complexClass, List<FieldModel> synthesised) {
    boolean customAccess = complexClass.decl.getAnnotations().stream()
        .filter(a -> a.getNameAsString().endsWith("AllArgsConstructor"))
        .anyMatch(a -> a.toString().contains("access"));
    if (customAccess) {
      return null;
    }
    List<String> params = new ArrayList<>();
    List<String> args = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (FieldDeclaration field : complexClass.decl.getFields()) {
      if (field.isStatic()) {
        continue;
      }
      for (var variable : field.getVariables()) {
        if (field.isFinal() && variable.getInitializer().isPresent()) {
          continue;
        }
        String type = variable.getType().asString();
        types.add(simpleTypeName(type));
        params.add(type + " " + variable.getNameAsString());
        args.add(variable.getNameAsString());
      }
    }
    if (params.isEmpty()) {
      return null;
    }
    synthesised.forEach(field -> args.add("null"));
    // Indent the added constructor like the class's own members, and its body one level deeper —
    // deriving the width from the first field's column keeps the team's indentation (prl/sscs 4, SDK 2).
    String indent = complexClass.decl.getFields().stream().findFirst()
        .flatMap(FieldDeclaration::getBegin)
        .map(p -> " ".repeat(p.column - 1))
        .orElse("  ");
    return new NarrowAllArgsPlan(
        complexClass.simpleName, params, args, String.join(",", types), indent);
  }

  /**
   * A human-readable reason why appending a synthesised field to {@code complexClass} would break its
   * compilation, or null when synthesis is safe.
   *
   * <p>Every reason returned here is passed through {@link #repairConstructors}, which widens the
   * hand-written constructors and/or adds a narrow all-args constructor so the field can be synthesised
   * after all. Only two shapes survive to the gap report:
   * <ul>
   *   <li>a class whose all-args constructor a subclass calls via {@code super(...)} but whose
   *       all-args form is INFERRED from {@code @Builder}/{@code @Value} rather than declared with
   *       {@code @AllArgsConstructor} — the narrow constructor that would bind that call suppresses the
   *       inference and breaks the builder;</li>
   *   <li>a class where the repair's narrow constructors would collide with each other or with an
   *       existing signature ({@link #constructorRepairCollision}).</li>
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
    if (forcesFieldsFinal(complexClass) && !complexClass.decl.getConstructors().isEmpty()) {
      // A @Value class makes EVERY field private final — including the synthesised one — so a
      // hand-written explicit constructor that does not assign it leaves it "might not have been
      // initialized" (Civil's Bundle: @Value + a @JsonCreator ctor that only sets its declared field).
      // Route to manual placement rather than synthesising an uninitialisable final field.
      //
      // A class that merely DECLARES some fields final (fpl's RespondentParty/ChildParty: @Data with
      // `private final` members and a constructor-level @Builder) is NOT this case: the synthesised
      // field is emitted non-final, so it compiles and is set via the Lombok setter/left null —
      // verified against Lombok. Only @Value (which forces the new field final too) is unsafe.
      return "which is a @Value class whose hand-written constructor would not initialise the "
          + "synthesised final field. Add the field and update the constructor by hand.";
    }
    if (generatesAllArgsConstructor(complexClass)
        && index.hasSubtypeWithExplicitSuperCall(complexClass)) {
      // Repaired by repairConstructors in the PARENT file (a narrow all-args constructor the
      // unchanged subclass binds to), except for the inferred-@Builder shape it refuses by name.
      return "whose Lombok all-args constructor a subclass calls positionally via super(...); "
          + "appending a field would widen that constructor and leave the subclass's super(...) call "
          + "with no matching constructor. Add the field and update the subclass constructor by hand.";
    }
    return null;
  }

  /**
   * Whether a class forces <em>every</em> field {@code private final}, so a synthesised (non-final)
   * field would itself become final and need constructor initialisation. That is Lombok
   * {@code @Value} (which makes all fields final); it is NOT a {@code @Data} class that merely
   * declares some individual fields {@code final} (fpl's {@code RespondentParty}/{@code ChildParty}),
   * because there the synthesised field stays non-final and compiles/sets fine — the over-broad
   * "any final field" test wrongly routed those complex-type members to a gap, dropping every
   * definition-only member of {@code RespondentParty}/{@code ChildParty}/{@code Solicitor}/etc.
   */
  private static boolean forcesFieldsFinal(ModelSourceIndex.Type target) {
    return hasTypeAnnotation(target.decl, "Value");
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
   * Whether fields synthesised onto {@code decl} must each carry their own
   * {@code @JsonInclude(NON_NULL)}: true when the class carries a MARKER {@code @JsonInclude} — the
   * annotation with no value, which means {@code ALWAYS} and so serialises nulls.
   *
   * <p>Without this, synthesis changes the class's wire payload. A definition-only field is by
   * definition one the team's code never populates, so on an ALWAYS class every instance gains a
   * {@code "<id>": null} property. On a published library like sscs-common that is a breaking change
   * to every consumer's JSON — and it is observable in the team's own tests (sscs-common's
   * {@code should_deserialise_and_serialise} asserts a deserialise/serialise round trip against
   * fixture JSON, which fails with {@code Expected: pcqId but none found} once {@code Appellant},
   * {@code Appointee}, {@code Representative} and {@code OverrideFields} each gain a null property).
   *
   * <p>A class-level {@code @JsonInclude(NON_NULL)} / {@code NON_ABSENT} / {@code NON_EMPTY} already
   * suppresses the null, so it needs nothing; a per-field annotation would be redundant. The CCD
   * definition is unaffected either way — the SDK derives {@code CaseField} rows from FIELDS, and
   * reads no Jackson inclusion setting.
   */
  private static boolean synthesisedFieldsNeedNonNull(TypeDeclaration<?> decl) {
    return decl.getAnnotations().stream()
        .filter(a -> a.getNameAsString().equals("JsonInclude")
            || a.getNameAsString().endsWith(".JsonInclude"))
        .anyMatch(AnnotationExpr::isMarkerAnnotationExpr);
  }

  /**
   * Resolves the synthesise list against the names the target class (or a superclass in the parsed
   * source) already declares. Synthesis fills the definition-only gap — fields the resolver could NOT
   * bind to a model property — but a field can be unresolved yet still <em>declared</em>: a
   * {@code @JsonUnwrapped} parent (prl's {@code CaseData.allegationOfHarm}, whose leaves are resolved
   * through it, so the parent itself is never a leaf property) or a {@code @JsonProperty}-renamed
   * member whose CCD id the definition also lists as a separate field (prl's {@code Court.courtName}).
   * Re-declaring the name produces {@code variable X is already defined} (the prl compile break,
   * finding B1).
   *
   * <p>Where the declared member pins a DIFFERENT id with its own {@code @JsonProperty}, the two are
   * distinct CCD members and {@link SynthesisPlacement#reconcileDeclaredNames} renames the synthesised
   * one instead of dropping it — sscs's {@code Party.confidentialityRequiredChangedDate}, which
   * serialises as {@code confidentialityRequiredConfirmedDate}.
   *
   * <p>Where the declared member provably IS the definition member — its own {@code @JsonProperty}, or
   * one on its {@code @JsonCreator} parameter, states exactly the definition's id — the field is ADOPTED:
   * it receives the definition's {@code @CCD} (and the {@code @JsonProperty} pin the SDK needs when the
   * id came from the creator parameter) rather than the {@code @CCD(ignore = true)} an unmatched Java
   * field would get. Civil's {@code GAHearingDetails} is why: eleven of its members were routed to
   * synthesis because their PascalCase definition ids resolve to no field, dropped here on the camelCase
   * name clash, and then marked ignored by the unmatched-Java pass — so the class ended up carrying
   * {@code @JsonProperty("HearingPreferencesPreferredType") @CCD(ignore = true)} on the very field the
   * definition's row needed, and the row had no counterpart. Adoption is the annotate-the-existing-member
   * treatment the gap text used to ask the operator to perform by hand, taken automatically wherever the
   * identity can be proved; {@link SynthesisPlacement#reconcileDeclaredNames} documents the guard and why
   * anything weaker keeps being dropped.
   *
   * <p>Everything else is still skipped with a gap so the drop is visible — the existing member already
   * carries the data; the field just needs {@code @CCD} the operator can add (mirrors the Civil
   * PascalCase-collision fix, generalised from resolved to <em>declared</em> members).
   *
   * @param byFile the per-file edits, so a class touched only by an adoption still reaches the renderer
   * @param inherited the claim collector the adoptions are recorded into; the caller must not yet have
   *     settled its decisions, or an adoption would be committed after the annotation it must outrank
   * @param target the class the fields would be synthesised onto
   * @param synthesised the definition-only fields to place
   * @return the fields still to synthesise
   */
  private List<FieldModel> dropExistingFieldCollisions(Map<Path, FileEdits> byFile,
      RetrofitInheritedMembers inherited, ModelSourceIndex.Type target,
      List<FieldModel> synthesised) {
    SynthesisPlacement.DeclaredNameCollisions resolved =
        placement.reconcileDeclaredNames(target, synthesised);
    for (SynthesisPlacement.Adoption adoption : resolved.adopted()) {
      adoptExistingMember(byFile, inherited, target, adoption);
    }
    reportDroppedCollisions(target, resolved.dropped());
    return resolved.placeable();
  }

  /**
   * The reconciliation for a target whose claims are already settled, where an adoption can no longer be
   * made: every collision is reported, adoptable or not.
   *
   * <p>Only the constructor-limit borderline host reaches this. Its target class is not chosen until the
   * placement plan runs — which needs the placeable set the reconciliation produces — so the host's own
   * collisions cannot be known before the claims are committed. A collision there is also vanishingly
   * unlikely to be adoptable: the host is a prefix-less {@code @JsonUnwrapped} member's class, whose
   * members flatten into the ROOT's id space, so a definition member colliding with one of its fields
   * would already have resolved as a root property rather than reaching synthesis at all.
   *
   * @param target the class the fields would be synthesised onto
   * @param synthesised the definition-only fields to place
   * @return the fields still to synthesise
   */
  private List<FieldModel> reportExistingFieldCollisions(
      ModelSourceIndex.Type target, List<FieldModel> synthesised) {
    SynthesisPlacement.DeclaredNameCollisions resolved =
        placement.reconcileDeclaredNames(target, synthesised);
    List<FieldModel> unplaced = new ArrayList<>(resolved.dropped());
    resolved.adopted().forEach(adoption -> unplaced.add(adoption.field()));
    reportDroppedCollisions(target, unplaced);
    return resolved.placeable();
  }

  /** Records the skip-and-report gap for each definition member no field could be added for. */
  private void reportDroppedCollisions(ModelSourceIndex.Type target, List<FieldModel> dropped) {
    for (FieldModel field : dropped) {
      gaps.add(GapEntry.builder()
          .sheet("CaseField")
          .rowKey(field.getId())
          .column("FieldType")
          .value(field.getFieldType())
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.OMITTED_FAIL)
          .detail("Definition field '" + field.getId() + "' would be synthesised as member '"
              + field.getJavaName() + "' onto " + target.simpleName + ", which already declares a "
              + "field of that name (e.g. a @JsonUnwrapped parent or a member whose own CCD id this "
              + "cannot tell apart from it); skipped to avoid a duplicate-field compile error. "
              + "Annotate the existing member with @CCD by hand if it should carry this definition "
              + "field's metadata.")
          .build());
    }
  }

  /**
   * Claims the definition's {@code @CCD} onto the field the target class already declares for an adopted
   * member, plus the {@code @JsonProperty} pin the SDK needs to derive the definition's id from it.
   *
   * <p>Routed through {@link RetrofitInheritedMembers} rather than written straight to the file edits,
   * for the same reason every other claim is: the adopted field may be declared on a shared superclass
   * that several complex types reach, and the definition may configure their rows differently — one
   * annotation on the declaration cannot say that, and a class-level {@code @CCD(member = …)} must. This
   * claim also has to be weighed against the {@code @CCD(ignore = true)} the unmatched-Java pass makes
   * about the same declaration through the same class; the "annotate wins" rule there resolves it, which
   * is exactly the contradiction that left Civil's {@code GAHearingDetails} members ignored.
   *
   * <p>The claim is made with {@code reachedThroughFqn} equal to the declaring class's own FQN, because
   * the ownership question adoption answers is per DECLARATION: the id proof came from the field's own
   * annotations (or its declaring class's creator), not from the path the type was reached by.
   *
   * <p>The {@code @JsonProperty} pin is stated unconditionally as the claim's rename, and is the same
   * no-op it is on {@link #planPinnedNames}'s path: the emitter skips it when the field already carries
   * the annotation, and where it does not the value pinned is the one the creator parameter already
   * produces. Without it the SDK — which reads {@code @JsonProperty} only off the field and the read
   * method — would derive the field's own camelCase name and emit the row under an id the definition
   * never mentions, which is a fidelity regression rather than a missing row.
   */
  private void adoptExistingMember(Map<Path, FileEdits> byFile,
      RetrofitInheritedMembers inherited, ModelSourceIndex.Type target,
      SynthesisPlacement.Adoption adoption) {
    ModelSourceIndex.Type owner = adoption.declaringType();
    FieldModel member = adoption.field();
    String memberName = member.getJavaName();
    // A hint pinned here cascades onto every CaseEventToComplexTypes row placing the member, exactly as
    // it does for a resolved member (see RetrofitPlannedHints), so the linker compares the event row's
    // HintText against the hint this patch is about to pin rather than the one the source reads today.
    plannedHints.record(owner.fqn, memberName, member.getHint());
    inherited.annotateDeclared(owner, memberName, member, member.getId());
    // Adoption adds no field, so nothing here needs the synthesis block, the constructor repairs or a
    // plannedSynthesis record — the member walk resolves this member off the parsed source already. The
    // file is registered so a class whose ONLY edit is an adoption still reaches the renderer.
    editsFor(byFile, target.file);
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

    // Collect one insertion (own-line block of annotation text) per field, keyed by the 1-based
    // source line its FIRST token (existing annotation, else modifier/type) begins on — so the
    // block lands directly above that line, below any annotations the field already carries.
    Map<Integer, List<String>> insertionsByLine = new TreeMap<>(Comparator.reverseOrder());
    // Retypes are the one op that rewrites a token on the declaration line itself rather than inserting
    // whole lines above it, so they are collected as (1-based line → replacement of that line) and
    // applied before the insertion pass — which keys on the SAME original line numbers, so rewriting a
    // line in place cannot shift them.
    Map<Integer, String> retypedLines = new TreeMap<>();
    // The companion classes a retype points at, so their imports can be added when the retyped field's
    // own class does not already live in the model package the companions are emitted into.
    Set<String> retypeTargets = new LinkedHashSet<>();
    // Likewise for the enums named by @CCD(typeParameterClass) rather than declared, which need the same
    // cross-package import even though the field's own type is unchanged — simple name → the package it
    // lives in (the model package for a companion, its own for one of the team's enums).
    Map<String, String> typeParameterClasses = new LinkedHashMap<>();
    // Whole source lines to delete, collected from both the class-level and the field-level annotation
    // removals and applied in the single descending pass below.
    Set<Integer> linesToDelete = new java.util.TreeSet<>(Comparator.reverseOrder());
    // The annotation simple names actually deleted, so their now-possibly-unused imports can be
    // stripped (checkstyle's unused-import rule, maxWarnings=0 on the retrofitted teams).
    Set<String> deletedAnnotations = new LinkedHashSet<>();

    for (TypeDeclaration<?> type : unit.getTypes()) {
      for (FieldDeclaration fieldDecl : type.getFields()) {
        String member = fieldDecl.getVariable(0).getNameAsString();
        int pinLine = fieldFirstLine(fieldDecl);
        // Field-level annotation removals: the getter-suppressing @Getter(AccessLevel.NONE) on a
        // @JsonUnwrapped member whose getter a placement resolved through (see
        // RetrofitUnsuppressedGetters). Deletes only a solo annotation line, exactly as the class-level
        // removal below does — every occurrence in the retrofitted models is one, and skipping a shared
        // line would leave the placement referencing a getter Lombok does not generate, so the shape is
        // vetted BEFORE the plan is committed: ModelSourceIndex.suppressionIsSoloOnItsLine applies this
        // same predicate to the same file, and refuses the placement when it does not hold.
        Set<String> removeOnField = edits.removeFieldAnnotations.get(member);
        if (removeOnField != null) {
          for (AnnotationExpr a : fieldDecl.getAnnotations()) {
            String simple = a.getNameAsString();
            simple = simple.contains(".") ? simple.substring(simple.lastIndexOf('.') + 1) : simple;
            if (!removeOnField.contains(simple)) {
              continue;
            }
            int begin = a.getBegin().map(p -> p.line).orElse(-1);
            int end = a.getEnd().map(p -> p.line).orElse(-1);
            if (begin < 1 || begin != end) {
              continue;
            }
            if (sourceLines.get(begin - 1).trim().equals(a.toString())) {
              linesToDelete.add(begin);
              deletedAnnotations.add(simple);
            }
          }
        }
        // Re-declare the field as the generated companion class backing its definition complex type.
        // Planned in planRetypes (which refuses every shape where this would not compile); applied here
        // by replacing exactly the declared type's own token range on its own line, so a comment, an
        // initialiser or a same-named token elsewhere on the line is untouched.
        String retypeTo = edits.retype.get(member);
        if (retypeTo != null
            && replaceDeclaredTypeToken(fieldDecl, retypeTo, sourceLines, retypedLines)) {
          retypeTargets.add(retypeTo);
        }
        // A naming-strategy pin is independent of the @CCD idempotency rule below: it exists so the
        // SDK derives the right CCD id, which an existing @CCD does not supply. It is still skipped
        // when the field already carries a @JsonProperty — that annotation already governs the id
        // (and a re-run must be a no-op).
        String pinId = edits.pinNames.get(member);
        if (pinId != null && !hasAnnotation(fieldDecl, "JsonProperty")) {
          insertionsByLine.computeIfAbsent(pinLine, k -> new ArrayList<>())
              .addAll(indentEachLine(List.of("@JsonProperty(\"" + pinId + "\")"),
                  leadingWhitespace(sourceLines.get(pinLine - 1))));
          needsJsonPropertyImport = true;
        }
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
            if (field.getTypeParameterClassName() != null) {
              // A companion has no package of its own — it is emitted into the model package — while the
              // team's own enum keeps the package it is declared in.
              typeParameterClasses.put(field.getTypeParameterClassName(),
                  field.getTypeParameterClassPackage() == null
                      ? modelPackage : field.getTypeParameterClassPackage());
            }
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

    // Class-level @ComplexType additions (the definition-ID pin): one own-line annotation directly
    // above the type declaration's own first line, below any annotations it already carries — the same
    // by-original-line insertion the field annotations use, so it joins the single descending pass
    // below and every untouched line stays byte-identical.
    boolean needsComplexTypeImport = false;
    if (!edits.nameComplexTypes.isEmpty()) {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        ComplexTypeIdPlan plan = edits.nameComplexTypes.get(type.getNameAsString());
        if (plan == null) {
          continue;
        }
        int typeLine = type.getBegin().map(p -> p.line).orElse(-1);
        if (typeLine < 1) {
          continue;
        }
        // A suppression-only pin carries no definition ID (see planSuppressedComplexTypes) and must
        // NOT name the type: an empty name() is what makes it inert everywhere except
        // ComplexTypeGenerator — CaseFieldGenerator's FieldType/FieldTypeParameter overrides are all
        // guarded on a non-empty name(), so leaving it off keeps every referencing field's type
        // derivation exactly as it is today.
        String pin = plan.definitionId() == null || plan.definitionId().isEmpty()
            ? "@ComplexType(generate = " + plan.generate() + ")"
            : "@ComplexType(name = \"" + plan.definitionId() + "\", generate = "
                + plan.generate() + ")";
        insertionsByLine.computeIfAbsent(typeLine, k -> new ArrayList<>())
            .addAll(indentEachLine(List.of(pin),
                leadingWhitespace(sourceLines.get(typeLine - 1))));
        needsComplexTypeImport = true;
      }
    }

    // Class-level @CCD(member = …) overrides: one per inherited member this class needs configured
    // differently from the field's own declaration, which says one thing for every subclass at once
    // (see RetrofitInheritedMembers and CCD#member()). Placed like the @ComplexType pin — own-line,
    // above the type declaration's own first line — and repeatable, so several stack.
    if (!edits.memberOverrides.isEmpty()) {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        List<MemberOverridePlan> overrides = edits.memberOverrides.get(type.getNameAsString());
        if (overrides == null || overrides.isEmpty()) {
          continue;
        }
        int typeLine = type.getBegin().map(p -> p.line).orElse(-1);
        if (typeLine < 1) {
          continue;
        }
        String indent = leadingWhitespace(sourceLines.get(typeLine - 1));
        List<String> rendered = new ArrayList<>();
        for (MemberOverridePlan plan : overrides) {
          // Idempotency, per member rather than per class: a class may already carry the team's own
          // override for one member and still need one for another.
          if (hasMemberOverride(type, plan.memberName())) {
            continue;
          }
          rendered.add(
              CcdAnnotationRenderer.renderWrapped("CCD", plan.members(), indent.length()));
          needsCcdImport = true;
          needsFieldTypeImport |= plan.usesFieldType();
          accessClasses.addAll(plan.accessClasses());
        }
        if (!rendered.isEmpty()) {
          insertionsByLine.computeIfAbsent(typeLine, k -> new ArrayList<>())
              .addAll(indentEachLine(rendered, indent));
        }
      }
    }

    // Per-enum-constant additions: the @CCD carrying the FixedLists ListElement pin or the State sheet
    // labels, and the @JsonProperty carrying the FixedLists ListElementCode.
    if (!edits.constantAnnotations.isEmpty() || !edits.constantCodes.isEmpty()) {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        String name = type.getNameAsString();
        Map<String, List<String>> labels =
            edits.constantAnnotations.getOrDefault(name, Map.of());
        Map<String, String> codes = edits.constantCodes.getOrDefault(name, Map.of());
        if (!type.isEnumDeclaration() || (labels.isEmpty() && codes.isEmpty())) {
          continue;
        }
        ConstantPins pinned = planConstantPins(type.asEnumDeclaration(), labels, codes, sourceLines,
            insertionsByLine, linesToDelete);
        needsCcdImport |= pinned.ccd();
        needsJsonPropertyImport |= pinned.jsonProperty();
      }
    }

    // Constants ADDED for definition codes the enum models none for, declared after the last existing
    // constant with their own code and label pins.
    if (!edits.addConstants.isEmpty()) {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        List<RetrofitFixedListLabels.AddedConstant> added =
            edits.addConstants.getOrDefault(type.getNameAsString(), List.of());
        if (!type.isEnumDeclaration() || added.isEmpty()) {
          continue;
        }
        ConstantPins pinned = planAddedConstants(type.asEnumDeclaration(), added, sourceLines,
            insertionsByLine, linesToDelete);
        needsCcdImport |= pinned.ccd();
        needsJsonPropertyImport |= pinned.jsonProperty();
      }
    }

    // Class-level annotation removals (the constructor-limit @AllArgsConstructor drop): find each
    // matching top-level annotation that sits alone on its own line and delete that whole line. Only a
    // solo annotation line is removed — an annotation sharing a line with other tokens is left as-is
    // (no such case arises for the @AllArgsConstructor this targets, and skipping keeps the edit safe).
    if (!edits.removeTypeAnnotations.isEmpty()) {
      for (TypeDeclaration<?> type : unit.getTypes()) {
        type.getAnnotations().forEach(a -> {
          String simple = a.getNameAsString();
          simple = simple.contains(".") ? simple.substring(simple.lastIndexOf('.') + 1) : simple;
          if (!edits.removeTypeAnnotations.contains(simple)) {
            return;
          }
          int begin = a.getBegin().map(p -> p.line).orElse(-1);
          int end = a.getEnd().map(p -> p.line).orElse(-1);
          if (begin < 1 || begin != end) {
            return;
          }
          if (sourceLines.get(begin - 1).trim().equals(a.toString())) {
            linesToDelete.add(begin);
            deletedAnnotations.add(simple);
          }
        });
      }
    }

    // Apply inserts and deletes in one strictly-descending pass over 1-based line numbers so an
    // earlier edit never shifts a later, still-to-be-applied line number. A line can be both: a
    // class-level insertion is keyed on the type declaration's own first line, which is its first
    // existing annotation — the very line a removal may target. Deleting before inserting at the same
    // index leaves the added annotation where the removed one was.
    // In-place declaration rewrites first: replacing a line changes no line NUMBER, so every insertion
    // and deletion index collected above still points at the line it was computed from.
    for (Map.Entry<Integer, String> retyped : retypedLines.entrySet()) {
      sourceLines.set(retyped.getKey() - 1, retyped.getValue());
    }
    java.util.NavigableSet<Integer> touched = new java.util.TreeSet<>(Comparator.reverseOrder());
    touched.addAll(insertionsByLine.keySet());
    touched.addAll(linesToDelete);
    for (int line : touched) {
      if (linesToDelete.contains(line)) {
        sourceLines.remove(line - 1);
      }
      List<String> added = insertionsByLine.get(line);
      if (added != null) {
        sourceLines.addAll(line - 1, added);
      }
    }
    String printed = String.join("\n", sourceLines) + (droppedTrailingNewline ? "\n" : "");
    boolean needsJsonUnwrappedImport = false;

    // Synthesised definition-only fields: build one clearly-delimited block and insert it before
    // the target class's closing brace (textually, so the marker comments and indentation are
    // exactly as intended and the diff is one contiguous hunk at the end of the class body).
    Set<String> typeImports = new LinkedHashSet<>();
    boolean needsJsonIncludeImport = false;
    if (!edits.synthesise.isEmpty()) {
      ImportBinder binder = new ImportBinder(existingImports(unit));
      SynthResult synth = renderSynthBlock(edits, binder);
      printed = insertBeforeClassEnd(printed, synth.text);
      needsCcdImport |= synth.usesCcd;
      needsJsonPropertyImport |= synth.usesJsonProperty;
      needsFieldTypeImport |= synth.usesFieldType;
      needsJsonIncludeImport |= synth.usesJsonInclude;
      accessClasses.addAll(synth.accessClasses);
      typeImports.addAll(synth.typeImports);
      // A constructor-bound idiom (builder bound to a hand-written constructor, or @Value whose
      // constructor must initialise every final field) needs its constructor widened to match the
      // fields just added, else the class no longer compiles. Re-parses the printed text so the
      // synthesised fields are in scope and the constructor positions are the post-insert ones.
      if (edits.extendConstructorsOf != null) {
        SynthResult ctorImports = new SynthResult();
        printed = extendConstructors(
            printed, edits.extendConstructorsOf, edits.synthesise, binder, ctorImports);
        needsJsonPropertyImport |= ctorImports.usesJsonProperty;
        typeImports.addAll(ctorImports.typeImports);
      }
      // A class whose all-args constructor is Lombok-generated and called positionally by a subclass
      // gets an explicit narrow constructor over its pre-synthesis field list, so that super(...) call
      // keeps a target once the generated constructor grows.
      if (edits.narrowAllArgs != null) {
        printed = insertBeforeClassEnd(printed, renderNarrowAllArgs(edits.narrowAllArgs));
      }
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

    // A retyped field now names a generated companion class. The companions are emitted into the model
    // package, so a field declared in a SUB-package (or any other package) needs an import for it; a
    // field already in the model package needs none, and adding one there would be an unused-import
    // checkstyle failure in the team's repo.
    String filePackage = unit.getPackageDeclaration()
        .map(p -> p.getNameAsString()).orElse(null);
    if (!retypeTargets.isEmpty() && modelPackage != null && !modelPackage.equals(filePackage)) {
      for (String target : retypeTargets) {
        typeImports.add("import " + modelPackage + "." + target + ";");
      }
    }

    // Same rule for a @CCD(typeParameterClass) reference: a field declared in a different package from the
    // enum named needs the import. Unlike a retype the field's own declared type is untouched — only the
    // annotation names the class.
    for (Map.Entry<String, String> target : typeParameterClasses.entrySet()) {
      if (target.getValue() != null && !target.getValue().equals(filePackage)) {
        typeImports.add("import " + target.getValue() + "." + target.getKey() + ";");
      }
    }

    // Add imports on the printed text (doing it textually keeps the diff minimal and deterministic).
    if (needsComplexTypeImport) {
      typeImports.add("import uk.gov.hmcts.ccd.sdk.api.ComplexType;");
    }
    printed = addImports(printed, needsCcdImport, needsJsonPropertyImport, needsFieldTypeImport,
        needsJsonUnwrappedImport, needsJsonIgnoreImport, needsJsonIncludeImport, accessClasses,
        typeImports);

    // A dropped annotation leaves its import unused; strip it so checkstyle's unused-import rule stays
    // clean (the retrofitted teams run maxWarnings=0). Only remove an import whose type no longer
    // appears anywhere else in the printed source — the last @Getter(AccessLevel.NONE) in a file also
    // takes lombok.Getter with it, but sscs keeps 21 more of them, so both imports rightly stay.
    for (String removed : deletedAnnotations) {
      printed = removeUnusedAnnotationImport(printed, removed);
      // The suppressing annotation's ARGUMENT type goes unused with it: @Getter(AccessLevel.NONE) is
      // the only reference to lombok.AccessLevel in most models.
      for (String argType : ANNOTATION_ARGUMENT_TYPES.getOrDefault(removed, Set.of())) {
        printed = removeUnusedTypeImport(printed, argType);
      }
    }

    if (printed.equals(original)) {
      return null;
    }
    return new RetrofitPatch.FilePatch(relative, original, printed);
  }

  /**
   * Removes the {@code import …<simpleName>;} line for a dropped class-level annotation when the
   * simple name no longer appears as an {@code @}-annotation anywhere in {@code source} — so removing
   * the last {@code @AllArgsConstructor} also removes its now-unused import (checkstyle's unused-import
   * rule, {@code maxWarnings=0} on the retrofitted teams). If the annotation still appears (another
   * type in the file uses it, or it was imported for another reason) the import is kept.
   */
  private static String removeUnusedAnnotationImport(String source, String simpleName) {
    // Look for a real annotation use — an "@Name" whose token is exactly the simple name (not a
    // longer identifier, not inside {@code @Name} javadoc). If any remains, keep the import.
    java.util.regex.Matcher use = java.util.regex.Pattern
        .compile("@" + java.util.regex.Pattern.quote(simpleName) + "\\b").matcher(source);
    while (use.find()) {
      int at = use.start();
      // Skip a javadoc {@code @AllArgsConstructor} reference: the '@' is preceded by "{@code ".
      String before = source.substring(Math.max(0, at - 8), at);
      if (!before.contains("{@code")) {
        return source;
      }
    }
    List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
    lines.removeIf(line -> {
      String t = line.trim();
      return t.startsWith("import ")
          && (t.endsWith("." + simpleName + ";") || t.equals("import " + simpleName + ";"));
    });
    return String.join("\n", lines);
  }

  /**
   * Removes the {@code import …<simpleName>;} line for a type a dropped annotation referenced, when the
   * simple name no longer appears as an identifier anywhere else in {@code source} — so deleting a
   * file's last {@code @Getter(AccessLevel.NONE)} also drops its now-unused {@code lombok.AccessLevel}
   * import. Conservative: any remaining mention (another suppression, an {@code AccessLevel.PRIVATE}
   * elsewhere, a javadoc reference) keeps the import.
   */
  private static String removeUnusedTypeImport(String source, String simpleName) {
    List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
    String importSuffix = "." + simpleName + ";";
    // Scan every non-import line for the identifier; if it still occurs, the import is still needed.
    java.util.regex.Pattern use =
        java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(simpleName) + "\\b");
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("import ") && trimmed.endsWith(importSuffix)) {
        continue;
      }
      if (use.matcher(line).find()) {
        return source;
      }
    }
    lines.removeIf(line -> {
      String t = line.trim();
      return t.startsWith("import ")
          && (t.endsWith(importSuffix) || t.equals("import " + simpleName + ";"));
    });
    return String.join("\n", lines);
  }

  /**
   * Widens every hand-written constructor of {@code targetClass} so it also takes the synthesised
   * fields, keeping a builder bound to that constructor valid and initialising the new final fields
   * of a {@code @Value} class.
   *
   * <p>Each constructor gains one trailing {@code @JsonProperty("<id>") <Type> <name>} parameter per
   * synthesised field and one trailing {@code this.<name> = <name>;} assignment. Both edits are made
   * textually on the already-printed source (the same approach the rest of this emitter uses) so
   * every untouched line stays byte-identical and the diff is minimal:
   * <ul>
   *   <li>the parameter list is extended at the {@code )} that closes it, matching the existing
   *       parameters' own indentation when they are written one-per-line (the sscs idiom) so the
   *       widened list keeps the team's formatting;</li>
   *   <li>the assignments are inserted immediately before the constructor body's closing brace.</li>
   * </ul>
   *
   * <p>Constructors are rewritten in DESCENDING source position so an earlier rewrite never shifts a
   * later, still-to-be-applied offset. A compact constructor that delegates to another via
   * {@code this(...)} is skipped: widening it would leave the delegation short of arguments, and the
   * constructor it delegates to is itself widened, so the field is still initialised.
   */
  private String extendConstructors(
      String printed, String targetClass, List<FieldModel> synthesised, ImportBinder binder,
      SynthResult imports) {
    CompilationUnit unit = parseCompilationUnit(printed, targetClass);
    Optional<ClassOrInterfaceDeclaration> target = unit.getClassByName(targetClass);
    if (target.isEmpty()) {
      return printed;
    }
    List<ConstructorDeclaration> constructors = new ArrayList<>(target.get().getConstructors());
    // Descending by start position: rewrite the last constructor first so earlier offsets hold.
    constructors.sort(Comparator.comparingInt(
        (ConstructorDeclaration c) -> c.getBegin().map(p -> p.line).orElse(0)).reversed());
    for (ConstructorDeclaration ctor : constructors) {
      if (!widensTo(ctor)) {
        continue;
      }
      // Every widened constructor gets its narrow overload: a class whose overloads would collide is
      // refused up front by narrowOverloadCollision, so this is unconditional here.
      printed = widenConstructor(printed, ctor, synthesised, binder, imports, true);
    }
    return printed;
  }

  /**
   * A human-readable reason why this class's constructor repairs cannot all be emitted, or null when
   * they can.
   *
   * <p>The narrow constructors are what keep existing positional call sites compiling, so they are not
   * optional: if one cannot be emitted because the post-repair class would already declare that
   * signature, the class must be refused rather than patched. Suppressing just the narrow constructor
   * would be worse than a compile error — an existing {@code new Address(a, b)} call would silently
   * rebind to the WIDENED one-arg constructor, quietly assigning {@code b} to the synthesised field
   * instead of the second.
   *
   * <p>Three shapes collide: a sibling that delegates via {@code this(...)} keeps its original signature
   * (so re-creating it clashes); the widened form of a shorter sibling can land on exactly the
   * signature a longer sibling's overload would occupy (a two-constructor class whose signatures differ
   * by one parameter of the synthesised field's type); and an added narrow all-args constructor can
   * duplicate a signature an existing or widened constructor already occupies.
   */
  private String constructorRepairCollision(
      ModelSourceIndex.Type complexClass, List<FieldModel> synthesised,
      NarrowAllArgsPlan narrowAllArgs) {
    List<ConstructorDeclaration> constructors = complexClass.decl.getConstructors();
    Set<String> postEditSignatures = new LinkedHashSet<>();
    for (ConstructorDeclaration ctor : constructors) {
      postEditSignatures.add(widensTo(ctor) ? widenedSignature(ctor, synthesised)
          : parameterSignature(ctor));
    }
    for (ConstructorDeclaration ctor : constructors) {
      if (widensTo(ctor) && postEditSignatures.contains(parameterSignature(ctor))) {
        return "whose constructor (" + parameterSignature(ctor) + ") cannot keep a delegating overload "
            + "of its original signature — another constructor would already declare it after "
            + "widening, so existing positional call sites would silently rebind to a different "
            + "constructor. Add the field and update the constructors by hand.";
      }
      if (widensTo(ctor)) {
        postEditSignatures.add(parameterSignature(ctor));
      }
    }
    if (narrowAllArgs != null && postEditSignatures.contains(narrowAllArgs.signature)) {
      return "whose Lombok all-args constructor a subclass calls positionally via super(...), but "
          + "whose narrow replacement (" + narrowAllArgs.signature + ") would duplicate a constructor "
          + "the class already declares. Add the field and update the subclass constructor by hand.";
    }
    return null;
  }

  /**
   * Whether {@link #widenConstructor} will actually widen this constructor — it declines a constructor
   * that delegates to a sibling via {@code this(...)} (the sibling is widened instead) and one with an
   * empty parameter list (nothing to append an argument to).
   */
  private static boolean widensTo(ConstructorDeclaration ctor) {
    return !delegatesToSiblingConstructor(ctor) && !ctor.getParameters().isEmpty();
  }

  /**
   * A constructor's parameter-type list as source strings, used to compare signatures. Source strings
   * rather than resolved erasures: the emitter has no symbol solver, and a conservative
   * false-positive match only costs one skipped narrow overload.
   */
  private static String parameterSignature(ConstructorDeclaration ctor) {
    List<String> types = new ArrayList<>();
    ctor.getParameters().forEach(param -> types.add(simpleTypeName(param.getType().asString())));
    return String.join(",", types);
  }

  /** The signature a widened constructor ends up with: its own parameters plus the synthesised ones. */
  private static String widenedSignature(
      ConstructorDeclaration ctor, List<FieldModel> synthesised) {
    List<String> types = new ArrayList<>();
    ctor.getParameters().forEach(param -> types.add(simpleTypeName(param.getType().asString())));
    synthesised.forEach(
        field -> types.add(simpleTypeName(SyntheticFieldTypes.javaType(field))));
    return String.join(",", types);
  }

  /**
   * A type's package qualifiers stripped, so a synthesised {@code java.time.LocalDate} parameter
   * compares equal to an existing source-level {@code LocalDate} one when checking for a signature
   * clash. Collapsing distinct types that share a simple name is acceptable: the only cost of a false
   * match is one narrow overload not emitted.
   */
  private static String simpleTypeName(String type) {
    StringBuilder out = new StringBuilder();
    int segmentStart = 0;
    for (int i = 0; i <= type.length(); i++) {
      boolean boundary = i == type.length() || !isTypeNameChar(type.charAt(i));
      if (boundary) {
        String segment = type.substring(segmentStart, i);
        int lastDot = segment.lastIndexOf('.');
        out.append(lastDot < 0 ? segment : segment.substring(lastDot + 1));
        if (i < type.length() && type.charAt(i) != ' ') {
          out.append(type.charAt(i));
        }
        segmentStart = i + 1;
      }
    }
    return out.toString();
  }

  private static boolean isTypeNameChar(char c) {
    return Character.isJavaIdentifierPart(c) || c == '.';
  }

  /**
   * Whether a constructor's first statement delegates to a sibling constructor ({@code this(...)}).
   * Such a constructor must NOT be widened: the sibling it delegates to is widened instead, and
   * adding parameters here would make the {@code this(...)} call short of arguments.
   */
  private static boolean delegatesToSiblingConstructor(ConstructorDeclaration ctor) {
    return ctor.getBody().getStatements().stream().findFirst()
        .map(first -> first.isExplicitConstructorInvocationStmt()
            && first.asExplicitConstructorInvocationStmt().isThis())
        .orElse(false);
  }

  /**
   * Appends the synthesised fields to one constructor's parameter list and assignment body, returning
   * the rewritten source. Offsets are taken from the parsed positions of the last parameter and the
   * body's closing brace, so the edit touches only those two points.
   *
   * <p>A NARROW delegating overload preserving the original signature is added alongside, so every
   * existing positional {@code new <Class>(...)} call site still compiles. That matters twice over:
   * prl's own tests construct these classes positionally ({@code new WithoutNoticeOrderDetails(Yes)}),
   * and a retrofitted model may be a PUBLISHED library (sscs-common) whose callers are not even in the
   * parsed source, so no scan of this repo could prove the widened arity is unused. Verified against
   * Lombok 1.18.38: with two constructors present, {@code @Builder}/{@code @Jacksonized} bind to the
   * {@code @JsonCreator}-annotated one, so {@code builder()}/{@code toBuilder()} still set the added
   * fields, and the narrow overload passes {@code null} for them.
   */
  private String widenConstructor(
      String printed, ConstructorDeclaration ctor, List<FieldModel> synthesised,
      ImportBinder binder, SynthResult imports, boolean addNarrowOverload) {
    if (ctor.getParameters().isEmpty()) {
      // Nothing to widen: a no-arg constructor cannot carry the field, and adding an assignment
      // would reference a parameter that does not exist.
      return printed;
    }
    List<String> lines = new ArrayList<>(Arrays.asList(printed.split("\n", -1)));
    Optional<com.github.javaparser.Position> bodyEnd = ctor.getBody().getEnd();
    if (bodyEnd.isEmpty()) {
      return printed;
    }
    int closeLine = bodyEnd.get().line;

    // 1. The narrow delegating overload, inserted AFTER the constructor's closing brace — the latest
    //    of the three edit points, so applying it first leaves every earlier offset valid.
    if (addNarrowOverload) {
      lines.addAll(closeLine, renderNarrowOverload(ctor, synthesised, lines, closeLine));
    }

    // 2. Assignments, inserted before the body's closing brace (before the parameter list edit: it
    //    is later in the file, so applying it first leaves the parameter offsets valid).
    String bodyIndent = ctor.getBody().getStatements().stream().findFirst()
        .flatMap(s -> s.getBegin())
        .map(p -> " ".repeat(p.column - 1))
        .orElse(leadingWhitespace(lines.get(closeLine - 1)) + "    ");
    List<String> assignments = new ArrayList<>();
    for (FieldModel field : synthesised) {
      String name = field.getJavaName();
      assignments.add(bodyIndent + "this." + name + " = " + name + ";");
    }
    lines.addAll(closeLine - 1, assignments);

    // 3. Parameters, appended at the ')' that closes the parameter list.
    var lastParam = ctor.getParameter(ctor.getParameters().size() - 1);
    Optional<com.github.javaparser.Position> paramEnd = lastParam.getEnd();
    Optional<com.github.javaparser.Position> paramStart = lastParam.getBegin();
    if (paramEnd.isEmpty() || paramStart.isEmpty()) {
      return String.join("\n", lines);
    }
    // One-per-line parameter lists (the sscs @JsonProperty idiom) keep that shape: continue at the
    // column the existing parameters start on. A single-line list stays on its line.
    boolean onePerLine = ctor.getParameters().size() > 1
        && !ctor.getParameter(0).getBegin().map(p -> p.line)
            .equals(paramStart.map(p -> p.line));
    String paramIndent = " ".repeat(paramStart.get().column - 1);
    List<String> rendered = new ArrayList<>();
    for (FieldModel field : synthesised) {
      String javaType = bindTypeReferences(SyntheticFieldTypes.javaType(field), binder, imports);
      rendered.add("@JsonProperty(\"" + field.getId() + "\") " + javaType + " "
          + field.getJavaName());
    }
    imports.usesJsonProperty = true;
    int lastParamLine = paramEnd.get().line;
    String line = lines.get(lastParamLine - 1);
    int insertAt = paramEnd.get().column;
    String head = line.substring(0, insertAt);
    String tail = line.substring(insertAt);
    if (onePerLine) {
      List<String> replacement = new ArrayList<>();
      replacement.add(head + ",");
      for (int i = 0; i < rendered.size(); i++) {
        boolean last = i == rendered.size() - 1;
        replacement.add(paramIndent + rendered.get(i) + (last ? tail : ","));
      }
      lines.remove(lastParamLine - 1);
      lines.addAll(lastParamLine - 1, replacement);
    } else {
      lines.set(lastParamLine - 1, head + ", " + String.join(", ", rendered) + tail);
    }
    return String.join("\n", lines);
  }

  /**
   * The narrow delegating overload for one widened constructor: the ORIGINAL parameter list, a body
   * that forwards to the widened constructor passing {@code null} for each synthesised field, and a
   * comment saying why it exists.
   *
   * <p>It carries no annotations. {@code @JsonCreator} must stay on exactly one constructor (the
   * widened one, so Jackson and Lombok's {@code @Builder} both bind there), and {@code @JsonProperty}
   * on the parameters of a non-creator constructor is meaningless. {@code null} is always a legal
   * argument because every type {@link SyntheticFieldTypes} declares is a reference type.
   */
  private List<String> renderNarrowOverload(
      ConstructorDeclaration ctor, List<FieldModel> synthesised, List<String> lines, int closeLine) {
    List<String> params = new ArrayList<>();
    List<String> args = new ArrayList<>();
    ctor.getParameters().forEach(param -> {
      params.add(param.getType().asString() + " " + param.getNameAsString());
      args.add(param.getNameAsString());
    });
    synthesised.forEach(field -> args.add("null"));
    String access = ctor.getAccessSpecifier().asString();
    String signature = (access.isEmpty() ? "" : access + " ")
        + ctor.getNameAsString() + "(" + String.join(", ", params) + ") {";
    String indent = leadingWhitespace(lines.get(closeLine - 1));
    List<String> out = new ArrayList<>();
    out.add("");
    out.add(indent + "/** Retained so existing positional call sites still compile. */");
    if ((indent + signature).length() <= MAX_EMITTED_LINE) {
      out.add(indent + signature);
    } else {
      // Too long for one line: one parameter per line, aligned under the '(' as the team's own
      // multi-line constructors are.
      String open = (access.isEmpty() ? "" : access + " ") + ctor.getNameAsString() + "(";
      String continuation = indent + " ".repeat(open.length());
      for (int i = 0; i < params.size(); i++) {
        String prefix = i == 0 ? indent + open : continuation;
        String suffix = i == params.size() - 1 ? ") {" : ",";
        out.add(prefix + params.get(i) + suffix);
      }
    }
    // Delegate at the same column the widened constructor's own statements sit at, so the overload
    // matches the team's indentation width (prl indents 4, sscs 4, the SDK 2).
    String bodyIndent = ctor.getBody().getStatements().stream().findFirst()
        .flatMap(s -> s.getBegin())
        .map(p -> " ".repeat(p.column - 1))
        .orElse(indent + "  ");
    out.add(bodyIndent + "this(" + String.join(", ", args) + ");");
    out.add(indent + "}");
    return out;
  }

  /**
   * Renders the added narrow all-args constructor: the class's pre-synthesis parameter list delegating
   * {@code this(<those args>, null…)} to the constructor Lombok now generates over the widened field
   * list.
   *
   * <p>It carries no annotations. {@code @AllArgsConstructor} stays on the class and Jackson keeps
   * binding through the builder / setters, so annotating this one would only add a second creator.
   * {@code null} is always a legal argument because every type {@link SyntheticFieldTypes} declares is
   * a reference type.
   */
  private static String renderNarrowAllArgs(NarrowAllArgsPlan plan) {
    String indent = plan.indent;
    String open = "public " + plan.className + "(";
    List<String> out = new ArrayList<>();
    out.add(indent + "/** Retained so a subclass's positional super(...) call still binds. */");
    String signature = open + String.join(", ", plan.params) + ") {";
    if ((indent + signature).length() <= MAX_EMITTED_LINE) {
      out.add(indent + signature);
    } else {
      // Too long for one line: one parameter per line, aligned under the '(' as the team's own
      // multi-line constructors are.
      String continuation = indent + " ".repeat(open.length());
      for (int i = 0; i < plan.params.size(); i++) {
        String prefix = i == 0 ? indent + open : continuation;
        String suffix = i == plan.params.size() - 1 ? ") {" : ",";
        out.add(prefix + plan.params.get(i) + suffix);
      }
    }
    out.add(indent + indent + "this(" + String.join(", ", plan.args) + ");");
    out.add(indent + "}");
    return String.join("\n", out) + "\n";
  }

  private SynthResult renderSynthBlock(FileEdits edits, ImportBinder binder) {
    SynthResult fields =
        renderSynthFields(edits.synthesise, "  ", binder, edits.synthesisedNeedsNonNull);
    SynthResult wrapped = new SynthResult();
    wrapped.text = "  " + SYNTH_BEGIN + '\n' + fields.text + "  " + SYNTH_END + '\n';
    wrapped.usesCcd = fields.usesCcd;
    wrapped.usesJsonProperty = fields.usesJsonProperty;
    wrapped.usesFieldType = fields.usesFieldType;
    wrapped.usesJsonInclude = fields.usesJsonInclude;
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
   *
   * <p>When {@code nonNull} is set each field additionally carries {@code @JsonInclude(NON_NULL)}, so
   * synthesising onto a class that serialises nulls does not add a null property to the team's wire
   * payload — see {@link #synthesisedFieldsNeedNonNull}.
   */
  private SynthResult renderSynthFields(
      List<FieldModel> synthesised, String indent, ImportBinder binder, boolean nonNull) {
    SynthResult result = new SynthResult();
    StringBuilder text = new StringBuilder();
    for (FieldModel field : synthesised) {
      if (nonNull) {
        text.append(indent).append("@JsonInclude(JsonInclude.Include.NON_NULL)\n");
        result.usesJsonInclude = true;
      }
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
      List<DelegatingGetter> getters, ImportBinder binder) {
    SynthResult result = new SynthResult();
    StringBuilder text = new StringBuilder();
    text.append("  ").append(SYNTH_BEGIN).append('\n');
    for (DelegatingGetter getter : getters) {
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
      // The companion/config emitters' own decision first (it honours --type-package-hint and so is
      // the only path that can disambiguate a simple name declared in two packages), then a type the
      // model source declares → its real FQN (an existing model class in another sub-package),
      // otherwise a companion type freshly emitted into the model package.
      String fqn = typeFqnOverrides.containsKey(simple)
          ? typeFqnOverrides.get(simple)
          : index.fqnForSimpleName(simple, modelPackage)
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
   * Whether a type already carries a class-level {@code @CCD(member = "<memberName>")} — the team's
   * own, or this patch re-applied. Matched on the member NAME, not on the whole annotation, because a
   * class can carry several and each configures a different inherited member.
   */
  private static boolean hasMemberOverride(TypeDeclaration<?> type, String memberName) {
    for (AnnotationExpr ann : type.getAnnotations()) {
      String simple = ann.getNameAsString();
      if (!simple.equals("CCD") && !simple.endsWith(".CCD")) {
        continue;
      }
      if (Annotations.stringMember(ann, "member").filter(memberName::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Rewrites a field declaration's declared type to {@code targetSimpleName}, recording the replaced
   * source line in {@code retypedLines}.
   *
   * <p>The token replaced is the type the SDK actually reads for the field's {@code FieldType}: the
   * declared type itself for a scalar field, and the ELEMENT type for a collection
   * ({@code List<X>} → {@code List<Target>}, {@code List<ListValue<X>>} → {@code List<ListValue<Target>>}),
   * mirroring {@code CaseFieldGenerator.resolveCollectionType}'s own descent and {@link TypeInference}'s.
   * The replacement is made by the token's own source RANGE rather than by string search, so a
   * same-named token elsewhere on the line (a trailing comment, an initialiser,
   * {@code Foo foo = new Foo()}) is left alone.
   *
   * <p>Refused — leaving the declaration exactly as it is — when the type token does not begin and end on
   * one line, or its recorded text does not match the source at that range. Both mean the emitter's
   * column arithmetic cannot be trusted for this declaration, and a wrong splice would produce
   * uncompilable Java rather than a diff a reviewer can read.
   *
   * @return true when the declaration was rewritten, false when the retype was refused or was a no-op
   */
  private static boolean replaceDeclaredTypeToken(FieldDeclaration fieldDecl,
      String targetSimpleName, List<String> sourceLines, Map<Integer, String> retypedLines) {
    com.github.javaparser.ast.type.Type declared = fieldDecl.getVariable(0).getType();
    com.github.javaparser.ast.type.Type target = retypeTarget(declared);
    if (!(target instanceof ClassOrInterfaceType cit)) {
      return false;
    }
    // The NAME node, not the whole type: List<ListValue<Foo>>'s element name is 'Foo' and must be
    // replaced without disturbing its own (absent) type arguments.
    com.github.javaparser.ast.expr.SimpleName name = cit.getName();
    Optional<com.github.javaparser.Range> range = name.getRange();
    if (range.isEmpty()) {
      return false;
    }
    com.github.javaparser.Range r = range.get();
    if (r.begin.line != r.end.line) {
      return false;
    }
    int lineNumber = r.begin.line;
    // A line already rewritten by another member's retype would make these columns stale; two fields
    // never share a declaration line in practice, so refuse rather than compound the arithmetic.
    if (retypedLines.containsKey(lineNumber) || lineNumber < 1 || lineNumber > sourceLines.size()) {
      return false;
    }
    if (targetSimpleName.equals(name.getIdentifier())) {
      // Already declared as the companion — a re-applied patch must be a no-op, like every other op.
      return false;
    }
    String line = sourceLines.get(lineNumber - 1);
    int from = r.begin.column - 1;
    int to = r.end.column; // JavaParser's end column is inclusive; substring's end is exclusive.
    if (from < 0 || to > line.length() || from >= to) {
      return false;
    }
    if (!line.substring(from, to).equals(name.getIdentifier())) {
      return false;
    }
    retypedLines.put(lineNumber, line.substring(0, from) + targetSimpleName + line.substring(to));
    return true;
  }

  /**
   * The type node a retype must rewrite: the declared type for a scalar field, else the collection's
   * element type, descending one further level through a generic element wrapper
   * ({@code List<ListValue<X>>} → {@code X}) exactly as {@code CaseFieldGenerator}'s
   * {@code hasGenerics()} guard and {@link TypeInference#inferCollection} do.
   *
   * <p>Returns null — no rewritable token — whenever the token the descent lands on carries type
   * arguments of its own, or the declaration is a raw collection with none. Both mean the declared
   * shape is deeper or vaguer than the SDK's single level of descent resolves, so no single name
   * substitution reproduces the definition type: sscs's
   * {@code List<CcdValue<CcdValue<String>>>} descends to {@code CcdValue<String>}, and renaming THAT
   * token yields {@code List<CcdValue<HearingVenueEpimsId<String>>>} — a type that does not take
   * parameters, and a model copy that no longer compiles.
   */
  private static com.github.javaparser.ast.type.Type retypeTarget(
      com.github.javaparser.ast.type.Type declared) {
    return RetrofitTypeTokens.elementToken(declared);
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

  /**
   * Plans the per-constant annotation insertions for one enum — the {@code @CCD(label)} carrying the
   * definition's {@code ListElement} and the {@code @JsonProperty} carrying its {@code ListElementCode} —
   * and reports which imports the file now needs.
   *
   * <p>The constants are grouped by the source line they begin on, because teams write enums both
   * ways: one constant per line (civil's {@code AllocatedTrack}) and several to a line (civil's
   * {@code ListingOrRelisting}: {@code LISTING, RELISTING}). A per-line insertion above a shared line
   * would stack every one of that line's annotations above the same line — neither {@code @CCD} nor
   * {@code @JsonProperty} is {@code @Repeatable}, so that does not compile. A shared line is therefore
   * rewritten into one constant per line instead; see {@link #splitSharedConstantLine}.
   */
  private ConstantPins planConstantPins(EnumDeclaration decl, Map<String, List<String>> labels,
      Map<String, String> codes, List<String> sourceLines,
      Map<Integer, List<String>> insertionsByLine, Set<Integer> linesToDelete) {
    Map<Integer, List<EnumConstantDeclaration>> byLine = new TreeMap<>();
    for (EnumConstantDeclaration constant : decl.getEntries()) {
      int line = constant.getBegin().map(p -> p.line).orElse(-1);
      if (line >= 1 && line <= sourceLines.size()) {
        byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(constant);
      }
    }

    boolean ccd = false;
    boolean jsonProperty = false;
    for (Map.Entry<Integer, List<EnumConstantDeclaration>> entry : byLine.entrySet()) {
      int line = entry.getKey();
      List<EnumConstantDeclaration> constants = entry.getValue();
      if (constants.stream().noneMatch(c -> labels.containsKey(c.getNameAsString())
          || codes.containsKey(c.getNameAsString()))) {
        continue;
      }
      String indent = leadingWhitespace(sourceLines.get(line - 1));

      // The common shape: the constant owns its line, so the annotations are inserted above it exactly
      // as a field's are — below any annotation the constant already carries, since the insertion is
      // keyed on the constant's begin line, which IS that annotation's line when it has one.
      if (constants.size() == 1) {
        List<String> added = constantAnnotationLines(constants.get(0), labels, codes, indent);
        insertionsByLine.computeIfAbsent(line, k -> new ArrayList<>()).addAll(added);
        ccd |= labels.containsKey(constants.get(0).getNameAsString());
        jsonProperty |= codes.containsKey(constants.get(0).getNameAsString());
        continue;
      }

      List<String> rewritten =
          splitSharedConstantLine(sourceLines.get(line - 1), constants, labels, codes, indent);
      if (rewritten == null) {
        continue; // a shape the split cannot reproduce verbatim: leave the line alone
      }
      linesToDelete.add(line);
      insertionsByLine.computeIfAbsent(line, k -> new ArrayList<>()).addAll(rewritten);
      for (EnumConstantDeclaration constant : constants) {
        ccd |= labels.containsKey(constant.getNameAsString());
        jsonProperty |= codes.containsKey(constant.getNameAsString());
      }
    }
    return new ConstantPins(ccd, jsonProperty);
  }

  /**
   * The annotation lines to insert above one enum constant: its {@code @JsonProperty} code pin first
   * (so the Jackson annotation reads above the CCD one, matching how the converter's own generated enums
   * are laid out), then its {@code @CCD}. Either may be absent — a constant can need its code pinned and
   * not its label, or the reverse.
   *
   * <p>The code pin is skipped when the constant already carries a {@code @JsonProperty}: that annotation
   * is not {@code @Repeatable} and already governs what Jackson emits, so a second would not compile and
   * re-applying the patch must be a no-op. The plan only ever reaches here with a pin whose value the
   * existing annotation already agrees with (see
   * {@link RetrofitFixedListLabels#canEmitTheDefinitionsCodes}), so nothing is lost by skipping it.
   */
  private static List<String> constantAnnotationLines(EnumConstantDeclaration constant,
      Map<String, List<String>> labels, Map<String, String> codes, String indent) {
    String name = constant.getNameAsString();
    List<String> annotations = new ArrayList<>();
    String code = codes.get(name);
    if (code != null && !Annotations.has(constant, "JsonProperty")) {
      annotations.add("@JsonProperty(" + CcdAnnotationRenderer.quote(code) + ")");
    }
    List<String> members = labels.get(name);
    if (members != null) {
      annotations.add(labelAnnotation(members, indent.length()));
    }
    return indentEachLine(annotations, indent);
  }

  /**
   * Rewrites one source line holding several enum constants into one line per constant, each carrying
   * its own {@code @JsonProperty}/{@code @CCD} where the plan has one, and returns the replacement lines
   * — or {@code null} to refuse the line.
   *
   * <p>Each constant's text is taken as the VERBATIM column slice of the original line, so arguments
   * and formatting survive untouched; whatever follows the last constant (a {@code ,} continuing onto
   * the next line, a {@code ;} closing the constant list, or nothing) is carried onto the last emitted
   * line. Refused rather than guessed when the line is not a plain comma-separated run of
   * single-line, unannotated constants — anything else (a constant with a body, a whole enum written
   * on one line, an interleaved comment) could not be reproduced byte-for-byte, and an unpinned
   * constant only costs a residual diff line whereas a mangled one breaks the team's build.
   */
  private static List<String> splitSharedConstantLine(String line,
      List<EnumConstantDeclaration> constants, Map<String, List<String>> labels,
      Map<String, String> codes, String indent) {
    List<String> texts = new ArrayList<>();
    int cursor = 0;
    for (EnumConstantDeclaration constant : constants) {
      if (!constant.getAnnotations().isEmpty()) {
        return null;
      }
      Optional<com.github.javaparser.Position> begin = constant.getBegin();
      Optional<com.github.javaparser.Position> end = constant.getEnd();
      if (begin.isEmpty() || end.isEmpty() || begin.get().line != end.get().line) {
        return null;
      }
      int from = begin.get().column - 1;
      int to = end.get().column;
      if (from < cursor || to > line.length() || to <= from) {
        return null;
      }
      String between = line.substring(cursor, from);
      if (cursor == 0 ? !between.isBlank() : !between.strip().equals(",")) {
        return null;
      }
      texts.add(line.substring(from, to));
      cursor = to;
    }

    String tail = line.substring(cursor);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < constants.size(); i++) {
      out.addAll(constantAnnotationLines(constants.get(i), labels, codes, indent));
      out.add(indent + texts.get(i) + (i < constants.size() - 1 ? "," : tail));
    }
    return out;
  }

  /**
   * Declares the constants a list needs and the enum lacks, after the last existing constant.
   *
   * <p><b>How the edit is made.</b> A constant list ends in {@code ;} (or, in an enum with no members, at
   * the closing brace). The last constant's line is rewritten to end in {@code ,} and the new constants
   * follow, the last of them carrying whatever terminated the original line — so the added text is one
   * contiguous hunk at the end of the constant list and nothing before it moves. Refused when that
   * terminator is not on the last constant's own line, or when the last constant shares its line with
   * another (the shared-line split may already own it): those need a rewrite this cannot make
   * byte-faithfully, and an unmade addition costs residual lines whereas a mangled one breaks the build.
   *
   * @return which imports the added constants' own pins make the file need
   */
  private ConstantPins planAddedConstants(EnumDeclaration decl,
      List<RetrofitFixedListLabels.AddedConstant> added, List<String> sourceLines,
      Map<Integer, List<String>> insertionsByLine, Set<Integer> linesToDelete) {
    List<EnumConstantDeclaration> entries = decl.getEntries();
    if (entries.isEmpty()) {
      return new ConstantPins(false, false);
    }
    EnumConstantDeclaration last = entries.get(entries.size() - 1);
    int line = last.getEnd().map(p -> p.line).orElse(-1);
    if (line < 1 || line > sourceLines.size()
        || entries.stream().filter(c -> c.getEnd().map(p -> p.line).orElse(-1) == line).count() > 1) {
      return new ConstantPins(false, false);
    }
    String text = sourceLines.get(line - 1);
    int endColumn = last.getEnd().map(p -> p.column).orElse(-1);
    if (endColumn < 1 || endColumn > text.length()) {
      return new ConstantPins(false, false);
    }
    // What follows the last constant on its line: the `;` closing the list, or nothing when the enum
    // declares no members and the list runs to the closing brace.
    String terminator = text.substring(endColumn);
    if (!terminator.isBlank() && !terminator.strip().equals(";")) {
      return new ConstantPins(false, false);
    }

    String indent = leadingWhitespace(text);
    boolean ccd = false;
    boolean jsonProperty = false;
    List<String> declared = new ArrayList<>();
    for (int i = 0; i < added.size(); i++) {
      RetrofitFixedListLabels.AddedConstant constant = added.get(i);
      List<String> annotations = new ArrayList<>();
      // The code pin is needed unless the constant name IS the code, exactly as for a declared constant —
      // or unless the enum serialises through a @JsonValue, where the code rides in the constructor
      // argument and no pin can move it. Both decisions are the matcher's; see AddedConstant#pinnedCode.
      if (constant.pinnedCode() != null) {
        annotations.add("@JsonProperty(" + CcdAnnotationRenderer.quote(constant.pinnedCode()) + ")");
        jsonProperty = true;
      }
      Optional<String> label = RetrofitFixedListLabels.labelFor(constant);
      if (label.isPresent()) {
        annotations.add(labelAnnotation(
            List.of("label = " + CcdAnnotationRenderer.quote(label.get())), indent.length()));
        ccd = true;
      }
      declared.addAll(indentEachLine(annotations, indent));
      declared.add(indent + constant.name() + "(" + String.join(", ", constant.arguments()) + ")"
          + (i < added.size() - 1 ? "," : terminator));
    }

    // Rewrite the last existing constant's line to continue the list, then insert the new declarations
    // after it. Both are keyed on the same line: the deletion removes the original, the insertion writes
    // the rewritten form and the additions in its place.
    linesToDelete.add(line);
    List<String> replacement = new ArrayList<>();
    replacement.add(text.substring(0, endColumn) + ",");
    replacement.addAll(declared);
    insertionsByLine.computeIfAbsent(line, k -> new ArrayList<>()).addAll(replacement);
    return new ConstantPins(ccd, jsonProperty);
  }

  /** Which imports a run of per-constant annotation insertions makes the file need. */
  private record ConstantPins(boolean ccd, boolean jsonProperty) {
  }

  /**
   * The {@code @CCD(...)} for an enum constant, wrapped one member per continuation line when the
   * single-line form would breach the house line limit — through the same renderer a field's annotation
   * uses, so a three-member state pin carrying fpl's multi-line {@code TitleDisplay} lays out the same
   * way a long field annotation does. Returned with no leading indent on the first line; the caller
   * applies its placement indent to every line.
   */
  private static String labelAnnotation(List<String> members, int baseIndentLength) {
    return CcdAnnotationRenderer.renderWrapped("CCD", members, baseIndentLength);
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
      boolean jsonUnwrapped, boolean jsonIgnore, boolean jsonInclude, Set<String> accessClasses,
      Set<String> typeImports) {
    List<String> wanted = new ArrayList<>();
    if (ccd) {
      wanted.add("import uk.gov.hmcts.ccd.sdk.api.CCD;");
    }
    if (jsonInclude) {
      wanted.add("import com.fasterxml.jackson.annotation.JsonInclude;");
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
    /**
     * Java field name → the {@code @JsonProperty} id to pin on it, for fields the
     * {@code CaseEventToComplexTypes} walk resolved through a class-level {@code @JsonNaming}
     * strategy (see {@link RetrofitPinnedNames}). These fields receive ONLY a {@code @JsonProperty} —
     * they are not definition complex-type members, so they get no {@code @CCD}.
     */
    private final Map<String, String> pinNames = new LinkedHashMap<>();
    /**
     * Java field name → the generated companion class simple name to <em>re-declare</em> it as, for a
     * field whose definition complex type has no model class of its own (see
     * {@link RetrofitPlannedRetypes}). Unlike every other op here this rewrites a token on the
     * declaration line itself rather than inserting lines above it.
     */
    private final Map<String, String> retype = new LinkedHashMap<>();
    private final List<FieldModel> synthesise = new ArrayList<>();
    /**
     * True when the class receiving the synthesised block serialises null-valued properties (a
     * class-level {@code @JsonInclude} with no explicit value, i.e. ALWAYS). Each synthesised field
     * then carries its own {@code @JsonInclude(NON_NULL)} — see
     * {@link #synthesisedFieldsNeedNonNull}.
     */
    private boolean synthesisedNeedsNonNull;
    /** Delegating getters to add for @JsonUnwrapped-reached complex-type grants (retrofit). */
    private final List<DelegatingGetter> delegatingGetters = new ArrayList<>();
    /** Simple name of a CaseDataExtra class to add as a prefix-less @JsonUnwrapped member (B2). */
    private String unwrappedMemberType;
    /**
     * Class-level annotation simple names to remove from the top-level type — currently only
     * {@code AllArgsConstructor}, dropped when the root class exceeds the constructor-argument limit
     * and building goes through a builder (the constructor-limit fix).
     */
    private final Set<String> removeTypeAnnotations = new LinkedHashSet<>();
    /**
     * Java field name → annotation simple names to remove from that field's declaration — currently
     * only {@code Getter}, dropping the {@code @Getter(AccessLevel.NONE)} that suppresses the getter a
     * placement resolved through (see {@link RetrofitUnsuppressedGetters}).
     */
    private final Map<String, Set<String>> removeFieldAnnotations = new LinkedHashMap<>();
    /**
     * Class simple name → the class-level {@code @ComplexType} to add to it, pinning the CCD type ID
     * the definition uses for a model class whose Java name differs from it (see
     * {@link #planComplexTypeId}).
     */
    private final Map<String, ComplexTypeIdPlan> nameComplexTypes = new LinkedHashMap<>();
    /**
     * Enum simple name → (constant name → the {@code @CCD(...)} members to pin on it): the definition's
     * own {@code ListElement} for a constant of an enum backing a {@code FixedLists} ID
     * ({@link RetrofitFixedListLabels}), and the {@code State} sheet's
     * {@code Name}/{@code TitleDisplay}/{@code Description} for a constant of the reused State enum
     * ({@link RetrofitStateLabels}). Keyed per enum because one file can declare several, and per
     * constant because the annotation goes on the constant, not the type — and because a single
     * {@code @CCD} per constant is all that compiles, so the two sources must share one claim.
     */
    private final Map<String, Map<String, List<String>>> constantAnnotations = new LinkedHashMap<>();
    /**
     * Enum simple name → (constant name → the {@code ListElementCode} to pin on it as
     * {@code @JsonProperty}). Kept separate from {@code constantAnnotations} because this is a DIFFERENT
     * annotation, not another {@code @CCD} member: a constant can carry both, and the two are decided
     * independently (a constant may need its code pinned and not its label, or the reverse). See
     * {@link RetrofitFixedListLabels#codePins}.
     */
    private final Map<String, Map<String, String>> constantCodes = new LinkedHashMap<>();
    /**
     * Enum simple name → the constants to ADD to it, for definition codes the enum models none for. See
     * {@link RetrofitFixedListLabels#constantsToAdd}.
     */
    private final Map<String, List<RetrofitFixedListLabels.AddedConstant>> addConstants =
        new LinkedHashMap<>();
    /**
     * The class whose hand-written constructor(s) must be widened to accept the synthesised fields,
     * or null when synthesis needs no constructor change. Set only for the builder-bound /
     * {@code @Value} idioms that would otherwise refuse synthesis.
     */
    private String extendConstructorsOf;
    /**
     * Class simple name → the class-level {@code @CCD(member = …)} overrides to add to it: one per
     * inherited member this class needs configured differently from the field's own declaration (see
     * {@link RetrofitInheritedMembers}). Each entry is a rendered member list, {@code member} first.
     */
    private final Map<String, List<MemberOverridePlan>> memberOverrides = new LinkedHashMap<>();
    /**
     * The narrow all-args constructor to ADD, or null when none is needed. Distinct from
     * {@code extendConstructorsOf}: that widens constructors the team wrote, whereas this one exists
     * to bind a subclass's {@code super(...)} to the pre-synthesis field list of a class whose
     * all-args constructor Lombok generates (so there is no source constructor to widen).
     */
    private NarrowAllArgsPlan narrowAllArgs;

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

    /**
     * Records that the class receiving the synthesised block serialises null-valued properties, so
     * each synthesised field needs its own {@code @JsonInclude(NON_NULL)}.
     */
    void includeSynthesisedWhenNonNull() {
      this.synthesisedNeedsNonNull = true;
    }

    /**
     * Records that {@code targetClass}'s hand-written constructor(s) must be widened to take the
     * synthesised fields as trailing parameters.
     */
    void extendConstructors(String targetClass) {
      this.extendConstructorsOf = targetClass;
    }

    /**
     * Records the narrow all-args constructor to add so a subclass's {@code super(...)} still binds.
     */
    void addNarrowAllArgs(NarrowAllArgsPlan plan) {
      this.narrowAllArgs = plan;
    }

    void addUnwrappedMember(String extraClassType) {
      this.unwrappedMemberType = extraClassType;
    }

    void addDelegatingGetter(DelegatingGetter getter) {
      this.delegatingGetters.add(getter);
    }

    void removeTypeAnnotation(String simpleName) {
      this.removeTypeAnnotations.add(simpleName);
    }

    /**
     * Records that a field declaration must lose an annotation — the getter-suppressing
     * {@code @Getter(AccessLevel.NONE)} on a {@code @JsonUnwrapped} member whose getter a placement
     * resolved through.
     */
    void removeFieldAnnotation(String member, String simpleName) {
      removeFieldAnnotations.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(simpleName);
    }

    /**
     * Records a class-level {@code @CCD(member = …)} override on {@code className}, configuring one
     * member it inherits for its own rows only.
     */
    void overrideMember(String className, MemberOverridePlan plan) {
      memberOverrides.computeIfAbsent(className, k -> new ArrayList<>()).add(plan);
    }

    /**
     * Records the class-level {@code @ComplexType(name = …, generate = true)} pinning the definition's
     * own type ID onto a model class whose Java simple name differs from it.
     */
    void nameComplexType(String className, ComplexTypeIdPlan plan) {
      nameComplexTypes.putIfAbsent(className, plan);
    }

    /**
     * Records the per-constant {@code @CCD(...)} member pins for one model enum. First write wins per
     * CONSTANT, matching the ID pin's own single-claim rule (an enum reached by two definition IDs pins
     * the first one's ID, so it must pin that same list's labels rather than a second list's) while
     * still letting two enums in one file, or two claims on two different constants of one enum, both
     * land. Per-constant rather than per-enum because the constant is what carries the annotation, and
     * an enum can legitimately be reached as both the reused State and a fixed list.
     */
    void annotateConstants(String enumSimpleName, Map<String, List<String>> membersByConstant) {
      if (membersByConstant.isEmpty()) {
        return;
      }
      Map<String, List<String>> existing =
          constantAnnotations.computeIfAbsent(enumSimpleName, k -> new LinkedHashMap<>());
      membersByConstant.forEach(existing::putIfAbsent);
    }

    /**
     * Records the per-constant {@code @JsonProperty} pinning the definition's own
     * {@code ListElementCode}. First write wins per constant, for the same reason the label pins do: an
     * enum reached by two definition IDs serves the first one's list, so a second list's codes must not
     * overwrite the first's.
     */
    void pinConstantCodes(String enumSimpleName, Map<String, String> codeByConstant) {
      if (codeByConstant.isEmpty()) {
        return;
      }
      Map<String, String> existing =
          constantCodes.computeIfAbsent(enumSimpleName, k -> new LinkedHashMap<>());
      codeByConstant.forEach(existing::putIfAbsent);
    }

    /**
     * Records the constants to ADD to one model enum. First write wins per enum, for the same reason the
     * pins' does: an enum reached by two definition IDs serves the first one's list, so only that list's
     * missing codes become constants.
     */
    void addConstants(
        String enumSimpleName, List<RetrofitFixedListLabels.AddedConstant> constants) {
      if (constants.isEmpty()) {
        return;
      }
      addConstants.putIfAbsent(enumSimpleName, constants);
    }

    /**
     * Records that a field must carry an explicit {@code @JsonProperty} pinning the id its class's
     * {@code @JsonNaming} strategy already produces. Skipped when the field is also being annotated,
     * because that plan carries its own {@code renameTo} and would emit a second annotation.
     */
    void pinName(String member, String id) {
      if (!annotate.containsKey(member)) {
        pinNames.putIfAbsent(member, id);
      }
    }

    /**
     * Records that a field must be re-declared as the generated companion class backing its definition
     * complex type. First write wins, matching {@link RetrofitPlannedRetypes}'s own single-claim rule so
     * the patch and the graph agree on which companion a member points at.
     */
    void retype(String member, String targetSimpleName) {
      retype.putIfAbsent(member, targetSimpleName);
    }
  }

  /**
   * The narrow all-args constructor to add to a class whose Lombok-generated all-args constructor a
   * subclass calls positionally: the pre-synthesis parameter list, the delegation arguments (those
   * parameters plus one {@code null} per synthesised field), the parameter-type signature used for
   * collision detection, and the indent the class's own members sit at.
   */
  private static final class NarrowAllArgsPlan {
    private final String className;
    private final List<String> params;
    private final List<String> args;
    private final String signature;
    private final String indent;

    NarrowAllArgsPlan(
        String className, List<String> params, List<String> args, String signature, String indent) {
      this.className = className;
      this.params = params;
      this.args = args;
      this.signature = signature;
      this.indent = indent;
    }
  }

  /**
   * One class-level {@code @CCD(member = …)} to add: the inherited member it configures (so a re-run
   * can see it is already there), its rendered annotation members, and the imports it needs.
   *
   * @param memberName the inherited member this override configures
   * @param members the rendered {@code @CCD} members, {@code member} first
   * @param usesFieldType whether the rendered members reference {@code FieldType}
   * @param accessClasses the access-class simple names the rendered members reference
   */
  private record MemberOverridePlan(String memberName, List<String> members, boolean usesFieldType,
                                    Set<String> accessClasses) {
  }

  /**
   * A planned class-level {@code @ComplexType} pinning a definition complex type's ID onto the model
   * class that backs it, for a class whose Java simple name is not that ID.
   *
   * @param definitionId the ComplexTypes sheet ID the SDK must emit for this class
   * @param generate whether the class's own members are emitted as that type's rows (true for the value
   *     class), or the type is named only so collection fields reference it while emitting no rows of
   *     its own (false for a {@code {id, value}} wrapper)
   */
  private record ComplexTypeIdPlan(String definitionId, boolean generate) {
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
    boolean usesJsonInclude;
    final Set<String> accessClasses = new LinkedHashSet<>();
    /** Fully-qualified imports for synthesised-field types declared by simple name. */
    final Set<String> typeImports = new LinkedHashSet<>();
  }
}
