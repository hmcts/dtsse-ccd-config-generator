package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.converter.Converter;
import uk.gov.hmcts.ccd.sdk.converter.ConverterFactory;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.link.DefaultDefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.report.GapAndPassthroughWriter;

/**
 * Phase-2 retrofit orchestrator: runs the matcher once (parsing the model source), then reuses that
 * single parse + resolution to (1) rebind the linked model onto the team's existing classes, (2)
 * emit the companion generated sources (config/enum/access/passthrough) targeting them, and (3)
 * emit the annotation patch against the team's model. It threads the {@link RetrofitMatcher}'s
 * resolution through so the model tree is parsed exactly once.
 */
public final class RetrofitConverter {

  private final DefinitionIr ir;
  private final String caseTypeId;
  private final ConversionOptions options;
  private final Path modelSourceRoot;
  private final String modelPackage;
  private final String modelClassSimpleName;

  /**
   * Creates a retrofit converter.
   *
   * @param ir the parsed definition
   * @param caseTypeId the case type to convert
   * @param options the conversion options (retrofit=true, companion output packages set)
   * @param modelSourceRoot the team model's {@code src/main/java} root
   * @param modelPackage the team model package
   * @param modelClassSimpleName the root model class simple name
   */
  public RetrofitConverter(DefinitionIr ir, String caseTypeId, ConversionOptions options,
      Path modelSourceRoot, String modelPackage, String modelClassSimpleName) {
    this.ir = ir;
    this.caseTypeId = caseTypeId;
    this.options = options;
    this.modelSourceRoot = modelSourceRoot;
    this.modelPackage = modelPackage;
    this.modelClassSimpleName = modelClassSimpleName;
  }

  /** The result of a phase-2 run: the report (for context) and the emitted patch. */
  public static final class Result {
    private final RetrofitReport report;
    private final RetrofitPatch patch;

    Result(RetrofitReport report, RetrofitPatch patch) {
      this.report = report;
      this.patch = patch;
    }

    public RetrofitReport report() {
      return report;
    }

    public RetrofitPatch patch() {
      return patch;
    }
  }

  /**
   * Runs the full phase-2 conversion, writing the patch to {@code <reportDir>/retrofit.patch} and
   * the companion sources under the options' {@code outputSrc}.
   *
   * @param reportDir where {@code retrofit.patch} (and the phase-1 report) are written
   * @return the report and patch
   */
  public Result run(Path reportDir) {
    RetrofitMatcher matcher = new RetrofitMatcher(
        ir, caseTypeId, modelSourceRoot, modelPackage, modelClassSimpleName);
    RetrofitReport report = matcher.match();
    RetrofitReportWriter.write(report, reportDir);
    if (report.isMapBased()) {
      throw new IllegalStateException(
          "Retrofit not applicable for " + caseTypeId + ": " + report.getNotApplicableReason());
    }

    ModelSourceIndex index = matcher.index();
    final PropertyResolver.Resolution resolution = matcher.resolution();
    final ModelSourceIndex.Type root = matcher.root();

    // Let the placements resolve through a @JsonUnwrapped member whose Lombok getter the model
    // suppresses, on the understanding that the patch below deletes the suppression. Installed HERE —
    // after the matcher's report-only match above, whose measured floors must keep reflecting the model
    // as it stands, and before any placement runs — so every placement records into the one plan the
    // patch realises. See RetrofitUnsuppressedGetters for why the removal is wire-format-neutral.
    index.repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());

