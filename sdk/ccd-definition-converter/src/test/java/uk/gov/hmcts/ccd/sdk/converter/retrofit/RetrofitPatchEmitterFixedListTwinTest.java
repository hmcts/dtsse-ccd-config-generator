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
 * Pins the {@code @CCD(label)} pin onto EVERY model enum sharing a {@code FixedLists} ID's simple
 * name, not the one an ambiguous-name lookup happens to return.
 *
 * <p>{@code FixedListGenerator} derives a list's ID from the enum it reflects — its
 * {@code @ComplexType(name)} when pinned, else the simple class NAME — so a model declaring the same
 * enum name twice has two types that would emit rows into one ID, and which of them the SDK reaches is
 * decided by the case-data reachability graph rather than by anything the converter's index can see.
 * The pin used to go to {@code ModelSourceIndex#bySimpleName}, which answers such a name with ONE
 * candidate and for these cannot answer correctly: its package hint is a prefix test against the model
 * package and separates neither twin, so the tie fell to source-scan order.
 *
 * <p>Civil is the case. It declares {@code AssistedCostTypesList} in both {@code enums.finalorders}
 * (7 constants, reached from {@code CaseDataCaseProgression.assistedOrderCostList} and so the twin the
 * SDK really reflects for {@code CIVIL}) and {@code ga.enums.dq} (6 constants, reached only from
 * {@code GeneralApplicationCaseData}, a different case type's root). Scan order pinned the
 * {@code ga.enums.dq} twin: all five labels were written, none reached the definition, and the emitted
 * list carried {@code ListElement == ListElementCode} for every row. {@code HearingLengthFinalOrderList},
 * {@code OrderMadeOnTypes}, {@code DisposalHearingBundleType} and
 * {@code DisposalHearingFinalDisposalHearingTimeEstimate} are the same shape.
 *
 * <p>The fixture reproduces the decisive property rather than the whole model: the twin the definition's
 * own field declares is the one in the DEEPER package, so a lookup preferring the first candidate under
 * the model package picks the other one. Both twins are asserted to be annotated, because pinning a twin
 * the SDK never reflects is inert (it emits no rows) while missing the reflected one costs every label of
 * the list — and because two twins can BOTH be reachable, which no single choice can cover.
 */
class RetrofitPatchEmitterFixedListTwinTest {

  private static final String MODEL_PACKAGE = "m";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  @Test
  void pinsTheDefinitionLabelOntoEveryEnumSharingTheListsName(@TempDir Path work)
      throws Exception {
    String diff = emitPatch(twinEnumModel(work), definitionWithLabelledList(work));

    assertThat(hunkFor(diff, "m/reached/CostType.java"))
        .as("the twin the definition's own field declares — the one the SDK reflects — must carry"
            + " the labels; this is the twin scan order used to skip")
        .contains("@CCD(label = \"Costs in the case\")")
        .contains("@CCD(label = \"No order as to costs\")");

    assertThat(hunkFor(diff, "m/other/CostType.java"))
        .as("the unreached twin is annotated too: which twin the SDK reflects is not knowable here,"
            + " and a pin on a type that emits no rows is inert")
        .contains("@CCD(label = \"Costs in the case\")")
        .contains("@CCD(label = \"No order as to costs\")");
  }

  /** Runs matcher → linker → rebind → patch emit and returns the unified diff. */
  private String emitPatch(Path modelRoot, Path definition) {
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
        new RetrofitMatcher(ir, "EXAMPLE", modelRoot, MODEL_PACKAGE, "CaseData");
    matcher.match();
    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound = new RetrofitModelRebinder(matcher.index(), matcher.resolution(),
        matcher.root()).rebind(linked);

    RetrofitPatchEmitter emitter = new RetrofitPatchEmitter(matcher.index(), matcher.resolution(),
        rebound, matcher.root(), CONFIG_PACKAGE);
    // The pre-drop lists are what carry the labels: the rebinder removes exactly the lists a model
    // enum already serves, which is the set whose constants need the pin.
    emitter.bindDefinitionFixedLists(linked.getFixedLists());
    return emitter.emit().unifiedDiff();
  }

  /** The hunk of a unified diff for one file, so a per-twin assertion cannot be satisfied by the other. */
  private static String hunkFor(String diff, String path) {
    int start = diff.indexOf("+++ b/" + path);
    assertThat(start).as("patch must touch " + path + ":\n" + diff).isNotNegative();
    int next = diff.indexOf("--- a/", start);
    return next < 0 ? diff.substring(start) : diff.substring(start, next);
  }

  /**
   * A model declaring {@code CostType} twice, whose root field declares the twin in the deeper package
   * — so a first-under-the-model-package lookup returns the OTHER one.
   */
  private Path twinEnumModel(Path work) throws Exception {
    Path src = work.resolve("model/src");
    write(src, "m", "CaseData", "package m;\n\n"
        + "import m.reached.CostType;\n\n"
        + "public class CaseData {\n"
        + "  private CostType costList;\n"
        + "  public CostType getCostList() { return costList; }\n}\n");
    // Both twins declare the definition's codes, so the only question the emitter faces is which
    // declaration to annotate.
    write(src, "m/other", "CostType", "package m.other;\n\n"
        + "public enum CostType {\n  COSTS_IN_THE_CASE,\n  NO_ORDER_TO_COST\n}\n");
    write(src, "m/reached", "CostType", "package m.reached;\n\n"
        + "public enum CostType {\n  COSTS_IN_THE_CASE,\n  NO_ORDER_TO_COST\n}\n");
    return src.toAbsolutePath();
  }

  /** A definition whose {@code CostType} list carries labels the constant names do not reproduce. */
  private Path definitionWithLabelledList(Path work) throws Exception {
    Path definition = work.resolve("definition");
    writeSheet(definition, "CaseType", "[{ \"ID\": \"EXAMPLE\", \"Name\": \"Example\","
        + " \"Description\": \"Twin fixed-list fixture\", \"JurisdictionID\": \"EX\" }]");
    writeSheet(definition, "CaseField",
        "[{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"costList\","
        + " \"FieldType\": \"FixedRadioList\", \"FieldTypeParameter\": \"CostType\","
        + " \"Label\": \"Cost type\" }]");
    writeSheet(definition, "FixedLists",
        "[{ \"ID\": \"CostType\", \"ListElementCode\": \"COSTS_IN_THE_CASE\","
        + " \"ListElement\": \"Costs in the case\", \"DisplayOrder\": 1 },\n"
        + " { \"ID\": \"CostType\", \"ListElementCode\": \"NO_ORDER_TO_COST\","
        + " \"ListElement\": \"No order as to costs\", \"DisplayOrder\": 2 }]");
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
