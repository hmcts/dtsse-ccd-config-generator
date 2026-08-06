package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.link.DefaultDefinitionLinker;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;

/**
 * Pins the patch emitter's type-reference binding against the run's
 * {@code ConversionOptions.retrofitTypeFqnOverrides} — the same map the companion and config
 * emitters bind with.
 *
 * <p>The two used to resolve independently and could disagree: prl declares {@code Miam} in both
 * {@code complextypes.applicationtab} and {@code complextypes.citizen.response.miam}. The overrides
 * map honours {@code --type-package-hint} and chose the citizen one, so the config emitter emitted
 * {@code Miam::getAttendedMiam}; the patch emitter went through
 * {@link ModelSourceIndex#fqnForSimpleName}, which knows nothing of the hint and — finding neither
 * candidate under the model package — took the first arbitrarily, declaring the synthesised field as
 * {@code applicationtab.Miam}, which has no such member. The lane failed to compile with
 * {@code no suitable method found for mandatory(Miam::getAttendedMiam)}.
 *
 * <p>Both directions are asserted (pin to {@code pkga}, pin to {@code pkgb}) so the test proves the
 * map is what decides, rather than accidentally agreeing with the index's arbitrary pick.
 */
class RetrofitPatchEmitterTypeFqnOverrideTest {

  private static final String MODEL_PACKAGE = "m";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  @Test
  void declaresASynthesisedFieldWithTheOverriddenPackageForAnAmbiguousSimpleName(
      @TempDir Path work) throws Exception {
    Path model = ambiguousModel(work);
    Path definition = definitionReferencingDupType(work);

    assertThat(synthesisedFieldTypeIn(model, definition, Map.of("DupType", "m.pkgb.DupType")))
        .as("the patch must declare the type the emitters' shared FQN decision names")
        .isEqualTo("m.pkgb.DupType");
    assertThat(synthesisedFieldTypeIn(model, definition, Map.of("DupType", "m.pkga.DupType")))
        .as("the override decides — not the index's arbitrary pick among ambiguous candidates")
        .isEqualTo("m.pkga.DupType");
  }

  /**
   * Runs matcher → linker → rebind → patch emit with the given overrides and returns the FQN the
   * emitted patch declares the synthesised {@code dupHolder} field with. The type is qualified in the
   * patch either inline or via an added import, so both forms are accepted.
   */
  private String synthesisedFieldTypeIn(Path modelRoot, Path definition,
      Map<String, String> overrides) {
    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(definition))
        .caseTypeId("EXAMPLE")
        .modelPackage(MODEL_PACKAGE)
        .configPackage(CONFIG_PACKAGE)
        .overlaySuffixes(Map.of())
        .retrofit(true)
        .retrofitCaseDataClass("CaseData")
        .retrofitTypeFqnOverrides(overrides)
        .build();

    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitMatcher matcher =
        new RetrofitMatcher(ir, "EXAMPLE", modelRoot, MODEL_PACKAGE, "CaseData");
    matcher.match();
    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound = new RetrofitModelRebinder(matcher.index(), matcher.resolution(),
        matcher.root()).rebind(linked);

    String diff = new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound,
        matcher.root(), CONFIG_PACKAGE, 0, "", RetrofitPinnedNames.empty(), overrides)
        .emit().unifiedDiff();

    assertThat(diff).as("dupHolder must be synthesised for this test to mean anything")
        .contains("dupHolder");
    for (String candidate : List.of("m.pkga.DupType", "m.pkgb.DupType")) {
      if (diff.contains("+import " + candidate + ";") || diff.contains(candidate + " dupHolder")) {
        return candidate;
      }
    }
    throw new AssertionError("patch bound dupHolder to neither DupType:\n" + diff);
  }

  /** A model whose root CaseData has no dupHolder, and two same-named types in sibling packages. */
  private Path ambiguousModel(Path work) throws Exception {
    Path src = work.resolve("model/src");
    write(src, "m", "CaseData", "package m;\n\npublic class CaseData {\n"
        + "  private String applicantName;\n"
        + "  public String getApplicantName() { return applicantName; }\n}\n");
    write(src, "m/pkga", "DupType", "package m.pkga;\n\npublic class DupType {\n"
        + "  private String alpha;\n  public String getAlpha() { return alpha; }\n}\n");
    write(src, "m/pkgb", "DupType", "package m.pkgb;\n\npublic class DupType {\n"
        + "  private String beta;\n  public String getBeta() { return beta; }\n}\n");
    return src.toAbsolutePath();
  }

  /** A definition with a definition-only field typed by the ambiguous complex type. */
  private Path definitionReferencingDupType(Path work) throws Exception {
    Path definition = work.resolve("definition");
    writeSheet(definition, "CaseType", "[{ \"ID\": \"EXAMPLE\", \"Name\": \"Example\","
        + " \"Description\": \"Ambiguous-type fixture\", \"JurisdictionID\": \"EX\" }]");
    writeSheet(definition, "CaseField",
        "[{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"applicantName\", \"FieldType\": \"Text\","
        + " \"Label\": \"Applicant name\" },\n"
        + " { \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"dupHolder\", \"FieldType\": \"DupType\","
        + " \"Label\": \"Definition-only field of an ambiguous complex type\" }]");
    writeSheet(definition, "ComplexTypes",
        "[{ \"ID\": \"DupType\", \"ListElementCode\": \"alpha\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Alpha\" }]");
    return definition.toAbsolutePath();
  }

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), body);
  }

  private static void writeSheet(Path definition, String sheet, String json) throws Exception {
    Path dir = definition.resolve(sheet);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(sheet + ".json"), json);
  }
}
