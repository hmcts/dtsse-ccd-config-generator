package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.difflib.patch.Patch;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitConverter;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitPatch;
import uk.gov.hmcts.ccd.sdk.diff.ComparisonResult;
import uk.gov.hmcts.ccd.sdk.diff.NormalisingCcdConfigComparator;

/**
 * The phase-2 gate: a full retrofit ROUND-TRIP against the fake team model under
 * {@code retrofit/roundtrip}. It runs {@link RetrofitConverter} (matcher → rebind → companion
 * sources → annotation patch), APPLIES the emitted patch to a copy of the model in-memory (parsing
 * the unified diff and reconstructing the patched files — no {@code git}), compiles the patched
 * model together with the companion sources in-JVM (reusing {@link GeneratedSourceCompiler}'s
 * javax.tools + Lombok machinery), runs the SDK generator ({@link GeneratorRunner}), then diffs the
 * regenerated definition against the input with {@link NormalisingCcdConfigComparator}. Zero
 * unexplained diffs is the bar.
 *
 * <p>Coverage matrix (all in one round-trip):
 * <ul>
 *   <li><b>exact match + label + access</b> — {@code applicantName} (Text, @CCD label + access);</li>
 *   <li><b>type conflict</b> — {@code applicantEmail} (def Email, model String → typeOverride);</li>
 *   <li><b>concrete-wrapper collection</b> — {@code documents} ({@code List<DocItem>} →
 *       typeParameterOverride "Document");</li>
 *   <li><b>unmatched Java field</b> — {@code internalScratch} → {@code @CCD(ignore = true)};</li>
 *   <li><b>synthesised definition-only field placed on an event</b> — {@code extraCaseNote};</li>
 *   <li><b>complex type with member labels</b> — {@code Party.fullName}/{@code partyEmail};</li>
 *   <li><b>reusable @JsonProperty state enum</b> — {@code State} ({@code PREPARE_FOR_HEARING});</li>
 *   <li><b>fixed list matched to an existing model enum</b> — {@code ClaimType}.</li>
 * </ul>
 */
@Tag("round-trip")
class RetrofitRoundTripTest {

  private static final Path FIXTURE =
      Path.of("src/test/resources/retrofit/roundtrip").toAbsolutePath();

  @Test
  void retrofitRoundTripsWithZeroDiffs(@TempDir Path work) throws Exception {
    runRoundTrip(work, 0, false);
  }

  /**
   * B2 end-to-end proof: with a low constructor-limit the root {@code CaseData}
   * ({@code @Builder}/{@code @AllArgsConstructor}) trips the JVM/Lombok limit, so the synthesised
   * definition-only field is moved into an added {@code CaseDataExtra} class reached via a
   * prefix-less {@code @JsonUnwrapped} member. Because prefix-less unwrapping flattens member names
   * verbatim, the regenerated definition is byte-identical — the same zero-diff bar — proving the
   * CaseDataExtra placement, the config's clustered reference through the unwrapped parent, and the
   * new-file patch all round-trip.
   */
  @Test
  void retrofitRoundTripsViaCaseDataExtraWhenConstructorLimitTripped(@TempDir Path work)
      throws Exception {
    runRoundTrip(work, 3, true);
  }

