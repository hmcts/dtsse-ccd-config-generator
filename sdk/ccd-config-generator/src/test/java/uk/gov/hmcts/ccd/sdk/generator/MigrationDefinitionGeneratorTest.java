package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CaseType;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasCode;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;
import uk.gov.hmcts.ccd.sdk.api.Jurisdiction;
import uk.gov.hmcts.ccd.sdk.api.Webhook;

public class MigrationDefinitionGeneratorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void generatesDefinitionOnlyMigrationMetadata() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    builder.caseType(CaseType.builder()
        .id("MigrationTest")
        .name("Migration test")
        .description("Migration test definition")
        .liveFrom(LocalDate.of(2023, 9, 28))
        .enableForDeletion(true)
        .retriesTimeoutUrlPrintEvent(20)
        .build());
    builder.jurisdiction(Jurisdiction.builder()
        .id("TEST")
        .name("Test")
        .description("Test jurisdiction")
        .shuttered(true)
        .build());
    builder.omitDefaultLiveFrom();
    builder.omitCaseHistory();
    builder.event("create")
        .initialState(TestState.Open)
        .name("Create")
        .omitShowSummary()
        .omitPublish()
        .externalCallbackUrl(Webhook.AboutToSubmit, "${TEST_URL}/create")
        .fields()
        .omitPageColumnNumber()
        .optionalNoSummary(TestData::getName)
        .done();
    builder.tab("Details", "Details")
        .withoutChannel()
        .field(TestData::getName);

    File output = new File(tmp.getRoot(), "definition");
    JSONConfigGenerator<TestData, TestState, TestRole> generator = new JSONConfigGenerator<>(List.of(
        new CaseFieldGenerator<>(),
        new CaseEventGenerator<>(),
        new CaseEventToFieldsGenerator<>(),
        new CaseTypeTabGenerator<>()
    ));
    generator.writeConfig(output, builder.build());

    assertThat(rows(output, "CaseType.json")).containsExactly(Map.of(
        "ID", "MigrationTest",
        "Name", "Migration test",
        "Description", "Migration test definition",
        "LiveFrom", "28/09/2023",
        "JurisdictionID", "TEST",
        "SecurityClassification", "Public",
        "EnableForDeletion", "Yes",
        "RetriesTimeoutURLPrintEvent", 20
    ));
    assertThat(rows(output, "Jurisdiction.json")).containsExactly(Map.of(
        "ID", "TEST",
        "Name", "Test",
        "Description", "Test jurisdiction",
        "LiveFrom", "01/01/2017",
        "Shuttered", "Yes"
    ));

    Map<String, Object> event = onlyRow(output, "CaseEvent/create.json");
    assertThat(event)
        .containsEntry("CallBackURLAboutToSubmitEvent", "${TEST_URL}/create")
        .doesNotContainKeys("LiveFrom", "ShowSummary", "Publish");

    Map<String, Object> eventField = onlyRow(output, "CaseEventToFields/create.json");
    assertThat(eventField)
        .doesNotContainKeys("LiveFrom", "PageColumnNumber", "ShowSummaryChangeOption");

    Map<String, Object> caseField = rows(output, "CaseField.json").stream()
        .filter(row -> row.get("ID").equals("name"))
        .findFirst()
        .orElseThrow();
    assertThat(caseField)
        .containsEntry("ID", "name")
        .doesNotContainEntry("ID", "caseHistory")
        .doesNotContainKey("LiveFrom");

    Map<String, Object> tab = onlyRow(output, "CaseTypeTab/1_Details.json");
    assertThat(tab)
        .containsEntry("CaseFieldID", "name")
        .doesNotContainKeys("LiveFrom", "Channel");
  }

  @Test
  public void rejectsCallbackHandlersForDefinitionOnlyUrls() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    var event = builder.event("create").initialState(TestState.Open);

    event.externalCallbackUrl(Webhook.AboutToSubmit, "${TEST_URL}/create");

    assertThatThrownBy(() -> event.aboutToSubmitCallback((details, before) -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("external URL");
  }

  @Test
  public void retainsAccessProfileLiveFromPerRow() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    builder.omitDefaultLiveFrom();
    builder.caseRoleToAccessProfile(TestRole.TEST)
        .legacyIdamRole()
        .retainLiveFrom();
    builder.caseRoleToAccessProfile(TestRole.TEST_WITHOUT_LIVE_FROM)
        .legacyIdamRole();

    File output = new File(tmp.getRoot(), "access-profile-live-from");
    JSONConfigGenerator<TestData, TestState, TestRole> generator =
        new JSONConfigGenerator<>(List.of(new RoleToAccessProfilesGenerator<>()));
    generator.writeConfig(output, builder.build());

    List<Map<String, Object>> profiles = rows(output, "RoleToAccessProfiles.json");
    assertThat(profiles)
        .filteredOn(row -> row.get("RoleName").equals("idam:caseworker-test"))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("LiveFrom", "01/01/2017"));
    assertThat(profiles)
        .filteredOn(row -> row.get("RoleName").equals("idam:caseworker-test-without-live-from"))
        .singleElement()
        .satisfies(row -> assertThat(row).doesNotContainKey("LiveFrom"));
  }

  @Test
  public void generatesExactOptionalMigrationColumnsAndRegisteredDefinitions() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    builder.caseHistoryLabel("History");
    builder.registerFixedList("external_list", TestFixedList.SECOND, TestFixedList.FIRST);
    builder.registerComplexTypes(UnreferencedComplex.class);
    builder.retainCaseRoleLiveFrom();
    var event = builder.event("import")
        .forAllStates()
        .postStateExpression("$PREVIOUS")
        .name("Import")
        .omitEndButtonLabel()
        .omitShowEventNotes()
        .omitDisplayOrder()
        .omitStateAuthorisationInference()
        .grant(CRU, TestRole.TEST);
    event.fields()
        .omitPageDisplayOrder()
        .externalMidEventCallbackUrl("${TEST_URL}/mid-event")
        .field(TestData::getName)
        .optional()
        .showSummaryChangeOption(false)
        .doNotPublish()
        .pageShowCondition("name=\"visible\"")
        .done()
        .complex(TestData::getImportFile, false)
        .eventToComplexTypeId("file")
        .mandatoryWithLabel(TestComplex::getFile, "File")
        .done()
        .done();
    builder.tab("ExactMetadata", "Default label")
        .displayOrder(9)
        .field("name", null, 1, null, "Row label", 9)
        .field("legacyType", null, 2, null, null, null);

    File output = new File(tmp.getRoot(), "exact-definition");
    JSONConfigGenerator<TestData, TestState, TestRole> generator = new JSONConfigGenerator<>(List.of(
        new CaseFieldGenerator<>(),
        new CaseEventGenerator<>(),
        new CaseEventToFieldsGenerator<>(),
        new CaseEventToComplexTypesGenerator<>(),
        new CaseTypeTabGenerator<>(),
        new ComplexTypeGenerator<>(),
        new FixedListGenerator<>(),
        new CaseRoleGenerator<>(),
        new AuthorisationCaseStateGenerator<>()
    ));
    generator.writeConfig(output, builder.build());

    assertThat(rows(output, "CaseField.json"))
        .anySatisfy(row -> assertThat(row)
            .containsEntry("ID", "caseHistory")
            .containsEntry("Label", "History"));
    assertThat(onlyRow(output, "CaseEvent/import.json"))
        .containsEntry("PostConditionState", "$PREVIOUS")
        .doesNotContainKeys("DisplayOrder", "EndButtonLabel", "ShowEventNotes");
    List<Map<String, Object>> eventFields = rows(output, "CaseEventToFields/import.json");
    assertThat(eventFields)
        .allSatisfy(row -> assertThat(row).doesNotContainKey("PageDisplayOrder"));
    assertThat(eventFields)
        .filteredOn(row -> row.get("CaseFieldID").equals("name"))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("Publish", "N")
            .containsEntry("ShowSummaryChangeOption", "N")
            .containsEntry("PageShowCondition", "name=\"visible\""));
    assertThat(eventFields)
        .filteredOn(row -> row.containsKey("CallBackURLMidEvent"))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("CallBackURLMidEvent", "${TEST_URL}/mid-event"));
    assertThat(onlyRow(output, "CaseEventToComplexTypes/import/importFile.json"))
        .containsEntry("ID", "file")
        .containsEntry("EventElementLabel", "File")
        .containsEntry("FieldDisplayOrder", 1);
    assertThat(rows(output, "FixedLists/external_list.json"))
        .anySatisfy(row -> assertThat(row)
            .containsEntry("ListElementCode", "second-code")
            .containsEntry("DisplayOrder", 1))
        .anySatisfy(row -> assertThat(row)
            .containsEntry("ListElementCode", "first-code")
            .containsEntry("DisplayOrder", 7));
    assertThat(onlyRow(output, "ComplexTypes/Unreferenced.json"))
        .containsEntry("ID", "Unreferenced")
        .containsEntry("ListElementCode", "value");
    assertThat(rows(output, "CaseRoles.json"))
        .filteredOn(row -> row.get("ID").equals("[TEST_ROLE]"))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("LiveFrom", "15/08/2024"));
    assertThat(rows(output, "CaseField.json"))
        .filteredOn(row -> row.get("ID").equals("legacyType"))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("FieldType", "LegacyComplex")
            .containsEntry("Searchable", "Y")
            .containsEntry("RetainHiddenValue", "Yes"));
    assertThat(onlyRow(output, "CaseTypeTab/9_ExactMetadata.json"))
        .containsEntry("TabLabel", "Row label")
        .containsEntry("TabDisplayOrder", 9);
    assertThat(onlyRow(output, "CaseTypeTab/null_ExactMetadata.json"))
        .containsEntry("CaseFieldID", "legacyType")
        .doesNotContainKeys("TabLabel", "TabDisplayOrder");
    assertThat(rows(output, "AuthorisationCaseState.json")).isEmpty();
  }

  @Test
  public void rejectsMixedExternalAndHandledMidEventCallbacks() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    var fields = builder.event("import")
        .initialState(TestState.Open)
        .fields()
        .page("details", (details, before) -> null);

    assertThatThrownBy(() -> fields.externalMidEventCallbackUrl("${TEST_URL}/mid-event"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("external URL");
  }

  @Test
  public void rejectsInvalidOrConflictingFixedListRegistrations() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();

    assertThatThrownBy(() -> builder.registerFixedList("", TestFixedList.FIRST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
    assertThatThrownBy(() -> builder.registerFixedList(
        "external_list", TestFixedList.FIRST, TestFixedList.FIRST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");

    builder.registerFixedList("external_list", TestFixedList.FIRST);
    assertThatThrownBy(() -> builder.registerFixedList("external_list", TestFixedList.SECOND))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Conflicting");
  }

  private ConfigBuilderImpl<TestData, TestState, TestRole> newBuilder() {
    ResolvedCCDConfig<TestData, TestState, TestRole> config = new ResolvedCCDConfig<>(
        TestData.class,
        TestState.class,
        TestRole.class,
        Map.of(),
        ImmutableSet.copyOf(TestState.values())
    );
    return new ConfigBuilderImpl<>(config);
  }

  private Map<String, Object> onlyRow(File root, String relativePath) {
    List<Map<String, Object>> rows = rows(root, relativePath);
    assertThat(rows).hasSize(1);
    return rows.getFirst();
  }

  @SneakyThrows
  private List<Map<String, Object>> rows(File root, String relativePath) {
    return MAPPER.readValue(new File(root, relativePath), new TypeReference<>() {});
  }

  private static class TestData {

    @CCD(label = "Name")
    private String name;

    private TestComplex importFile;

    @CCD(
        label = "Legacy type",
        typeNameOverride = "LegacyComplex",
        includeSearchable = true,
        retainHiddenValueValue = "Yes"
    )
    private String legacyType;

    public String getName() {
      return name;
    }

    public TestComplex getImportFile() {
      return importFile;
    }

  }

  @ComplexType(name = "Unreferenced", generate = true)
  private static class UnreferencedComplex {
    @CCD(label = "Value")
    private String value;
  }

  private static class TestComplex {

    private String file;

    public String getFile() {
      return file;
    }
  }

  private enum TestFixedList implements HasLabel, HasCode {
    @CCD(displayOrder = 7)
    FIRST("first-code", "First"),
    SECOND("second-code", "Second");

    private final String code;
    private final String label;

    TestFixedList(String code, String label) {
      this.code = code;
      this.label = label;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getLabel() {
      return label;
    }
  }

  private enum TestState {
    Open
  }

  private enum TestRole implements HasRole {
    TEST("caseworker-test"),
    TEST_WITHOUT_LIVE_FROM("caseworker-test-without-live-from"),
    @CCD(label = "Test role", liveFrom = "15/08/2024")
    CASE_ROLE("[TEST_ROLE]");

    private final String role;

    TestRole(String role) {
      this.role = role;
    }

    @Override
    public String getRole() {
      return role;
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
