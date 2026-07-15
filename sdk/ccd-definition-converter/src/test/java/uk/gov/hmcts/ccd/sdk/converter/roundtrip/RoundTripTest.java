package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import uk.gov.hmcts.ccd.sdk.converter.Converter;
import uk.gov.hmcts.ccd.sdk.converter.ConverterFactory;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;
import uk.gov.hmcts.ccd.sdk.diff.ComparisonResult;
import uk.gov.hmcts.ccd.sdk.diff.NormalisingCcdConfigComparator;

/**
 * The core correctness proof for the converter: JSON definition → generated Java → compile →
 * run the SDK generator → JSON, then semantically diff the regenerated definition against the
 * input with {@link NormalisingCcdConfigComparator}. Any difference the documented
 * normalisation rules do not explain is a residual.
 *
 * <p>The bundled {@code golden/*} fixtures must round-trip with <em>zero</em> residuals. The seven
 * real service fixtures (git submodules under {@code test-projects/}/{@code test-builds/}) each
 * gate their residuals against a checked-in per-fixture baseline under
 * {@code src/test/resources/roundtrip-baselines/}: the test passes iff the observed residuals equal
 * the baseline exactly. A <em>new</em> diff (regression) fails; a <em>vanished</em> diff
 * (improvement) also fails, demanding a baseline refresh — the ratchet can only tighten. The
 * baseline file <em>is</em> the enumerated list of that fixture's open gaps (see
 * {@code docs/json-conversion-fidelity.md}); regenerate baselines with {@link GenerateGoldenFiles}.
 *
 * <p>Each fixture's case skips (rather than fails) when its submodule is not initialised, so a fresh
 * checkout without submodules still builds.
 */
@Tag("round-trip")
class RoundTripTest {

  /** Consumed by {@link #realFixtureResidualsMatchBaseline} via {@link FieldSource}. */
  static final List<Fixtures.Fixture> FIXTURES = Fixtures.ALL;

  @Test
  void minimalGoldenFixtureRoundTrips(@TempDir Path work) {
    Path input = Path.of("src/test/resources/golden/minimal/input").toAbsolutePath();
    assertGoldenRoundTrips(input, "Minimal", work, Map.of("CCD_DEF_ENV", "nonprod"));
  }

  @Test
  void minimalGoldenFixtureRoundTripsInProdEnvironment(@TempDir Path work) {
    Path input = Path.of("src/test/resources/golden/minimal/input").toAbsolutePath();
    assertGoldenRoundTrips(input, "Minimal", work, Map.of("CCD_DEF_ENV", "prod"));
  }

  @Test
  void clusteredFieldsRoundTripViaJsonUnwrapped(@TempDir Path work) {
    Path input = Path.of("src/test/resources/golden/clustered/input").toAbsolutePath();
    assertGoldenRoundTrips(input, "Clustered", work, Map.of("CCD_DEF_ENV", "nonprod"));
  }

  /**
   * Every real fixture: convert, compile, generate, diff and assert the surviving residuals equal
   * the fixture's checked-in baseline exactly. This genuinely gates CI — a regression on any fixture
   * fails the build.
   */
  @ParameterizedTest(name = "{0}")
  @FieldSource("FIXTURES")
  void realFixtureResidualsMatchBaseline(Fixtures.Fixture fixture, @TempDir Path work) {
    assumeTrue(Files.isDirectory(fixture.input()),
        fixture.name() + " submodule not initialised; skipping");

    List<String> residuals = RoundTripRunner.residuals(fixture, work);
    String failure = Baselines.diff(fixture.name(), residuals);
    if (failure != null) {
      throw new AssertionError(failure);
    }
  }

  /**
   * Runs a bundled golden fixture and asserts it round-trips with no residuals. The golden fixtures
   * are always present (not submodules), so they never skip and always gate.
   */
  private static void assertGoldenRoundTrips(Path input, String caseTypeId, Path work,
                                             Map<String, String> env) {
    Path srcOut = work.resolve("src");
    Path classesOut = work.resolve("classes");
    Path defOut = work.resolve("definition");
    Path passthrough = work.resolve("passthrough");
    Path report = work.resolve("report");
    String modelPackage = "uk.gov.hmcts.roundtrip.model";
    String configPackage = "uk.gov.hmcts.roundtrip.config";

    Map<String, OverlayCondition> suffixes = new java.util.LinkedHashMap<>();
    suffixes.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    suffixes.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));

    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(input))
        .caseTypeId(caseTypeId)
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
    env.forEach(System::setProperty);
    try {
      GeneratorRunner.generate(
          generated, defOut, "uk.gov.hmcts.ccd.sdk", configPackage, modelPackage);
      uk.gov.hmcts.ccd.sdk.converter.passthrough.PassthroughMerger.merge(
          passthrough, defOut.resolve(caseTypeId));
    } finally {
      env.keySet().forEach(System::clearProperty);
    }

    DefinitionIr ir = new JsonDefinitionReader().read(options,
        new uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector());
    Map<String, List<Map<String, Object>>> expected =
        ExpectedDefinitionBuilder.build(ir, caseTypeId, options, env);
    Map<String, List<Map<String, Object>>> actual =
        NormalisingCcdConfigComparator.aggregateDirectory(defOut.resolve(caseTypeId).toFile());

    ComparisonResult result = NormalisingCcdConfigComparator.compare(expected, actual);
    if (!result.matches()) {
      throw new AssertionError(
          "Round-trip diff for " + caseTypeId + " (env=" + env + "):\n" + result.report());
    }
  }
}
