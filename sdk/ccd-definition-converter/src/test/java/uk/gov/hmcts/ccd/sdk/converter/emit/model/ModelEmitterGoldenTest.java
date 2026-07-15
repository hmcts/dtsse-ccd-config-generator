package uk.gov.hmcts.ccd.sdk.converter.emit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Golden-source tests for the model emitters.
 *
 * <p>Each emitter is exercised against a hand-built {@link CaseTypeModel} fixture and the
 * output is compared to expected {@code .java} files under
 * {@code src/test/resources/golden/model-emit/expected/}.
 */
class ModelEmitterGoldenTest {

  private static final String MODEL_PKG = "uk.gov.hmcts.test.model";
  private static final String CONFIG_PKG = "uk.gov.hmcts.test.config";

  // -----------------------------------------------------------------------
  // Test fixture
  // -----------------------------------------------------------------------

  /**
   * Builds the full test fixture model covering every @CCD member, @JsonProperty casing,
   * typeOverride cases, collection generics, complex type, fixed list with non-identifier code,
   * State with TitleDisplay, UserRole with case role, and one shared access class.
   *
   * @return the constructed model
   */
  private static CaseTypeModel buildFixture() {
    // --- States ---
    StateModel openState = StateModel.builder()
        .id("Open")
        .name("Case Open")
        .titleDisplay("# ${[CASE_REFERENCE]}")
        .build();
    StateModel closedState = StateModel.builder()
        .id("Closed")
        .name("Case Closed")
        .build();

    // --- Roles (includes a case role) ---
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

    // --- Access class ---
    AccessClassModel accessClass = AccessClassModel.builder()
        .className("CaseworkerAccess")
        .grants(Map.of("caseworker-test", "CRU"))
        .build();

    // --- Fixed list with non-identifier code ---
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

    // --- Fixed list with identifier-safe codes ---
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

    // --- Complex type member fields ---
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

    // --- CaseData fields ---
    // 1. Simple string field with label
    FieldModel nameField = FieldModel.builder()
        .id("claimantName")
        .javaName("claimantName")
        .javaType("String")
        .fieldType("Text")
        .label("Claimant Name")
        .overlayTags(Set.of())
        .build();

    // 2. Field with @JsonProperty (javaName differs from id)
    FieldModel jsonPropField = FieldModel.builder()
        .id("some_field_id")
        .javaName("someFieldId")
        .javaType("String")
        .fieldType("Text")
        .label("Some Field")
        .overlayTags(Set.of())
        .build();

    // 3. Collection generic field
    FieldModel collectionField = FieldModel.builder()
        .id("parties")
        .javaName("parties")
        .javaType("java.util.List<uk.gov.hmcts.ccd.sdk.type.ListValue<uk.gov.hmcts.test.model.TestAddress>>")
        .fieldType("Collection")
        .overlayTags(Set.of())
        .build();

    // 4. typeOverride field
    FieldModel overrideField = FieldModel.builder()
        .id("notes")
        .javaName("notes")
        .javaType("String")
        .fieldType("Text")
        .typeOverride("TextArea")
        .label("Notes")
        .overlayTags(Set.of())
        .build();

    // 5. typeParameterOverride field
    FieldModel tpOverrideField = FieldModel.builder()
        .id("claimType")
        .javaName("claimType")
        .javaType("String")
        .fieldType("FixedList")
        .typeParameterOverride("ClaimType")
        .label("Claim Type")
        .overlayTags(Set.of())
        .build();

    // 6. Field with ALL @CCD members: hint, showCondition, regex, categoryID,
    //    searchable=false, retainHiddenValue, min, max, access
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

    // 7. Label-type field: emitted as a real String member with @CCD(typeOverride = Label), as
    // TypeMapper maps a Label FieldType to String + a Label type override.
    FieldModel labelField = FieldModel.builder()
        .id("labelField")
        .javaName("labelField")
        .javaType("String")
        .fieldType("Label")
        .typeOverride("Label")
        .label("A label")
        .overlayTags(Set.of())
        .build();

    // 8. Overlay-tagged field (should be skipped)
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

  // -----------------------------------------------------------------------
  // CaseDataEmitter golden tests
  // -----------------------------------------------------------------------

  @Test
  void caseDataEmitterMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new CaseDataEmitter().emit(model, ctx);

    assertThat(files).hasSize(1);
    String actual = normalise(files.get(0).toString());
    String expected = loadGolden("CaseData.java");
    assertThat(actual).isEqualTo(expected);
  }

