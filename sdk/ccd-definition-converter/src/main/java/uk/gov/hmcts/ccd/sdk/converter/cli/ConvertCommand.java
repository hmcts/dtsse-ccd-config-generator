package uk.gov.hmcts.ccd.sdk.converter.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import uk.gov.hmcts.ccd.sdk.converter.Converter;
import uk.gov.hmcts.ccd.sdk.converter.ConverterFactory;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitConverter;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitMatcher;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitReport;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitReportWriter;

/**
 * Converts a JSON CCD definition into Java source targeting the ccd-config-generator SDK.
 *
 * <p>Run via {@code ./gradlew -p sdk :ccd-definition-converter:run --args='...'}.
 */
@Command(
    name = "convert-ccd-definition",
    mixinStandardHelpOptions = true,
    description = "Converts a JSON CCD definition to ccd-config-generator Java source.")
public class ConvertCommand implements Callable<Integer> {

  // The SDK's ConfigResolver scans this package for complex types; generated code that
  // lives elsewhere is invisible to the generator.
  static final String REQUIRED_PACKAGE_PREFIX = "uk.gov.hmcts";

  @Option(names = "--input", required = true, arity = "1..*",
      description = "Definition sheet directory (may repeat).")
  List<Path> inputs;

  @Option(names = "--case-type",
      description = "Case type ID to convert when the input holds more than one.")
  String caseTypeId;

  @Option(names = "--output-src",
      description = "Root directory generated Java sources are written under (generate mode).")
  Path outputSrc;

  @Option(names = "--model-package", required = true,
      description = "Package for CaseData, complex types and enums. In retrofit mode this is the "
          + "package the team's existing model lives in.")
  String modelPackage;

  @Option(names = {"--config-package", "--root-package"},
      description = "Root package the generated CCD config lives under (events in <root>.event, "
          + "page classes in <root>.event.page, access classes in <root>.access; tabs/search/"
          + "workbasket/case-type config beans directly in <root>). When omitted it is DERIVED from "
          + "--model-package: the model package is cut at its first '.model' segment and '.ccd' is "
          + "appended (uk.gov.hmcts.probate.model.ccd.raw -> uk.gov.hmcts.probate.ccd); a model "
          + "package with no '.model' segment gets '.ccd' appended verbatim. --root-package is an "
          + "alias for this option.")
  String configPackage;

  @Option(names = "--retrofit", defaultValue = "false",
      description = "Match the definition against the team's EXISTING Java model instead of "
          + "generating a fresh one. Without --report-only, also emits the annotation patch "
          + "(retrofit.patch) and companion config/enum/access sources.")
  boolean retrofit;

  @Option(names = "--report-only", defaultValue = "false",
      description = "In retrofit mode, emit the match report only (no patch, no companion "
          + "sources). Short-circuits to phase-1 behaviour.")
  boolean reportOnly;

  @Option(names = "--model-source-root",
      description = "Retrofit mode: the src/main/java root of the team's existing model.")
  Path modelSourceRoot;

  @Option(names = "--model-repo-root",
      description = "Retrofit mode: the model REPO root the emitted patch paths are relative to, so "
          + "every lane's patch roots the same way and bin/retrofit-verify applies it uniformly. "
          + "Defaults to --model-source-root (paths relative to the source root).")
  Path modelRepoRoot;

  @Option(names = "--model-class", defaultValue = "CaseData",
      description = "Retrofit mode: the root model class simple name (default CaseData).")
  String modelClass;

  @Option(names = "--type-package-hint",
      description = "Retrofit mode: disambiguate a simple type name declared in more than one model "
          + "sub-package, as TypeName=fully.qualified.package (may repeat). Consulted before the "
          + "resolver refuses to guess; an unknown hint (no such type in that package) errors.")
  Map<String, String> typePackageHints = new LinkedHashMap<>();

  @Option(names = "--overlay-suffix",
      description = "Overlay filename suffix mapping, suffix=[!]ENV_VAR:value (may repeat). "
          + "Defaults: prod=CCD_DEF_ENV:prod, nonprod=!CCD_DEF_ENV:prod.")
  Map<String, String> overlaySuffixes = new LinkedHashMap<>();

  @Option(names = "--passthrough-dir",
      description = "Where raw-JSON passthrough content is written. "
          + "Default: <output-src>/../resources/ccd-passthrough.")
  Path passthroughDir;

  @Option(names = "--report-dir", defaultValue = "build/ccd-conversion",
      description = "Where the gap report is written.")
  Path reportDir;

  @Option(names = "--events-per-config", defaultValue = "40",
      description = "Events per generated CCDConfig class.")
  int eventsPerConfig;

