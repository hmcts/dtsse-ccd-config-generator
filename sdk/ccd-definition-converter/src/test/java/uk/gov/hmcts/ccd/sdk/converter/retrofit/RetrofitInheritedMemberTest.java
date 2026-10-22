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
 * Pins {@link RetrofitInheritedMembers}: a field declared once on a shared superclass is several CCD
 * members, and the patch must be able to configure them independently.
 *
 * <p>Before this, every claim about a field was keyed by {@code (file, member)}, so the complex types
 * reaching one superclass declaration shared ONE annotation slot and the last one planned silently
 * overwrote the rest — whichever show condition it happened to carry became every subclass's. sscs's
 * abstract {@code Entity} declares {@code name}/{@code address}/{@code contact} for five parties and
 * the definition puts {@code FieldShowCondition} on {@code representative}'s rows alone, so 14
 * {@code ComplexTypes} lines were wrong whichever way the overwrite fell.
 *
 * <p>What is pinned here is the whole decision, in the shape the emitter has to get right:
 * <ul>
 *   <li>the agreeing majority stays on the field, so a definition that says one thing everywhere
 *       emits exactly the single annotation it always did;</li>
 *   <li>the diverging subclass carries a class-level {@code @CCD(member = …)} instead;</li>
 *   <li>a member the definition has no row for under one subclass is scoped the same way, via
 *       {@code ignore = true};</li>
 *   <li>re-running the patch against its own output adds nothing.</li>
 * </ul>
 */
class RetrofitInheritedMemberTest {

  private static final String MODEL_PACKAGE = "m";
  private static final String CONFIG_PACKAGE = "uk.gov.hmcts.example.config";

  @Test
  void scopesTheDivergingSubclassAndLeavesTheAgreedConfigurationOnTheField(@TempDir Path work)
      throws Exception {
    String diff = emit(hierarchyModel(work), divergingDefinition(work));

    assertThat(diff)
        .as("two of the three parties label the inherited member the same way, so that claim stays "
            + "on the declaration Entity owns")
        .contains("+  @CCD(label = \"Name\")");
    assertThat(diff)
        .as("the third labels it differently, and only a class-level override can say so")
        .contains("+@CCD(member = \"name\", label = \"Representative name\")");
    assertThat(diff.lines().filter(l -> l.startsWith("+") && l.contains("@CCD(member = \"name\"")))
        .as("exactly one class needs scoping — the agreeing ones must not be given overrides too")
        .hasSize(1);
  }

  @Test
  void scopesAMemberOneSubclassHasNoRowForAsAnIgnoreOverride(@TempDir Path work) throws Exception {
    String diff = emit(hierarchyModel(work), definitionMissingOneSubclassMember(work));

    assertThat(diff)
        .as("the parties that DO have a row keep the annotation on Entity's declaration")
        .contains("+  @CCD(label = \"Name\")");
    assertThat(diff)
        .as("the one the definition has no name row for drops it for its own rows only")
        .contains("+@CCD(member = \"name\", ignore = true)");
  }

  @Test
  void addsNothingWhenTheOverridesAreAlreadyPresent(@TempDir Path work) throws Exception {
    Path model = hierarchyModel(work);
    Path definition = divergingDefinition(work);

    // Apply the patch by hand — the same annotations, written as a team would leave them — and
    // re-emit. An idempotent patch is what makes the retrofit re-runnable against a repo that has
    // already taken one.
    Path applied = work.resolve("applied/src");
    write(applied, "m", "Entity", "package m;\n\nimport uk.gov.hmcts.ccd.sdk.api.CCD;\n\n"
        + "public abstract class Entity {\n"
        + "  @CCD(label = \"Name\")\n"
        + "  private String name;\n"
        + "  public String getName() { return name; }\n}\n");
    write(applied, "m", "Appellant", appellant());
    write(applied, "m", "Appointee", appointee());
    write(applied, "m", "Representative", "package m;\n\nimport uk.gov.hmcts.ccd.sdk.api.CCD;\n\n"
        + "@CCD(member = \"name\", label = \"Representative name\")\n"
        + "public class Representative extends Entity {\n}\n");
    write(applied, "m", "CaseData", caseData());

    assertThat(emit(applied.toAbsolutePath(), definition))
        .as("re-emitting against the patch's own output must add no second override")
        .doesNotContain("+@CCD(member = \"name\"");
  }