    // Reuse the team's State enum only when it is directly reusable (every definition state ID
    // resolves — proposal decision 3); otherwise generate a fresh State enum. The config binds to
    // whichever via EmitContext.stateClass().
    ConversionOptions.ConversionOptionsBuilder effective = options.toBuilder();
    RetrofitReport.StateVerdict stateVerdict = report.getStateVerdict();
    if (stateVerdict != null && stateVerdict.isStateEnumFound()
        && stateVerdict.getConflictingStates() == 0) {
      String fqn = stateVerdict.getStateEnumClass();
      int lastDot = fqn.lastIndexOf('.');
      effective
          .retrofitStateClass(fqn.substring(lastDot + 1))
          .retrofitStateClassPackage(lastDot < 0 ? modelPackage : fqn.substring(0, lastDot))
          .retrofitStateConstants(
              new StateEnumAnalyser(index).stateIdToConstant(modelPackage));
    }
    // A companion complex type emitted into modelPackage references member types that may live in
    // the team's other sub-packages (Civil's JudgmentAddress in model.judgmentonline, enums in
    // .enums). Feed JavaTypeParser the real FQN for every out-of-package model type so those
    // references import correctly instead of defaulting (wrongly) to modelPackage.
    Map<String, String> packageHints = options.getRetrofitTypePackageHints() == null
        ? java.util.Map.of() : options.getRetrofitTypePackageHints();
    validatePackageHints(index, packageHints);
    // Merge the out-of-package sibling type FQNs (C2) with the camelCase→PascalCase class aliases
    // (A2 companion fallout): a companion member/synthesised field typed by a camelCase definition
    // complex-type ID ({@code panel}, {@code name}) must bind to the existing PascalCase class
    // ({@code Panel}, {@code Name}) — whose camelCase companion is no longer generated — rather than a
    // dangling {@code modelPackage.panel}. Sibling FQNs win on a key clash (they name a concrete
    // out-of-package location; an alias is only a case-normalisation of an in/near-package class).
    //
    // A third source covers the case neither of those two reaches: a definition complex type that
    // BINDS to an existing class whose real simple name differs from the linker's derived name by more
    // than the leading character (ET's et3CaseDetailsLinksStatuses → Et3CaseDetailsLinksStatuses vs the
    // model's acronym-cased ET3CaseDetailsLinksStatuses). Nothing is emitted under the derived name, so
    // without the alias every reference to it is a cannot-find-symbol. Applied FIRST so a concrete
    // out-of-package sibling FQN still wins on a key clash, as before.
    // A definition type ID no name-based lookup reaches, bound instead to the class its own referencing
    // field is DECLARED as (probate's ExecutorApplying → AdditionalExecutorApplying, its
    // handoffReasonFixedList → HandoffReasonId). The patch pins the definition's ID onto that class with
    // @ComplexType(name), so the SDK emits the type under the ID the definition uses; without the
    // binding the ID gets a companion nothing references while the real class emits under its Java name
    // — the largest single category of retrofit residual (see RetrofitTypeBinder).
    //
    // Computed HERE, once, from the root resolution, and handed to every consumer below: the companion
    // filter, the reserved-name sets, the type aliases, the rebinder's FixedList drop and BOTH patch
    // emitters. One derivation, so no two of them can disagree about which IDs have a backing class.
    RetrofitTypeBinder binder = new RetrofitTypeBinder(index, modelPackage);
    // Settle the same-simple-name ties FIRST, from the declarations of the definition's own referencing
    // fields. complexTypeClass is the lookup the binding itself — and the companion filter, the member
    // walk and both patch emitters — ask whether an ID already has a class, so an arbitrary tie-break
    // there is invisible to every one of them. See ModelSourceIndex#preferDeclaredClasses.
    index.preferDeclaredClasses(
        binder.declaredClassPreferences(ir, caseTypeId, resolution.properties));
    final Map<String, ModelSourceIndex.Type> declaredBindings =
        binder.bind(ir, caseTypeId, resolution.properties);