  private void runRoundTrip(Path work, int constructorLimit, boolean expectCaseDataExtra)
      throws Exception {
    Path input = FIXTURE.resolve("input");
    Path modelSrc = FIXTURE.resolve("model/src");
    String caseTypeId = "RETRO";
    String modelPackage = "uk.gov.hmcts.rt.model";
    String configPackage = "uk.gov.hmcts.rt.config";

    Path companionSrc = work.resolve("companion");
    Path patchedModel = work.resolve("model");
    Path combinedSrc = work.resolve("src");
    Path classesOut = work.resolve("classes");
    Path defOut = work.resolve("definition");
    Path passthrough = work.resolve("passthrough");
    Path report = work.resolve("report");

    Map<String, OverlayCondition> suffixes = new java.util.LinkedHashMap<>();
    suffixes.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    suffixes.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));

    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(input))
        .caseTypeId(caseTypeId)
        .outputSrc(companionSrc)
        .modelPackage(modelPackage)
        .configPackage(configPackage)
        .overlaySuffixes(suffixes)
        .passthroughDir(passthrough)
        .reportDir(report)
        .eventsPerConfig(40)
        .emitApplication(true)
        .allowGaps(true)
        .retrofit(true)
        .retrofitCaseDataClass("CaseData")
        .retrofitConstructorLimit(constructorLimit)
        .build();

    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitConverter.Result result = new RetrofitConverter(
        ir, caseTypeId, options, modelSrc, modelPackage, "CaseData").run(report);

    RetrofitPatch patch = result.patch();
    assertThat(patch.unifiedDiff()).isNotBlank();
    // Sanity-check the patch content covers the taxonomy the model needs annotating for.
    assertThat(patch.unifiedDiff())
        .contains("@CCD(")
        .contains("typeOverride = FieldType.Email")
        .contains("typeParameterOverride = \"Document\"")
        .contains("@CCD(ignore = true)")
        .contains("extraCaseNote");

    if (expectCaseDataExtra) {
      // B2: the synthesised extraCaseNote is moved to an added CaseDataExtra class and the root gains
      // one prefix-less @JsonUnwrapped member — NOT an in-class synthesised block.
      assertThat(patch.unifiedDiff())
          .contains("new file mode")
          .contains("CaseDataExtra.java")
          .contains("@JsonUnwrapped private CaseDataExtra caseDataExtra;");
      assertThat(patch.files())
          .anySatisfy(f -> assertThat(f.relativePath()).endsWith("CaseDataExtra.java"));
    } else {
      assertThat(patch.unifiedDiff()).doesNotContain("CaseDataExtra");
    }

    // Apply the patch to a copy of the model tree (parsing the unified diff, no git), then place the
    // patched model and the companion sources under one compile root.
    copyTree(modelSrc, patchedModel);
    applyUnifiedDiff(patch, patchedModel);
    copyTree(patchedModel, combinedSrc);
    copyTree(companionSrc, combinedSrc);

    ClassLoader generated = GeneratedSourceCompiler.compile(combinedSrc, classesOut);
    Map<String, String> env = Map.of("CCD_DEF_ENV", "nonprod");
    env.forEach(System::setProperty);
    try {
      GeneratorRunner.generate(
          generated, defOut, "uk.gov.hmcts.ccd.sdk", configPackage, modelPackage);
      uk.gov.hmcts.ccd.sdk.converter.passthrough.PassthroughMerger.merge(
          passthrough, defOut.resolve(caseTypeId));
    } finally {
      env.keySet().forEach(System::clearProperty);
    }

    Map<String, List<Map<String, Object>>> expected =
        ExpectedDefinitionBuilder.build(ir, caseTypeId, options, env);
    Map<String, List<Map<String, Object>>> actual =
        NormalisingCcdConfigComparator.aggregateDirectory(defOut.resolve(caseTypeId).toFile());

    ComparisonResult diff = NormalisingCcdConfigComparator.compare(expected, actual);
    if (!diff.matches()) {
      throw new AssertionError("Retrofit round-trip diff for " + caseTypeId + ":\n" + diff.report());
    }
  }

  /**
   * Applies the emitted patch to the copied model tree by parsing its unified diff and rebuilding
   * each touched file — verifying the emitted diff is well-formed and self-consistent, rather than
   * just writing {@link RetrofitPatch.FilePatch#patchedContent()} back (which would not exercise the
   * diff text at all).
   */
  private void applyUnifiedDiff(RetrofitPatch patch, Path modelRoot) throws Exception {
    UnifiedDiff unified = UnifiedDiffReader.parseUnifiedDiff(
        new ByteArrayInputStream(patch.unifiedDiff().getBytes(StandardCharsets.UTF_8)));
    for (var fileDiff : unified.getFiles()) {
      String fromFile = fileDiff.getFromFile();
      // A new file (git "new file mode") has /dev/null as its from-file; take the target path from
      // the to-file and start from empty content, mirroring how `git apply` creates the file
      // (finding B2's added CaseDataExtra class).
      boolean newFile = fromFile == null || fromFile.endsWith("/dev/null")
          || fromFile.equals("/dev/null");
      String relative = stripPrefix(newFile ? fileDiff.getToFile() : fromFile);
      Path target = modelRoot.resolve(relative);
      // Split into git's unified-diff line model (a trailing newline is a terminator, not an empty
      // final line) — the same model RetrofitPatchEmitter.splitGitLines emits the diff against — so
      // the diff's hunk line numbers line up exactly with the source it was generated from. (Using
      // a naive split("\n", -1) here would reintroduce the phantom trailing line and mis-apply any
      // end-of-file hunk, e.g. the synthesised-fields block.)
      String source = newFile ? "" : Files.readString(target);
      boolean trailingNewline = newFile || source.endsWith("\n");
      List<String> original = uk.gov.hmcts.ccd.sdk.converter.retrofit.RetrofitPatchEmitter
          .splitGitLines(source);
      Patch<String> filePatch = fileDiff.getPatch();
      List<String> patched = filePatch.applyTo(original);
      if (newFile && target.getParent() != null) {
        Files.createDirectories(target.getParent());
      }
      Files.writeString(target, String.join("\n", patched) + (trailingNewline ? "\n" : ""));
    }
  }

  private static String stripPrefix(String path) {
    if (path.startsWith("a/") || path.startsWith("b/")) {
      return path.substring(2);
    }
    return path;
  }

  private static void copyTree(Path from, Path to) throws Exception {
    try (Stream<Path> walk = Files.walk(from)) {
      List<Path> files = walk.filter(Files::isRegularFile).collect(
          java.util.stream.Collectors.toList());
      for (Path file : files) {
        Path rel = from.relativize(file);
        Path dest = to.resolve(rel.toString());
        Files.createDirectories(dest.getParent());
        Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