  /**
   * sscs's hierarchy reduced to what the decision needs: one abstract superclass declaring the shared
   * member, three subclasses the definition reaches as separate complex types, and a root holding one
   * field of each.
   */
  private Path hierarchyModel(Path work) throws Exception {
    Path src = work.resolve("model/src");
    write(src, "m", "Entity", "package m;\n\npublic abstract class Entity {\n"
        + "  private String name;\n"
        + "  public String getName() { return name; }\n}\n");
    write(src, "m", "Appellant", appellant());
    write(src, "m", "Appointee", appointee());
    write(src, "m", "Representative",
        "package m;\n\npublic class Representative extends Entity {\n}\n");
    write(src, "m", "CaseData", caseData());
    return src.toAbsolutePath();
  }

  private static String appellant() {
    return "package m;\n\npublic class Appellant extends Entity {\n}\n";
  }

  private static String appointee() {
    return "package m;\n\npublic class Appointee extends Entity {\n}\n";
  }

  private static String caseData() {
    return "package m;\n\npublic class CaseData {\n"
        + "  private Appellant appellant;\n"
        + "  private Appointee appointee;\n"
        + "  private Representative representative;\n"
        + "  public Appellant getAppellant() { return appellant; }\n"
        + "  public Appointee getAppointee() { return appointee; }\n"
        + "  public Representative getRepresentative() { return representative; }\n}\n";
  }

  /** Three complex types over one inherited member, one of them labelling it differently. */
  private Path divergingDefinition(Path work) throws Exception {
    return definition(work, "diverging",
        "[{ \"ID\": \"Appellant\", \"ListElementCode\": \"name\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Name\" },\n"
        + " { \"ID\": \"Appointee\", \"ListElementCode\": \"name\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Name\" },\n"
        + " { \"ID\": \"Representative\", \"ListElementCode\": \"name\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Representative name\" }]");
  }

  /** The same three types, with no {@code name} row at all under {@code Representative}. */
  private Path definitionMissingOneSubclassMember(Path work) throws Exception {
    return definition(work, "missing",
        "[{ \"ID\": \"Appellant\", \"ListElementCode\": \"name\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Name\" },\n"
        + " { \"ID\": \"Appointee\", \"ListElementCode\": \"name\", \"FieldType\": \"Text\","
        + " \"ElementLabel\": \"Name\" },\n"
        + " { \"ID\": \"Representative\", \"ListElementCode\": \"placeholder\","
        + " \"FieldType\": \"Text\", \"ElementLabel\": \"Placeholder\" }]");
  }

  private Path definition(Path work, String name, String complexTypes) throws Exception {
    Path definition = work.resolve("definition-" + name);
    writeSheet(definition, "CaseType", "[{ \"ID\": \"EXAMPLE\", \"Name\": \"Example\","
        + " \"Description\": \"Inherited-member fixture\", \"JurisdictionID\": \"EX\" }]");
    writeSheet(definition, "CaseField",
        "[{ \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"appellant\", \"FieldType\": \"Appellant\","
        + " \"Label\": \"Appellant\" },\n"
        + " { \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"appointee\", \"FieldType\": \"Appointee\","
        + " \"Label\": \"Appointee\" },\n"
        + " { \"CaseTypeID\": \"EXAMPLE\", \"ID\": \"representative\","
        + " \"FieldType\": \"Representative\", \"Label\": \"Representative\" }]");
    writeSheet(definition, "ComplexTypes", complexTypes);
    return definition.toAbsolutePath();
  }

  /**
   * Runs matcher → linker → rebind → patch emit, as {@link RetrofitConverter} does, and returns the
   * unified diff.
   */
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
        new RetrofitMatcher(ir, "EXAMPLE", modelRoot, MODEL_PACKAGE, "CaseData");
    matcher.match();

    CaseTypeModel linked = new DefaultDefinitionLinker().link(ir, options, new GapCollector());
    CaseTypeModel rebound = new RetrofitModelRebinder(matcher.index(), matcher.resolution(),
        matcher.root()).rebind(linked);
    return new RetrofitPatchEmitter(matcher.index(), matcher.resolution(), rebound, matcher.root(),
        CONFIG_PACKAGE, 0, "", RetrofitPinnedNames.empty(), Map.of()).emit().unifiedDiff();
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
