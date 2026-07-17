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
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;

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
    Map<String, String> fqnOverrides = new java.util.LinkedHashMap<>();
    fqnOverrides.putAll(index.caseInsensitiveClassAliases());
    fqnOverrides.putAll(index.topLevelFqnsOutside(modelPackage, packageHints));
    final ConversionOptions emitOptions = effective
        .retrofitTypeFqnOverrides(fqnOverrides)
        // Reserve existing model names so a generated companion's PascalCase name (finding #3/#4) is
        // suffixed rather than colliding with an unrelated existing type of the same name. The two
        // companion kinds reserve different sets because each binds to (and so never re-emits) a
        // model type it can reuse:
        //   - a complex-type companion (a class) is emitted only when no model CLASS of that ID
        //     exists (RetrofitComplexTypeEmitter binds the rest in place), so it can only clash with a
        //     model ENUM (the definition 'benefit' complex type vs the domain enum Benefit). Reserving
        //     class names here would wrongly suffix a reference to a bound, never-emitted type.
        //   - a fixed-list companion (an enum) reuses a model enum only on an EXACT list-ID match
        //     (rebind's hasEnum(id)); a machine 'FL_'/case-shifted ID (FL_amendReason, eventType) does
        //     not match its PascalCased model twin (AmendReason, EventType), so a fresh companion is
        //     emitted and can clash with EITHER a model enum or class. Reserving all model names is
        //     safe: the only names it must stay free of are exact-ID reuses, which emit no companion.
        .retrofitReservedComplexTypeNames(index.enumSimpleNames())
        .retrofitReservedFixedListNames(index.allSimpleNames())
        .build();

    int constructorLimit = options.getRetrofitConstructorLimit();
    String pathPrefix = patchPathPrefix(options);
    RetrofitModelRebinder rebinder =
        new RetrofitModelRebinder(index, resolution, root, constructorLimit);

    // Emit companion sources: the Converter runs reader → linker (retrofit: unclustered) → rebind →
    // emitters. The rebind rewrites getters/fields onto the team's model and drops any FixedList
    // reused from a model enum. We capture the rebound model for the patch emitter.
    CaseTypeModel[] reboundHolder = new CaseTypeModel[1];
    // Append the companion complex-type emitter for the DEFINITION-ONLY complex types (those with no
    // model class). It needs the parsed index + model package, so it is passed in here rather than
    // wired in the static ConverterFactory.
    Converter converter = ConverterFactory.create(emitOptions,
            List.of(new RetrofitComplexTypeEmitter(index, modelPackage)))
        .toBuilder()
        .modelTransform(model -> {
          CaseTypeModel rebound = rebinder.rebind(model);
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
    RetrofitPatchEmitter emitter = new RetrofitPatchEmitter(
        index, resolution, reboundHolder[0], root,
        uk.gov.hmcts.ccd.sdk.converter.api.EmitContext.accessPackage(options.getConfigPackage()),
        constructorLimit, pathPrefix);
    RetrofitPatch patch = emitter.emit();
    writePatch(reportDir, patch);
    // Surface any synthesised-field name collisions the emitter skipped (finding B1) so they are not
    // silently lost — a skipped field means an existing member should carry that definition's @CCD.
    if (!emitter.gaps().isEmpty()) {
      System.err.printf("Retrofit: %d synthesised field(s) skipped on name collisions for %s "
          + "(see gap details):%n", emitter.gaps().size(), caseTypeId);
      emitter.gaps().forEach(g -> System.err.println("  - " + g.getDetail()));
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

  private void writePatch(Path reportDir, RetrofitPatch patch) {
    try {
      Files.createDirectories(reportDir);
      Files.writeString(reportDir.resolve("retrofit.patch"), patch.unifiedDiff());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing retrofit patch to " + reportDir, e);
    }
  }
}