  @Option(names = "--emit-application", defaultValue = "false",
      description = "Also emit a minimal @SpringBootApplication for running the generator.")
  boolean emitApplication;

  @Option(names = "--allow-gaps", defaultValue = "false",
      description = "Complete with unresolvable gaps reported instead of failing.")
  boolean allowGaps;

  public static void main(String[] args) {
    System.exit(new CommandLine(new ConvertCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args));
  }

  @Override
  public Integer call() {
    if (retrofit) {
      return runRetrofit();
    }
    ConversionOptions options = buildOptions();
    Converter converter = ConverterFactory.create(options);
    Converter.ConversionResult result = converter.convert(options);
    System.out.printf("Generated %d source files under %s (%d gap findings, report in %s)%n",
        result.getFiles().size(), options.getOutputSrc(),
        result.getGaps().size(), options.getReportDir());
    return 0;
  }

  /**
   * Runs phase-1 retrofit: read the definition, resolve every data-bearing CaseField ID against the
   * team's existing model with the SDK's own rules, and write the match report. No source is
   * mutated and no patch is emitted (that is phase 2).
   *
   * @return the process exit code
   */
  private Integer runRetrofit() {
    ConversionOptions options = buildRetrofitOptions();
    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    String caseType = caseTypeId != null ? caseTypeId : soleCaseType(ir);

    if (reportOnly) {
      // Phase 1: report only.
      RetrofitReport report = new RetrofitMatcher(
          ir, caseType, modelSourceRoot, modelPackage, modelClass).match();
      RetrofitReportWriter.write(report, reportDir);
      if (report.isMapBased()) {
        System.out.printf("Retrofit not applicable for %s: %s (report in %s)%n",
            caseType, report.getNotApplicableReason(), reportDir);
      } else {
        System.out.printf(
            "Retrofit match for %s: %.1f%% resolved (%.1f%% exact), %d data-bearing fields "
                + "(%d exact, %d type-conflict, %d unmatched); report in %s%n",
            caseType, report.resolvedPercent(), report.exactPercent(),
            report.getDataBearingFields(), report.getExactMatches(),
            report.getTypeConflicts(), report.getUnmatchedDefinitionFields(), reportDir);
      }
      return 0;
    }

    // Phase 2: emit the annotation patch + companion sources.
    RetrofitConverter.Result result;
    try {
      result = new RetrofitConverter(
          ir, caseType, options, modelSourceRoot, modelPackage, modelClass).run(reportDir);
    } catch (IllegalStateException e) {
      // Map-based / not-applicable: the report was already written; surface the reason.
      System.err.println(e.getMessage() + " (report in " + reportDir + ")");
      return 1;
    }
    RetrofitReport report = result.report();
    long touchedFiles = result.patch().files().size();
    System.out.printf(
        "Retrofit patch for %s: %.1f%% resolved (%.1f%% exact); %d model file(s) patched "
            + "(retrofit.patch in %s), companion sources under %s%n",
        caseType, report.resolvedPercent(), report.exactPercent(),
        touchedFiles, reportDir, outputSrc);
    return 0;
  }