    Map<String, String> fqnOverrides = new java.util.LinkedHashMap<>();
    // A reference to a declared-bound ID must resolve to the class it binds to, not to a companion that
    // is no longer emitted for it — the same aliasing the name-bound IDs get below, keyed on the ID's
    // own derived companion name.
    // Keyed per KIND, because the two namers differ: a fixed list's reference name strips the machine
    // 'FL_' prefix (FL_commRequestTopic → CommRequestTopic), so keying it on the complex-type namer
    // (FLCommRequestTopic) aliases a name nothing ever emits while the real reference stays dangling.
    declaredBindings.forEach((id, type) -> {
      String derived = type.isEnum()
          ? uk.gov.hmcts.ccd.sdk.converter.link.TypeClassNamer.fixedListName(id)
          : uk.gov.hmcts.ccd.sdk.converter.link.TypeClassNamer.complexTypeName(id);
      if (!derived.isEmpty() && !derived.equals(type.simpleName)) {
        fqnOverrides.putIfAbsent(derived, type.fqn);
      }
    });
    fqnOverrides.putAll(index.complexTypeIdClassAliases(
        sheetIds(uk.gov.hmcts.ccd.sdk.converter.ir.SheetName.COMPLEX_TYPES), modelPackage));
    fqnOverrides.putAll(index.caseInsensitiveClassAliases());
    fqnOverrides.putAll(index.topLevelFqnsOutside(modelPackage, packageHints));
    final ConversionOptions planOptions = effective
        .retrofitTypeFqnOverrides(fqnOverrides)
        // Reserve existing model names so a generated companion's PascalCase name (finding #3/#4) is
        // suffixed rather than colliding with an unrelated existing type of the same name. The two
        // companion kinds reserve different sets because each binds to (and so never re-emits) a
        // model type it can reuse:
        //   - a complex-type companion (a class) is emitted only when no model CLASS of that ID
        //     exists (RetrofitComplexTypeEmitter binds the rest in place), so it can only clash with a
        //     model ENUM (the definition 'benefit' complex type vs the domain enum Benefit). Reserving
        //     class names here would wrongly suffix a reference to a bound, never-emitted type.
        //   - a fixed-list companion (an enum) reuses a model type only on an EXACT list-ID match
        //     (rebind's hasTopLevelType(id)); a machine 'FL_'/case-shifted ID (FL_amendReason,
        //     eventType) does not match its PascalCased model twin (AmendReason, EventType), so a
        //     fresh companion is emitted and can clash with EITHER a model enum or class. All model
        //     names are reserved EXCEPT the exact-ID matches: those bind to the model's own type and
        //     emit no companion, so reserving them would rename the reference to a '<Id>2' companion
        //     that is never generated (the prl/fpl/Civil 'cannot find symbol' break).
        //
        // A declared-bound ID emits no companion either (the patch pins its ID onto the real class), so
        // that class's name must not be reserved for the same reason: reserving it would bump a reference
        // to a '<Name>2' companion nothing generates.
        .retrofitReservedComplexTypeNames(
            withoutBoundNames(reservedComplexTypeNames(index), declaredBindings))
        .retrofitReservedFixedListNames(
            withoutBoundNames(reservedFixedListNames(index), declaredBindings))
        .build();

    int constructorLimit = options.getRetrofitConstructorLimit();
    String pathPrefix = patchPathPrefix(options);
    // Bind CaseEventToComplexTypes member chains to the team's ACTUAL declared model classes (real
    // getters, e.g. getOrganisationID) rather than the SDK-predefined type of a shared complex-type ID
    // or a similarly-named synthesised sibling. A member with no Java backing on the real class makes
    // that row fall back to a row passthrough — no broken reference is ever emitted.
    //
    // The graph binds against the model as the applied PATCH will leave it, so it must know which
    // members the patch synthesises — but the patch is emitted from the REBOUND model, which the
    // conversion below produces. The dependency is not actually circular:
    // planComplexTypeMembers reads only model.getComplexTypes(), which the rebinder passes through
    // untouched and which the linker builds before its CaseEventToComplexTypes pass. So a linking pass
    // that emits nothing yields a model whose complex types already equal the final ones, and planning
    // against it gives the same answer the real patch will.
    //
    // The reverse direction runs through pinnedNames: where the walk resolves a member only via its
    // class's @JsonNaming strategy (Civil's Address.addressLine1 serialising as AddressLine1), it
    // records that reliance HERE and the patch below pins it with an explicit @JsonProperty. Both the
    // SDK and this converter are naming-strategy blind, so resolving without pinning would emit
    // Address::getAddressLine1 and regenerate the id 'addressLine1' — silently changing the CCD field
    // id. One shared instance, written by the graph during the conversion and read by the patch
    // emitter after it, is what makes the two impossible to disagree (see RetrofitPinnedNames).
    final RetrofitPinnedNames pinnedNames = RetrofitPinnedNames.empty();
    // One throwaway planning pass yields BOTH plans the graph needs, so the synthesis it resolves and
    // the retypes it descends through can never come from two different plannings of the same model.
    RetrofitPatchEmitter planner =
        planner(planOptions, index, resolution, root, constructorLimit, pathPrefix, declaredBindings);
    RetrofitPlannedSynthesis plannedSynthesis = planner.planSynthesisedMembers();
    final ConversionOptions emitOptions = planOptions.toBuilder()
        .retrofitModelTypeGraph(new RetrofitEventComplexTypeGraph(index, resolution, root,
            plannedSynthesis, planner.plannedRetypes(), planner.plannedHints(), pinnedNames))
        .build();
    RetrofitModelRebinder rebinder =
        new RetrofitModelRebinder(index, resolution, root, constructorLimit);
    rebinder.bindDeclaredFixedLists(declaredBoundEnumIds(declaredBindings));

