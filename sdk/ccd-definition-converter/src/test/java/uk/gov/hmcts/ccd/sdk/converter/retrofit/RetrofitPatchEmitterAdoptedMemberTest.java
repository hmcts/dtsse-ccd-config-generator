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
 * Pins the patch side of the adoption path: a definition complex-type member the class ALREADY declares a
 * provably-identical field for is annotated with the definition's {@code @CCD} in place, not marked
 * {@code @CCD(ignore = true)}.
 *
 * <p>Civil's {@code GAHearingDetails} is the case. Its definition type declares eleven members whose
 * {@code ListElementCode} is PascalCase ({@code HearingDuration}, {@code SupportRequirement}, …) while the
 * class declares them camelCase and pins each PascalCase id on its {@code @JsonCreator} parameter. Two
 * halves of the emitter then disagreed about the same field: {@code planComplexTypeMembers} found no
 * property for the PascalCase id (because {@code PropertyResolver} reads {@code @JsonProperty} only off
 * the FIELD) and routed the member to synthesis, where the declared-name collision dropped it — while its
 * unmatched-Java loop, working from the field's own camelCase {@code ccdId}, concluded the field belonged
 * to no definition row and ignored it. The class came out of the patch carrying
 * {@code @JsonProperty("HearingDuration") @CCD(ignore = true)} on the very field the definition's row
 * needed, so the SDK emitted no row for it: eleven residual {@code ComplexTypes} diff lines on this class
 * plus one on {@code GeneralApplication.CaseAccessCategory}.
 *
 * <p>What is pinned here is the whole decision reaching the team's source correctly — the definition's
 * label on the field, the id pinned so the creator-parameter-blind SDK derives it, and no
 * {@code ignore = true} anywhere near it — because the collision reconciliation being right in isolation
 * ({@code SynthesisPlacementTest}) is not enough when it is the disagreement between two passes that
 * produced the bug.
 */
class RetrofitPatchEmitterAdoptedMemberTest {

  private static final String MODEL_PACKAGE = "m";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  @Test
  void annotatesTheDeclaredFieldOfAMemberWhoseIdItsCreatorParameterPins(@TempDir Path work)
      throws Exception {
    Path model = work.resolve("model/src");
    writeCreatorPinnedDetails(model, "HearingDuration");

    String diff = emit(model, definition(work));

    assertThat(diff).as("the definition's metadata must land on the existing declaration")
        .contains("+    @CCD(label = \"How long will the hearing last?\", searchable = false)");
    assertThat(diff).as("the id the SDK cannot derive from a creator parameter must be pinned")
        .contains("+    @JsonProperty(\"HearingDuration\")");
    assertThat(diff.lines().filter(l -> l.startsWith("+") && l.contains("ignore = true")))
        .as("the field the definition's row needs must not be ignored")
        .isEmpty();
  }

  /**
   * The refusal, end to end. With the creator parameter naming a different id there is no proof the field
   * is the definition's member, so the emitter must keep marking it ignored and report the definition
   * member as a gap — the pre-existing behaviour, which is the safe one when identity is unproven.
   */
  @Test
  void keepsIgnoringTheDeclaredFieldWhenNoPinProvesItIsTheDefinitionMember(@TempDir Path work)
      throws Exception {
    Path model = work.resolve("model/src");
    writeCreatorPinnedDetails(model, "SomethingElse");

    String diff = emit(model, definition(work));

    assertThat(diff).as("an unproven field keeps the unmatched-Java treatment")
        .contains("+    @CCD(ignore = true)");
    assertThat(diff).as("and must not receive the definition member's metadata")
        .doesNotContain("How long will the hearing last?");
  }

  /**
   * The model: Civil's {@code GAHearingDetails} shape reduced to one member — a camelCase field whose CCD
   * id is stated only by its {@code @JsonCreator} parameter, and a second field that really is unmatched
   * so the ignore path is exercised either way.
   */
  private void writeCreatorPinnedDetails(Path model, String pinnedId) throws Exception {
    write(model, "m", "GAHearingDetails", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonCreator;\n"
        + "import com.fasterxml.jackson.annotation.JsonProperty;\n"
        + "import lombok.Data;\n"
        + "@Data\n"
        + "public class GAHearingDetails {\n"
        + "    private String hearingDuration;\n"
        + "    @JsonCreator\n"
        + "    GAHearingDetails(@JsonProperty(\"" + pinnedId + "\") String hearingDuration) {\n"
        + "        this.hearingDuration = hearingDuration;\n"
        + "    }\n"
        + "}\n");
    write(model, "m", "CaseData", "package m;\nimport lombok.Data;\n@Data\n"
        + "public class CaseData {\n"
        + "    private String applicantName;\n"
        + "    private GAHearingDetails generalAppHearingDetails;\n"
        + "}\n");
  }

  /** Runs reader → linker → rebind → patch emitter, as {@link RetrofitConverter} does. */
  private String emit(Path modelRoot, Path definition) {
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
    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound = new RetrofitModelRebinder(matcher.index(), matcher.resolution(),
        matcher.root()).rebind(linked);
    return new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound, matcher.root(),
        CONFIG_PACKAGE, 0, "", RetrofitPinnedNames.empty(), Map.of()).emit().unifiedDiff();
  }

  /**
   * A definition whose {@code GAHearingDetails} complex type addresses its member by the PascalCase
   * {@code ListElementCode} the model pins on its creator parameter — the Civil divergence that makes the
   * member look definition-only to the resolver.
   */
  private Path definition(Path work) throws Exception {
    Path definition = work.resolve("definition");
    writeSheet(definition, "CaseType", "[{ \"ID\": \"EXAMPLE\", \"Name\": \"Example\","
        + " \"Description\": \"Adopted-member fixture\", \"JurisdictionID\": \"EX\" }]");
    writeSheet(definition, "CaseField",
        "[{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"applicantName\", \"FieldType\": \"Text\","
        + " \"Label\": \"Applicant name\" },"
        + "{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"generalAppHearingDetails\","
        + " \"FieldType\": \"GAHearingDetails\", \"Label\": \"Hearing details\" }]");
    writeSheet(definition, "ComplexTypes",
        "[{ \"ID\": \"GAHearingDetails\", \"ListElementCode\": \"HearingDuration\","
        + " \"FieldType\": \"Text\", \"ElementLabel\": \"How long will the hearing last?\","
        + " \"Searchable\": \"N\" }]");
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