  private String soleCaseType(DefinitionIr ir) {
    List<String> ids = ir.rows(uk.gov.hmcts.ccd.sdk.converter.ir.SheetName.CASE_TYPE).stream()
        .map(r -> r.getString("ID").orElse(null))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.size() == 1) {
      return ids.get(0);
    }
    throw parameterError("Multiple or no case types present " + ids
        + "; specify one with --case-type");
  }

  /**
   * Validates retrofit-mode options and assembles a definition-reading {@link ConversionOptions}.
   * Generate-mode options ({@code --output-src}, {@code --config-package}) are not required.
   *
   * @return the options for reading the definition
   */
  ConversionOptions buildRetrofitOptions() {
    requirePackage("--model-package", modelPackage);
    if (modelSourceRoot == null || !Files.isDirectory(modelSourceRoot)) {
      throw parameterError("--model-source-root must be an existing directory: " + modelSourceRoot);
    }
    for (Path input : inputs) {
      if (!Files.isDirectory(input)) {
        throw parameterError("--input directory does not exist: " + input);
      }
    }
    ConversionOptions.ConversionOptionsBuilder builder = ConversionOptions.builder()
        .inputs(inputs)
        .caseTypeId(caseTypeId)
        .modelPackage(modelPackage)
        .overlaySuffixes(defaultOverlays())
        .reportDir(reportDir)
        .eventsPerConfig(eventsPerConfig);

    if (reportOnly) {
      // Phase 1 needs neither companion output nor a config package.
      return builder.build();
    }

    // Phase 2: companion sources are emitted, so the generate-mode outputs are required, plus the
    // retrofit target the config binds its typed getters to.
    if (outputSrc == null) {
      throw parameterError("--output-src is required for phase-2 retrofit (companion sources); "
          + "add --report-only to emit the report alone");
    }
    // In retrofit mode --model-package is the team's EXISTING model package, so a derived root would
    // land the companion config beside the team's model; that is intentional and matches the
    // maintainer's "companions in the service's main source tree" directive. --root-package /
    // --config-package still overrides when the team wants a specific home.
    String rootPackage = configPackage != null ? configPackage : deriveRootPackage(modelPackage);
    requirePackage("--config-package", rootPackage);
    if (eventsPerConfig < 1) {
      throw parameterError("--events-per-config must be at least 1");
    }
    return builder
        .outputSrc(outputSrc)
        .configPackage(rootPackage)
        .passthroughDir(passthroughDir != null
            ? passthroughDir
            : outputSrc.resolve("../resources/ccd-passthrough").normalize())
        .emitApplication(emitApplication)
        .allowGaps(allowGaps)
        .retrofit(true)
        .retrofitCaseDataClass(modelClass)
        .retrofitTypePackageHints(new LinkedHashMap<>(typePackageHints))
        .retrofitModelRepoRoot(modelRepoRoot)
        .build();
  }

  private Map<String, OverlayCondition> defaultOverlays() {
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    overlaySuffixes.forEach((suffix, spec) -> {
      try {
        overlays.put(suffix, OverlayCondition.parse(spec));
      } catch (IllegalArgumentException e) {
        throw parameterError("--overlay-suffix " + suffix + ": " + e.getMessage());
      }
    });
    return overlays;
  }

  /**
   * Validates the raw picocli fields and assembles the immutable options object.
   *
   * @return the validated options
   * @throws CommandLine.ParameterException on any invalid option combination
   */
  ConversionOptions buildOptions() {
    if (outputSrc == null) {
      throw parameterError("--output-src is required in generate mode");
    }
    requirePackage("--model-package", modelPackage);
    String rootPackage = configPackage != null ? configPackage : deriveRootPackage(modelPackage);
    requirePackage("--config-package", rootPackage);
    for (Path input : inputs) {
      if (!Files.isDirectory(input)) {
        throw parameterError("--input directory does not exist: " + input);
      }
    }
    if (eventsPerConfig < 1) {
      throw parameterError("--events-per-config must be at least 1");
    }

    Map<String, OverlayCondition> overlays = defaultOverlays();

    return ConversionOptions.builder()
        .inputs(inputs)
        .caseTypeId(caseTypeId)
        .outputSrc(outputSrc)
        .modelPackage(modelPackage)
        .configPackage(rootPackage)
        .overlaySuffixes(overlays)
        .passthroughDir(passthroughDir != null
            ? passthroughDir
            : outputSrc.resolve("../resources/ccd-passthrough").normalize())
        .reportDir(reportDir)
        .eventsPerConfig(eventsPerConfig)
        .emitApplication(emitApplication)
        .allowGaps(allowGaps)
        .build();
  }

  /**
   * Derives the root config package from the model package when neither {@code --root-package} nor
   * {@code --config-package} is given.
   *
   * <p>The rule keeps the generated config a sibling of the model, under the service's own base
   * package, exactly where the reference services keep theirs ({@code uk.gov.hmcts.divorce.divorce
   * case} beside {@code …divorcecase.model}): the model package is truncated at its <em>first</em>
   * {@code model} path segment and {@code .ccd} is appended, so
   * {@code uk.gov.hmcts.probate.model.ccd.raw} yields {@code uk.gov.hmcts.probate.ccd}. A model
   * package with no {@code model} segment simply gets {@code .ccd} appended.
   *
   * @param modelPackage the {@code --model-package} value
   * @return the derived root config package
   */
  static String deriveRootPackage(String modelPackage) {
    String[] segments = modelPackage.split("\\.");
    StringBuilder base = new StringBuilder();
    for (String segment : segments) {
      if (segment.equals("model")) {
        break;
      }
      if (base.length() > 0) {
        base.append('.');
      }
      base.append(segment);
    }
    return base + ".ccd";
  }

  private void requirePackage(String option, String value) {
    if (!value.equals(REQUIRED_PACKAGE_PREFIX)
        && !value.startsWith(REQUIRED_PACKAGE_PREFIX + ".")) {
      throw parameterError(option + " must live under " + REQUIRED_PACKAGE_PREFIX
          + " (the SDK only scans that package for complex types) but was " + value);
    }
  }

  private CommandLine.ParameterException parameterError(String message) {
    return new CommandLine.ParameterException(new CommandLine(this), message);
  }
}
