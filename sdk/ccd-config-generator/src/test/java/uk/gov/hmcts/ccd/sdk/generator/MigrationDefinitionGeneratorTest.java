package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  public void generatesExactOptionalMigrationColumnsAndRegisteredDefinitions() {
    ConfigBuilderImpl<TestData, TestState, TestRole> builder = newBuilder();
    builder.caseHistoryLabel("History");
    builder.registerFixedList("external_list", TestFixedList.SECOND, TestFixedList.FIRST);
    builder.event("import")
        .forAllStates()
        .postStateWildcard()
        .name("Import")
        .omitShowEventNotes()
        .fields()
        .omitPageDisplayOrder()
        .externalMidEventCallbackUrl("${TEST_URL}/mid-event")
        .optionalNoSummary(TestData::getName)
        .complex(TestData::getImportFile, false)
        .eventToComplexTypeId("file")
        .mandatoryWithLabel(TestComplex::getFile, "File")
        .done()
        .done();

    File output = new File(tmp.getRoot(), "exact-definition");
    JSONConfigGenerator<TestData, TestState, TestRole> generator = new JSONConfigGenerator<>(List.of(
        new CaseFieldGenerator<>(),
        new CaseEventGenerator<>(),
        new CaseEventToFieldsGenerator<>(),
        new CaseEventToComplexTypesGenerator<>(),
        new FixedListGenerator<>()
    ));
    generator.writeConfig(output, builder.build());

    assertThat(rows(output, "CaseField.json"))
        .anySatisfy(row -> assertThat(row)
            .containsEntry("ID", "caseHistory")
            .containsEntry("Label", "History"));
    assertThat(onlyRow(output, "CaseEvent/import.json"))
        .containsEntry("PostConditionState", "*")
        .doesNotContainKey("ShowEventNotes");
    List<Map<String, Object>> eventFields = rows(output, "CaseEventToFields/import.json");
    assertThat(eventFields)
        .allSatisfy(row -> assertThat(row).doesNotContainKey("PageDisplayOrder"));
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
            .containsEntry("DisplayOrder", 2));
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

    public String getName() {
      return name;
    }

    public TestComplex getImportFile() {
      return importFile;
    }

  }

  private static class TestComplex {

    private String file;

    public String getFile() {
      return file;
    }
  }

  private enum TestFixedList implements HasLabel {
    @JsonProperty("first-code")
    FIRST("First"),
    @JsonProperty("second-code")
    SECOND("Second");

    private final String label;

    TestFixedList(String label) {
      this.label = label;
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
    TEST;

    @Override
    public String getRole() {
      return "caseworker-test";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
