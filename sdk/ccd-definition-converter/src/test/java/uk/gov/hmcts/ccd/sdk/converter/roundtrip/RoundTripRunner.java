package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import uk.gov.hmcts.ccd.sdk.converter.Converter;
import uk.gov.hmcts.ccd.sdk.converter.ConverterFactory;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;
import uk.gov.hmcts.ccd.sdk.diff.ComparisonResult;
import uk.gov.hmcts.ccd.sdk.diff.NormalisingCcdConfigComparator;

/**
 * Runs one fixture end-to-end — JSON definition → generated Java → compile → SDK generator → JSON —
 * and returns the residual diff lines that survive {@link NormalisingCcdConfigComparator}
 * normalisation, deterministically normalised so they can be compared byte-for-byte against a
 * checked-in baseline.
 *
 * <p>Residual normalisation makes the lines stable across machines and runs. It is deliberately
 * conservative — it collapses only tokens that genuinely vary between runs or checkouts, never a
 * path that is real definition data (a callback URL's {@code /callbacks/...} suffix must stay
 * verbatim):</p>
 * <ul>
 *   <li>the per-run {@code @TempDir} work directory → {@code <work>};</li>
 *   <li>the machine-specific repository-root prefix → {@code <repo>} (a fixture value that echoes an
 *       input file path would otherwise differ per checkout);</li>
 *   <li>the OS temp root (e.g. {@code /tmp}, {@code java.io.tmpdir}) → {@code <tmp>};</li>
 *   <li>ISO-8601 date-time tokens → {@code <timestamp>};</li>
 *   <li>each residual's internal whitespace runs (including the embedded newlines that multi-line
 *       {@code ElementLabel} prose carries) are collapsed to a single space, so every residual is
 *       exactly one physical line in the baseline file — the baseline is a strict one-line-per-gap
 *       list that survives a write/read round-trip;</li>
 *   <li>lines are sorted lexicographically, so the comparator's row-iteration order (which the
 *       comparator does not otherwise guarantee) never perturbs the baseline.</li>
 * </ul>
 */
final class RoundTripRunner {

  // ISO-8601 date-times that could vary run to run.
  private static final Pattern TIMESTAMP =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z?");

  private RoundTripRunner() {
  }

  /**
   * Convert, compile, generate and diff the fixture, returning the sorted, path-/timestamp-stripped
   * residual diff lines. An empty list means the fixture round-trips clean.
   *
   * @param fixture the fixture to run
   * @param work    a fresh, empty working directory (per-run; usually a {@code @TempDir})
   */
  static List<String> residuals(Fixtures.Fixture fixture, Path work) {
    Path srcOut = work.resolve("src");
    Path classesOut = work.resolve("classes");
    Path defOut = work.resolve("definition");
    Path passthrough = work.resolve("passthrough");
    Path report = work.resolve("report");
    String modelPackage = "uk.gov.hmcts.roundtrip.model";
    String configPackage = "uk.gov.hmcts.roundtrip.config";

    // The prod/nonprod env suffixes every fixture uses, plus any fixture-specific overlay suffixes
    // (e.g. shutter fragments). Configuring a suffix makes both the converter's overlay guard and the
    // expected-definition builder honour the same predicate, so a nonprod run includes exactly the
    // matching fragment set on both sides.
    Map<String, OverlayCondition> suffixes = new LinkedHashMap<>();
    suffixes.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    suffixes.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    fixture.extraSuffixes().forEach((name, spec) -> suffixes.put(name, OverlayCondition.parse(spec)));

    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(fixture.input()))
        .caseTypeId(fixture.caseTypeId())
        .outputSrc(srcOut)
        .modelPackage(modelPackage)
        .configPackage(configPackage)
        .overlaySuffixes(suffixes)
        .passthroughDir(passthrough)
        .reportDir(report)
        .eventsPerConfig(40)
        .emitApplication(true)
        .allowGaps(true)
        .build();

    Converter converter = ConverterFactory.create(options);
    converter.convert(options);

    ClassLoader generated = GeneratedSourceCompiler.compile(srcOut, classesOut);
    Map<String, String> env = fixture.env();
    env.forEach(System::setProperty);
    try {
      GeneratorRunner.generate(
          generated, defOut, "uk.gov.hmcts.ccd.sdk", configPackage, modelPackage);
      uk.gov.hmcts.ccd.sdk.converter.passthrough.PassthroughMerger.merge(
          passthrough, defOut.resolve(fixture.caseTypeId()));
    } finally {
      env.keySet().forEach(System::clearProperty);
    }

    DefinitionIr ir = new JsonDefinitionReader().read(options,
        new uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector());
    Map<String, List<Map<String, Object>>> expected =
        ExpectedDefinitionBuilder.build(ir, fixture.caseTypeId(), options, env);

    Map<String, List<Map<String, Object>>> actual =
        NormalisingCcdConfigComparator.aggregateDirectory(defOut.resolve(fixture.caseTypeId()).toFile());

    ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);
    return normalise(result.getFailures(), work);
  }

  /**
   * Strips run-specific detail (the work directory, the repo-root and OS-temp prefixes, timestamps)
   * from each failure line and sorts the result, so two runs of the same fixture on different
   * machines produce byte-identical output. Longest prefixes are replaced first so a work dir nested
   * under the temp root collapses to {@code <work>}, not {@code <tmp>/...}.
   */
  private static List<String> normalise(List<String> failures, Path work) {
    String workPrefix = work.toAbsolutePath().normalize().toString();
    String repoPrefix = Fixtures.REPO_ROOT.toString();
    String tmpPrefix = Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
        .toAbsolutePath().normalize().toString();
    List<String> out = new ArrayList<>(failures.size());
    for (String line : failures) {
      String cleaned = line.replace(workPrefix, "<work>");
      cleaned = cleaned.replace(repoPrefix, "<repo>");
      cleaned = cleaned.replace(tmpPrefix, "<tmp>");
      cleaned = TIMESTAMP.matcher(cleaned).replaceAll("<timestamp>");
      // Collapse any internal whitespace run (multi-line ElementLabel prose carries embedded
      // newlines) to a single space so each residual is exactly one physical line.
      cleaned = cleaned.replaceAll("\\s+", " ").trim();
      out.add(cleaned);
    }
    out.sort(String::compareTo);
    return out;
  }
}