    // Emit companion sources: the Converter runs reader → linker (retrofit: unclustered) → rebind →
    // emitters. The rebind rewrites getters/fields onto the team's model and drops any FixedList
    // reused from a model enum. We capture the rebound model for the patch emitter.
    CaseTypeModel[] reboundHolder = new CaseTypeModel[1];
    // The linker's own fixed lists, captured before the rebind below drops the ones a model enum already
    // serves — those are exactly the enums whose constants carry the definition's ListElement labels.
    List<FixedListModel>[] linkedFixedLists = new List[1];
    // The pipeline's own gap collector. The patch emitter below necessarily runs AFTER the conversion —
    // it needs the rebound model, which only the conversion produces — and so after the report writer
    // has already written gap-report.json/md. Its findings must still land in the report, so the shared
    // collector is captured here and the reports are rewritten once the emitter has recorded into it.
    GapCollector[] pipelineGaps = new GapCollector[1];
    // Append the companion complex-type emitter for the DEFINITION-ONLY complex types (those with no
    // model class). It needs the parsed index + model package, so it is passed in here rather than
    // wired in the static ConverterFactory.
    Converter converter = ConverterFactory.create(emitOptions,
            List.of(new RetrofitComplexTypeEmitter(index, modelPackage, declaredBindings.keySet())))
        .toBuilder()
        .modelTransform((model, gaps) -> {
          linkedFixedLists[0] = model.getFixedLists();
          pipelineGaps[0] = gaps;
          CaseTypeModel rebound = rebinder.rebind(model, gaps);
          reboundHolder[0] = rebound;
          return rebound;
        })
        .build();
    converter.convert(emitOptions);