  // -----------------------------------------------------------------------
  // ComplexTypeEmitter golden tests
  // -----------------------------------------------------------------------

  @Test
  void complexTypeEmitterMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new ComplexTypeEmitter().emit(model, ctx);

    assertThat(files).hasSize(1);
    String actual = normalise(files.get(0).toString());
    String expected = loadGolden("TestAddress.java");
    assertThat(actual).isEqualTo(expected);
  }

  // -----------------------------------------------------------------------
  // EnumEmitter golden tests
  // -----------------------------------------------------------------------

  @Test
  void enumEmitterFixedListNonIdentifierMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new EnumEmitter().emit(model, ctx);

    JavaFile claimType = files.stream()
        .filter(f -> f.typeSpec().name().equals("ClaimType"))
        .findFirst()
        .orElseThrow();
    String actual = normalise(claimType.toString());
    String expected = loadGolden("ClaimType.java");
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void enumEmitterFixedListSimpleMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new EnumEmitter().emit(model, ctx);

    JavaFile yesOrNo = files.stream()
        .filter(f -> f.typeSpec().name().equals("YesOrNoChoice"))
        .findFirst()
        .orElseThrow();
    String actual = normalise(yesOrNo.toString());
    String expected = loadGolden("YesOrNoChoice.java");
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void enumEmitterStateMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new EnumEmitter().emit(model, ctx);

    JavaFile state = files.stream()
        .filter(f -> f.typeSpec().name().equals("State"))
        .findFirst()
        .orElseThrow();
    String actual = normalise(state.toString());
    String expected = loadGolden("State.java");
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void enumEmitterUserRoleMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new EnumEmitter().emit(model, ctx);

    JavaFile userRole = files.stream()
        .filter(f -> f.typeSpec().name().equals("UserRole"))
        .findFirst()
        .orElseThrow();
    String actual = normalise(userRole.toString());
    String expected = loadGolden("UserRole.java");
    assertThat(actual).isEqualTo(expected);
  }

  // -----------------------------------------------------------------------
  // AccessClassEmitter golden tests
  // -----------------------------------------------------------------------

  @Test
  void accessClassEmitterMatchesGolden() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();
    List<JavaFile> files = new AccessClassEmitter().emit(model, ctx);

    assertThat(files).hasSize(1);
    String actual = normalise(files.get(0).toString());
    String expected = loadGolden("CaseworkerAccess.java");
    assertThat(actual).isEqualTo(expected);
  }

  // -----------------------------------------------------------------------
  // Compile smoke test
  // -----------------------------------------------------------------------

  @Test
  void emittedSourcesCompileClean() throws IOException {
    CaseTypeModel model = buildFixture();
    EmitContext ctx = buildContext();

    List<JavaFile> allFiles = new java.util.ArrayList<>();
    allFiles.addAll(new CaseDataEmitter().emit(model, ctx));
    allFiles.addAll(new ComplexTypeEmitter().emit(model, ctx));
    allFiles.addAll(new EnumEmitter().emit(model, ctx));
    allFiles.addAll(new AccessClassEmitter().emit(model, ctx));

    CompileSmokeTest.assertCompilesClean(allFiles);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private static String normalise(String src) {
    // Trim trailing whitespace on each line
    String[] lines = src.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line.stripTrailing()).append("\n");
    }
    // Remove trailing newline(s) so comparison is stable
    return sb.toString().stripTrailing();
  }

  private static String loadGolden(String filename) throws IOException {
    String path = "/golden/model-emit/expected/" + filename;
    try (InputStream is = ModelEmitterGoldenTest.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("Golden file not found on classpath: " + path);
      }
      String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      return normalise(raw);
    }
  }
}
