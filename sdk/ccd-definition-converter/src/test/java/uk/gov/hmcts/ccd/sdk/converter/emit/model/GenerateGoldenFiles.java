package uk.gov.hmcts.ccd.sdk.converter.emit.model;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.StateModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * One-shot utility to write golden files from the current emitter output.
 *
 * <p>Disabled by default so a normal test run never overwrites the checked-in golden files
 * (which would mask emitter regressions in {@link ModelEmitterGoldenTest}). Remove the
 * {@code @Disabled} temporarily and run this one test to regenerate the expected files after an
 * intentional emitter change, then re-add it and review the diff.
 */
class GenerateGoldenFiles {

  private static final String MODEL_PKG = "uk.gov.hmcts.test.model";
  private static final String CONFIG_PKG = "uk.gov.hmcts.test.config";

  @Test
  @Disabled("Regeneration tool: enable manually to rewrite golden files after emitter changes")
  void writeGoldenFiles() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();

    Path goldenDir = Path.of(
        "src/test/resources/golden/model-emit/expected");
    Files.createDirectories(goldenDir);

    writeFiles(goldenDir, new CaseDataEmitter().emit(model, ctx));
    writeFiles(goldenDir, new ComplexTypeEmitter().emit(model, ctx));
    writeFiles(goldenDir, new EnumEmitter().emit(model, ctx));
    writeFiles(goldenDir, new AccessClassEmitter().emit(model, ctx));

    System.out.println("Golden files written to: " + goldenDir.toAbsolutePath());
  }

  private static void writeFiles(Path dir, List<JavaFile> files) throws IOException {
    for (JavaFile jf : files) {
      String content = normalise(jf.toString());
      Path out = dir.resolve(jf.typeSpec().name() + ".java");
      Files.writeString(out, content, StandardCharsets.UTF_8);
      System.out.println("  Written: " + out.getFileName());
    }
  }

  private static String normalise(String src) {
    String[] lines = src.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line.stripTrailing()).append("\n");
    }
    return sb.toString().stripTrailing();
  }

  private static EmitContext buildContext() {
    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage(MODEL_PKG)
        .configPackage(CONFIG_PKG)
        .build();
    return EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();
  }

  private static CaseTypeModel buildFixture() {
    StateModel openState = StateModel.builder()
        .id("Open")
        .name("Case Open")
        .titleDisplay("# ${[CASE_REFERENCE]}")
        .build();
    StateModel closedState = StateModel.builder()
        .id("Closed")
        .name("Case Closed")
        .build();

    RoleModel caseworker = RoleModel.builder()
        .id("caseworker-test")
        .javaConstant("CASEWORKER_TEST")
        .caseTypePermissions("CRU")
        .caseRole(false)
        .build();
    RoleModel creator = RoleModel.builder()
        .id("[CREATOR]")
        .javaConstant("CREATOR")
        .caseTypePermissions("")
        .caseRole(true)
        .build();

    AccessClassModel accessClass = AccessClassModel.builder()
        .className("CaseworkerAccess")
        .grants(Map.of("caseworker-test", "CRU"))
        .build();

    FixedListModel.Item itemA = FixedListModel.Item.builder()
        .code("A-CODE")
        .label("Label for A")
        .javaConstant("A_CODE")
        .displayOrder(1)
        .build();
    FixedListModel.Item itemB = FixedListModel.Item.builder()
        .code("B-CODE")
        .label("Label for B")
        .javaConstant("B_CODE")
        .displayOrder(2)
        .build();
    FixedListModel nonIdentifierList = FixedListModel.builder()
        .id("ClaimType")
        .items(Arrays.asList(itemA, itemB))
        .build();

    FixedListModel.Item yes = FixedListModel.Item.builder()
        .code("YES")
        .label("Yes")
        .javaConstant("YES")
        .displayOrder(1)
        .build();
    FixedListModel.Item no = FixedListModel.Item.builder()
        .code("NO")
        .label("No")
        .javaConstant("NO")
        .displayOrder(2)
        .build();
    FixedListModel simpleList = FixedListModel.builder()
        .id("YesOrNoChoice")
        .items(Arrays.asList(yes, no))
        .build();

    FieldModel addressLine = FieldModel.builder()
        .id("addressLine1")
        .javaName("addressLine1")
        .javaType("String")
        .fieldType("Text")
        .label("Address Line 1")
        .overlayTags(Set.of())
        .build();
    FieldModel city = FieldModel.builder()
        .id("city")
        .javaName("city")
        .javaType("String")
        .fieldType("Text")
        .label("City")
        .overlayTags(Set.of())
        .build();
    ComplexTypeModel addressType = ComplexTypeModel.builder()
        .id("TestAddress")
        .members(Arrays.asList(addressLine, city))
        .depth(0)
        .build();

    FieldModel nameField = FieldModel.builder()
        .id("claimantName")
        .javaName("claimantName")
        .javaType("String")
        .fieldType("Text")
        .label("Claimant Name")
        .overlayTags(Set.of())
        .build();
    FieldModel jsonPropField = FieldModel.builder()
        .id("some_field_id")
        .javaName("someFieldId")
        .javaType("String")
        .fieldType("Text")
        .label("Some Field")
        .overlayTags(Set.of())
        .build();
    FieldModel collectionField = FieldModel.builder()
        .id("parties")
        .javaName("parties")
        .javaType(
            "java.util.List<uk.gov.hmcts.ccd.sdk.type.ListValue<uk.gov.hmcts.test.model.TestAddress>>")
        .fieldType("Collection")
        .overlayTags(Set.of())
        .build();
    FieldModel overrideField = FieldModel.builder()
        .id("notes")
        .javaName("notes")
        .javaType("String")
        .fieldType("Text")
        .typeOverride("TextArea")
        .label("Notes")
        .overlayTags(Set.of())
        .build();
    FieldModel tpOverrideField = FieldModel.builder()
        .id("claimType")
        .javaName("claimType")
        .javaType("String")
        .fieldType("FixedList")
        .typeParameterOverride("ClaimType")
        .label("Claim Type")
        .overlayTags(Set.of())
        .build();
    FieldModel richField = FieldModel.builder()
        .id("richField")
        .javaName("richField")
        .javaType("String")
        .fieldType("Text")
        .label("Rich Field")
        .hint("Enter a value")
        .showCondition("claimantName!=\"\"")
        .regex("[A-Z]+")
        .categoryId("evidence")
        .searchable(false)
        .retainHiddenValue(true)
        .min(1)
        .max(100)
        .accessClassNames(List.of("CaseworkerAccess"))
        .overlayTags(Set.of())
        .build();
    FieldModel labelField = FieldModel.builder()
        .id("labelField")
        .javaName("labelField")
        .javaType("String")
        .fieldType("Label")
        .overlayTags(Set.of())
        .build();
    FieldModel overlayField = FieldModel.builder()
        .id("overlayField")
        .javaName("overlayField")
        .javaType("String")
        .fieldType("Text")
        .overlayTags(Set.of("prod"))
        .build();

    return CaseTypeModel.builder()
        .caseTypeId("TestCase")
        .caseTypeName("Test Case")
        .states(Arrays.asList(openState, closedState))
        .roles(Arrays.asList(caseworker, creator))
        .accessClasses(List.of(accessClass))
        .caseFields(Arrays.asList(
            nameField, jsonPropField, collectionField, overrideField,
            tpOverrideField, richField, labelField, overlayField))
        .complexTypes(List.of(addressType))
        .fixedLists(Arrays.asList(nonIdentifierList, simpleList))
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();
  }
}