    // Emit the annotation patch from the same parse/resolution + the rebound model's @CCD metadata.
    // The access classes the model's @CCD(access = {…}) references live in <configPackage>.access
    // (matching EmitContext.accessPackage() and AccessClassEmitter), so the patch's imports must
    // point there, not at the root config package. Both derivations go through the single
    // EmitContext.accessPackage(root) source of truth so the patch's imports and the emitted access
    // files can never drift into different packages (the ccd.config-vs-ccd.access split, Bug A).
    // pinnedNames is fully populated by now: the conversion above ran the member walk that fills it.
    // Hand the patch emitter the SAME type-FQN decisions the companion/config emitters bound their
    // references with, so a simple name declared in two packages (prl's Miam) cannot be declared one
    // way in the patched model and referenced the other way in the generated config.
    RetrofitPatchEmitter emitter = new RetrofitPatchEmitter(
        index, resolution, reboundHolder[0], root,
        EmitContext.accessPackage(options.getConfigPackage()),
        constructorLimit, pathPrefix, pinnedNames, fqnOverrides);
    emitter.bindDeclaredTypes(declaredBindings);
    emitter.bindDefinitionFixedLists(linkedFixedLists[0]);
    // The State sheet's Name/TitleDisplay/Description are pinned onto the team's own constants only when
    // this run REUSES their enum — read off the very options the reuse decision above wrote, so the
    // patch and the emitted config cannot diverge about which enum (and which constants) is the State.
    // When a fresh State enum is generated instead it carries those three columns itself (EnumEmitter),
    // and the team's enum is just another model type whose constants must be left untouched.
    if (planOptions.getRetrofitStateClass() != null) {
      emitter.bindReusedStateEnum(
          planOptions.getRetrofitStateClassPackage() + "." + planOptions.getRetrofitStateClass(),
          planOptions.getRetrofitStateConstants());
    }
    RetrofitPatch patch = emitter.emit();
    writePatch(reportDir, patch);
    // Surface any synthesised-field name collisions the emitter skipped (finding B1) so they are not
    // silently lost — a skipped field means an existing member should carry that definition's @CCD.
    if (!emitter.gaps().isEmpty()) {
      System.err.printf("Retrofit: %d synthesised field(s) skipped on name collisions for %s "
          + "(see gap details):%n", emitter.gaps().size(), caseTypeId);
      emitter.gaps().forEach(g -> System.err.println("  - " + g.getDetail()));
    }
    // Fold the emitter's findings into the pipeline's collector and rewrite the reports over the ones the
    // conversion wrote a moment ago. Until this ran, every gap the patch emitter recorded — the dropped
    // collisions above and the uncovered retype refusals — existed only on stderr: gap-report.json listed
    // none of them, so the machine-readable record of what the retrofit could not express was incomplete
    // for exactly the findings a maintainer has to act on by hand.
    if (pipelineGaps[0] != null && !emitter.gaps().isEmpty()) {
      emitter.gaps().forEach(pipelineGaps[0]::add);
      new GapAndPassthroughWriter()
          .rewriteGapReports(reboundHolder[0], pipelineGaps[0], emitOptions);
    }
    return new Result(report, patch);
  }

  /**
   * Validates each {@code --type-package-hint TypeName=package} against the parsed model, erroring
   * clearly when the named type does not exist in that package (finding D1) rather than silently
   * ignoring a typo'd hint and falling back to refuse-to-guess.
   */
  private void validatePackageHints(ModelSourceIndex index, Map<String, String> hints) {
    for (Map.Entry<String, String> hint : hints.entrySet()) {
      if (!index.hasTopLevelTypeInPackage(hint.getKey(), hint.getValue())) {
        throw new IllegalArgumentException("--type-package-hint " + hint.getKey() + "="
            + hint.getValue() + ": no top-level type '" + hint.getKey() + "' is declared in package '"
            + hint.getValue() + "' in the model source. Check the type name and package.");
      }
    }
  }

  /**
   * The path prefix rooting the emitted patch at the model REPO root (patch-root consistency):
   * {@code modelSourceRoot} relative to {@code --model-repo-root}, e.g.
   * {@code service/src/main/java/}. Empty when no repo root is given (paths stay relative to the
   * source root — the historical behaviour) or the repo root IS the source root. Errors clearly when
   * the source root is not under the given repo root.
   */
  private String patchPathPrefix(ConversionOptions options) {
    Path repoRoot = options.getRetrofitModelRepoRoot();
    if (repoRoot == null) {
      return "";
    }
    Path source = modelSourceRoot.toAbsolutePath().normalize();
    Path repo = repoRoot.toAbsolutePath().normalize();
    if (!source.startsWith(repo)) {
      throw new IllegalArgumentException("--model-source-root (" + source
          + ") must be under --model-repo-root (" + repo + ")");
    }
    String prefix = repo.relativize(source).toString().replace('\\', '/');
    return prefix.isEmpty() ? "" : prefix + "/";
  }

  /**
   * A throwaway patch emitter to plan against, so the {@code CaseEventToComplexTypes} member walk sees
   * the model as the applied patch will leave it: the complex-type members the patch synthesises (see
   * {@link RetrofitPlannedSynthesis}) and the fields it re-declares as generated companions (see
   * {@link RetrofitPlannedRetypes}). Without the plans the walk resolves the PARSED source and emits a
   * getter reference to a field the patch has changed or has not added.
   *
   * <p>The plan comes from a throwaway link + rebind of the same definition: the emitter needs a
   * rebound model, and the real run's model is only produced by the conversion that consumes this plan.
   * That is not circular — {@link RetrofitPatchEmitter#planSynthesisedMembers} reads only the model's
   * case fields and complex types, which {@link RetrofitModelRebinder} passes through untouched and
   * which the linker builds before its {@code CaseEventToComplexTypes} pass — so this pass, which emits
   * nothing and writes no report, yields exactly the types the real run will plan against.
   *
   * @param declaredBindings the same declared-type bindings the real emit uses — the plan must resolve
   *                         a bound ID to the model class the patch will pin it onto, not to a companion
   * @return the throwaway planner, whose plans the real run's emitter re-derives identically
   */
  private RetrofitPatchEmitter planner(ConversionOptions planOptions,
      ModelSourceIndex index, PropertyResolver.Resolution resolution, ModelSourceIndex.Type root,
      int constructorLimit, String pathPrefix,
      Map<String, ModelSourceIndex.Type> declaredBindings) {
    GapCollector planGaps = new GapCollector();
    CaseTypeModel linked =
        new DefaultDefinitionLinker().link(ir, planOptions, planGaps);
    RetrofitModelRebinder planRebinder =
        new RetrofitModelRebinder(index, resolution, root, constructorLimit);
    planRebinder.bindDeclaredFixedLists(declaredBoundEnumIds(declaredBindings));
    CaseTypeModel rebound = planRebinder.rebind(linked, planGaps);
    // Same overrides as the real emit below, so the plan the member walk resolves against and the
    // patch that realises it cannot bind a type reference differently.
    RetrofitPatchEmitter planner = new RetrofitPatchEmitter(index, resolution, rebound, root,
        EmitContext.accessPackage(options.getConfigPackage()), constructorLimit, pathPrefix,
        RetrofitPinnedNames.empty(), planOptions.getRetrofitTypeFqnOverrides());
    planner.bindDeclaredTypes(declaredBindings);
    // The LINKED lists, not the rebound ones: the rebind drops exactly the lists a model enum already
    // serves, which is the set whose constants the label pin targets.
    planner.bindDefinitionFixedLists(linked.getFixedLists());
    return planner;
  }

  /**
   * The declared-bound IDs whose bound model type is an ENUM: the {@code FixedLists} half of the
   * binding, which {@link RetrofitModelRebinder} needs so it drops those lists (the patch pins the ID
   * onto the model enum, so emitting a companion enum for it too would duplicate the type).
   *
   * @param declaredBindings the bindings
   * @return the enum-valued IDs
   */
  private java.util.Set<String> declaredBoundEnumIds(
      Map<String, ModelSourceIndex.Type> declaredBindings) {
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    declaredBindings.forEach((id, type) -> {
      if (type.isEnum()) {
        ids.add(id);
      }
    });
    return ids;
  }

  /**
   * A reserved-name set minus the simple names of the declared-bound classes.
   *
   * <p>Reserving a bound class's name makes the namer bump every reference to it to {@code <Name>2} — a
   * companion nothing generates, so every such reference fails to compile. Exactly the desync documented
   * on {@link ModelSourceIndex#boundFixedListNames}, reached by the new binding path.
   *
   * @param reserved the reserved names
   * @param declaredBindings the declared-type bindings
   * @return the reserved names with the bound classes' names removed
   */
  private java.util.Set<String> withoutBoundNames(java.util.Set<String> reserved,
      Map<String, ModelSourceIndex.Type> declaredBindings) {
    java.util.Set<String> narrowed = new java.util.LinkedHashSet<>(reserved);
    declaredBindings.values().forEach(type -> narrowed.remove(type.simpleName));
    return narrowed;
  }

  /**
   * The model type names to reserve when naming generated fixed-list companions: every model type name
   * EXCEPT those a definition FixedList ID matches exactly.
   *
   * <p>An exact match is bound, not emitted ({@link RetrofitModelRebinder} drops the list because the
   * model already declares that top-level type), so reserving its name would make the namer bump the
   * reference to {@code <Id>2} — a companion nothing ever generates, leaving every reference to it
   * uncompilable. Both halves of that decision now read the same
   * {@link ModelSourceIndex#hasTopLevelType} predicate via
   * {@link ModelSourceIndex#boundFixedListNames}, so they cannot drift apart.
   *
   * @param index the parsed model index
   * @return the names to reserve
   */
  private java.util.Set<String> reservedFixedListNames(ModelSourceIndex index) {
    java.util.Set<String> reserved = new java.util.LinkedHashSet<>(index.allSimpleNames());
    reserved.removeAll(index.boundFixedListNames(
        sheetIds(uk.gov.hmcts.ccd.sdk.converter.ir.SheetName.FIXED_LISTS)));
    return reserved;
  }

  /**
   * The model ENUM names to reserve when naming generated complex-type companions: every model enum
   * name EXCEPT those whose definition complex type binds to an existing model class.
   *
   * <p>The complex-type pass reserves only enums (a complex type binding to a class emits no companion),
   * but a model declaring BOTH a class and an enum of one name — prl's {@code OrderAppliedFor} — hit the
   * same desync: bound to the class, yet renamed against the enum to a companion never emitted.
   *
   * @param index the parsed model index
   * @return the enum names to reserve
   */
  private java.util.Set<String> reservedComplexTypeNames(ModelSourceIndex index) {
    java.util.Set<String> reserved = new java.util.LinkedHashSet<>(index.enumSimpleNames());
    reserved.removeAll(index.boundComplexTypeNames(
        sheetIds(uk.gov.hmcts.ccd.sdk.converter.ir.SheetName.COMPLEX_TYPES), modelPackage));
    return reserved;
  }

  /**
   * The distinct {@code ID} column values on a sheet for this case type.
   *
   * @param sheet the sheet to read IDs from
   * @return the distinct IDs, in encounter order
   */
  private java.util.Set<String> sheetIds(uk.gov.hmcts.ccd.sdk.converter.ir.SheetName sheet) {
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    for (uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow row : ir.rowsForCaseType(sheet, caseTypeId)) {
      row.getString(uk.gov.hmcts.ccd.sdk.converter.ir.Columns.ID).ifPresent(ids::add);
    }
    return ids;
  }

  private void writePatch(Path reportDir, RetrofitPatch patch) {
    try {
      Files.createDirectories(reportDir);
      Files.writeString(reportDir.resolve("retrofit.patch"), patch.unifiedDiff());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing retrofit patch to " + reportDir, e);
    }
  }
}
