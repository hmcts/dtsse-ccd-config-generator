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
 * Pins the patch side of {@link RetrofitUnsuppressedGetters}: the emitter deletes exactly the
 * {@code @Getter(AccessLevel.NONE)} lines the placements resolved through, and drops the Lombok
 * imports only once nothing else in the file uses them.
 *
 * <p>The reliance is recorded by {@link ModelSourceIndex#hasResolvableGetter} (pinned in
 * {@code ModelSourceIndexTest}) and consumed by the three placements (pinned in
 * {@code RetrofitEventComplexTypeGraphTest}); what is pinned HERE is that a recorded un-suppression
 * really reaches the team's source as a line deletion, since a placement that emits
 * {@code CaseData::getFinalDecisionCaseData} against a still-suppressed field is a compile error in
 * their repo.
 */
class RetrofitPatchEmitterUnsuppressedGetterTest {

  private static final String MODEL_PACKAGE = "m";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  @Test
  void deletesTheSuppressionAndItsNowUnusedImports(@TempDir Path work) throws Exception {
    // sscs's SscsCaseData shape, reduced to the one suppressed unwrapped holder a placement needs.
    Path model = work.resolve("model/src");
    write(model, "m", "FinalDecision", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class FinalDecision {\n  private String appellantAttended;\n}\n");
    write(model, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private FinalDecision finalDecisionCaseData;\n"
        + "}\n");

    String diff = emitWithRepairedGetterFor(model, definition(work), "finalDecisionCaseData");

    assertThat(diff).as("the suppression the placement resolved through must be removed")
        .contains("-  @Getter(AccessLevel.NONE)");
    assertThat(diff).as("nothing else in the file references either type")
        .contains("-import lombok.Getter;")
        .contains("-import lombok.AccessLevel;");
  }

  @Test
  void keepsTheLombokImportsWhileAnotherSuppressionRemains(@TempDir Path work) throws Exception {
    // sscs's real case: 22 members suppress their getter and only the ones a placement needs are
    // un-suppressed, so both imports stay in use. Removing them would break the file.
    Path model = work.resolve("model/src");
    write(model, "m", "FinalDecision", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class FinalDecision {\n  private String appellantAttended;\n}\n");
    write(model, "m", "Unrelated", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class Unrelated {\n  private String other;\n}\n");
    write(model, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private FinalDecision finalDecisionCaseData;\n"
        + "  @JsonUnwrapped\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private Unrelated unrelated;\n"
        + "}\n");

    String diff = emitWithRepairedGetterFor(model, definition(work), "finalDecisionCaseData");

    assertThat(diff.lines().filter(l -> l.startsWith("-  @Getter(AccessLevel.NONE)")))
        .as("only the member a placement relied on is un-suppressed")
        .hasSize(1);
    assertThat(diff.lines().filter(l -> l.startsWith(" ") && l.contains("@Getter(AccessLevel.NONE)")))
        .as("the other member's suppression survives untouched")
        .hasSize(1);
    assertThat(diff).as("the other member still suppresses its getter")
        .doesNotContain("-import lombok.Getter;")
        .doesNotContain("-import lombok.AccessLevel;");
  }

  /**
   * Runs the retrofit pipeline the way {@link RetrofitConverter} does — repair installed after the
   * matcher's report-only pass, before any placement — and records a reliance on {@code member}'s
   * getter through the real predicate rather than hand-building a plan, so the test cannot pass on a
   * record the placements would never make.
   *
   * @return the emitted unified diff
   */
  private String emitWithRepairedGetterFor(Path modelRoot, Path definition, String member) {
    ConversionOptions options = ConversionOptions.builder()
        .inputs(List.of(definition))
        .caseTypeId("EXAMPLE")
        .modelPackage(MODEL_PACKAGE)
        .configPackage(CONFIG_PACKAGE)
        .overlaySuffixes(Map.of())
        .retrofit(true)
        .retrofitCaseDataClass("CaseData")
        .build();

    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    RetrofitMatcher matcher =
        new RetrofitMatcher(ir, "EXAMPLE", modelRoot.toAbsolutePath(), MODEL_PACKAGE, "CaseData");
    matcher.match();

    matcher.index().repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());
    assertThat(matcher.index().hasResolvableGetter(matcher.root(), member))
        .as("the placement must resolve %s's getter for anything to be recorded", member)
        .isTrue();

    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound = new RetrofitModelRebinder(matcher.index(), matcher.resolution(),
        matcher.root()).rebind(linked);
    return new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound, matcher.root(),
        CONFIG_PACKAGE, 0, "", RetrofitPinnedNames.empty(), Map.of()).emit().unifiedDiff();
  }

  /** A definition whose only case field is the unwrapped holder's flat leaf. */
  private Path definition(Path work) throws Exception {
    Path definition = work.resolve("definition");
    writeSheet(definition, "CaseType", "[{ \"ID\": \"EXAMPLE\", \"Name\": \"Example\","
        + " \"Description\": \"Suppressed-getter fixture\", \"JurisdictionID\": \"EX\" }]");
    writeSheet(definition, "CaseField",
        "[{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"appellantAttended\", \"FieldType\": \"Text\","
        + " \"Label\": \"Appellant attended\" }]");
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
