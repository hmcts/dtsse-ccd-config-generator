package uk.gov.hmcts.ccd.sdk.converter.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

class JsonDefinitionReaderTest {

  private final JsonDefinitionReader reader = new JsonDefinitionReader();

  private Path goldenInput() throws URISyntaxException {
    URL url = getClass().getClassLoader().getResource("golden/minimal/input");
    return Paths.get(url.toURI());
  }

  private Path fragmentFixture() throws URISyntaxException {
    URL url = getClass().getClassLoader().getResource("reader-fixtures/fragments");
    return Paths.get(url.toURI());
  }

  private ConversionOptions optionsFor(Path inputDir, Map<String, OverlayCondition> overlays) {
    return ConversionOptions.builder()
        .inputs(List.of(inputDir))
        .overlaySuffixes(overlays)
        .build();
  }

  /**
   * The suffixes the golden fixture uses: it carries both -prod and -nonprod overlay files,
   * so tests reading it must configure both (an unknown suffix is a hard error by design).
   */
  private Map<String, OverlayCondition> goldenOverlays() {
    return Map.of(
        "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
        "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
  }

  // --- golden fixture tests ---

  @Test
  void goldenFixtureAllKnownSheetsFound() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.hasSheet(SheetName.CASE_EVENT)).isTrue();
    assertThat(ir.hasSheet(SheetName.CASE_FIELD)).isTrue();
    assertThat(ir.hasSheet(SheetName.CASE_TYPE)).isTrue();
    assertThat(ir.hasSheet(SheetName.JURISDICTION)).isTrue();
    assertThat(ir.hasSheet(SheetName.AUTHORISATION_CASE_EVENT)).isTrue();
    assertThat(ir.hasSheet(SheetName.AUTHORISATION_CASE_FIELD)).isTrue();
    assertThat(ir.hasSheet(SheetName.AUTHORISATION_CASE_STATE)).isTrue();
    assertThat(ir.hasSheet(SheetName.AUTHORISATION_CASE_TYPE)).isTrue();
  }

  @Test
  void goldenFixtureCaseEventHasThreeBaseRows() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> baseRows = ir.rows(SheetName.CASE_EVENT, SheetRow::isBase);
    assertThat(baseRows).hasSize(3);
  }

  @Test
  void goldenFixtureCaseEventProdFileAddsOneOverlayRow() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> overlayRows = ir.rows(SheetName.CASE_EVENT,
        r -> r.getOverlayTags().contains("prod"));
    assertThat(overlayRows).hasSize(1);
    assertThat(overlayRows.get(0).getString("ID")).contains("archiveCase");
  }

  @Test
  void goldenFixtureColumnOrderPreserved() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    SheetRow firstEvent = ir.rows(SheetName.CASE_EVENT, SheetRow::isBase).get(0);
    List<String> keys = List.copyOf(firstEvent.getColumns().keySet());
    assertThat(keys.get(0)).isEqualTo("LiveFrom");
    assertThat(keys.get(1)).isEqualTo("CaseTypeID");
    assertThat(keys.get(2)).isEqualTo("ID");
  }

  @Test
  void goldenFixtureNumericValuesStayNumeric() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    SheetRow firstEvent = ir.rows(SheetName.CASE_EVENT, SheetRow::isBase).get(0);
    Object displayOrder = firstEvent.getColumns().get("DisplayOrder");
    assertThat(displayOrder).isInstanceOf(Number.class);
  }

  @Test
  void goldenFixturePlaceholderPreservedVerbatim() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    SheetRow firstEvent = ir.rows(SheetName.CASE_EVENT, SheetRow::isBase).get(0);
    assertThat(firstEvent.getString("CallBackURLAboutToSubmitEvent"))
        .contains("${CCD_DEF_BASE_URL}/create/about-to-submit");
  }

  @Test
  void goldenFixtureNoGapsOnKnownSheets() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    reader.read(opts, gaps);

    assertThat(gaps.getEntries()).isEmpty();
  }

  // --- fragment directory tests ---

  @Test
  void fragmentDirectoryAliasNameResolvesToCorrectSheet() throws Exception {
    ConversionOptions opts = optionsFor(fragmentFixture(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.hasSheet(SheetName.CASE_EVENT_TO_COMPLEX_TYPES)).isTrue();
  }

  @Test
  void fragmentDirectoryBaseRowHasEmptyOverlayTags() throws Exception {
    ConversionOptions opts = optionsFor(fragmentFixture(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> base = ir.rows(SheetName.CASE_EVENT_TO_COMPLEX_TYPES, SheetRow::isBase);
    assertThat(base).hasSize(1);
    assertThat(base.get(0).getString("CaseEventID")).contains("someEvent");
  }

  @Test
  void fragmentFileSuffixInsideDirectoryGivesOverlayTag() throws Exception {
    ConversionOptions opts = optionsFor(fragmentFixture(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> overlay = ir.rows(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
        r -> r.getOverlayTags().contains("prod"));
    assertThat(overlay).hasSize(1);
    assertThat(overlay.get(0).getString("CaseEventID")).contains("anotherEvent");
  }

  @Test
  void singleObjectJsonFileReadAsOneRow() throws Exception {
    ConversionOptions opts = optionsFor(fragmentFixture(),
        goldenOverlays());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> complexRows = ir.rows(SheetName.COMPLEX_TYPES);
    assertThat(complexRows).hasSize(1);
    assertThat(complexRows.get(0).getString("ID")).contains("SingleObjectType");
  }

  @Test
  void kebabCaseFragmentNamesAreBaseRowsNotOverlayErrors() throws Exception {
    // Mirrors real fpl layouts: CaseEvent/manage-orders.json,
    // CaseEvent/addGatekeepingOrder/gatekeeping-order.json — dashes in fragment names must
    // not be interpreted as overlay suffixes and must not error.
    ConversionOptions opts = optionsFor(fragmentFixture(),
        Map.of(
            "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
            "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod")));
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> caseEventRows = ir.rows(SheetName.CASE_EVENT);
    assertThat(caseEventRows).hasSize(3);
    assertThat(caseEventRows).allMatch(SheetRow::isBase);
    assertThat(caseEventRows)
        .extracting(r -> r.getString("ID").orElseThrow())
        .containsExactlyInAnyOrder("manageOrders", "addGatekeepingOrder", "closeCaseTesting");
    assertThat(gaps.getEntries()).isEmpty();
  }

  @Test
  void fragmentSuffixMatchesLongestConfiguredSuffix() throws Exception {
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    Path sheetDir = java.nio.file.Files.createDirectory(tmpDir.resolve("CaseEvent"));
    java.nio.file.Files.writeString(sheetDir.resolve("workAllocation-WA-nonprod.json"),
        "[{\"ID\":\"waEvent\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of(
        "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"),
        "WA-nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod")));
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> rows = ir.rows(SheetName.CASE_EVENT);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOverlayTags()).containsExactly("WA-nonprod");
  }

  @Test
  void fragmentWithUnconfiguredDashEndingIsBaseNotError() throws Exception {
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    Path sheetDir = java.nio.file.Files.createDirectory(tmpDir.resolve("CaseEvent"));
    java.nio.file.Files.writeString(sheetDir.resolve("AuthorisationCaseEvent-testing.json"),
        "[{\"ID\":\"testingEvent\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of(
        "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
        "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod")));
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> rows = ir.rows(SheetName.CASE_EVENT);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).isBase()).isTrue();
    assertThat(gaps.getEntries()).isEmpty();
  }

  // --- overlay suffix validation tests ---

  @Test
  void unknownSuffixInFlatFileCausesError() throws Exception {
    ConversionOptions opts = optionsFor(goldenInput(), Map.of());
    GapCollector gaps = new GapCollector();

    assertThatThrownBy(() -> reader.read(opts, gaps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prod")
        .hasMessageContaining("Configured suffixes");
  }

  @Test
  void multiPartSuffixConfiguredAsSingleKeyIsAccepted() throws Exception {
    ConversionOptions opts = ConversionOptions.builder()
        .inputs(List.of(goldenInput()))
        .overlaySuffixes(Map.of(
            "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
            "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"),
            "WA-nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod")))
        .build();
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);
    assertThat(ir.hasSheet(SheetName.CASE_EVENT)).isTrue();
  }

  // --- unknown sheet gap tests ---

  @Test
  void unknownTopLevelSheetFileRecordedAsUnsupportedSheetGap() throws Exception {
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("ChangeHistory.json"), "[{}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    reader.read(opts, gaps);

    assertThat(gaps.getEntries()).hasSize(1);
    assertThat(gaps.getEntries().get(0).getCategory()).isEqualTo(GapCategory.UNSUPPORTED_SHEET);
    assertThat(gaps.getEntries().get(0).getAction()).isEqualTo(GapAction.OMITTED_FAIL);
    assertThat(gaps.getEntries().get(0).getSheet()).isEqualTo("ChangeHistory");
  }

  @Test
  void unknownTopLevelSheetFileIsSkippedNotLoaded() throws Exception {
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("ChangeHistory.json"), "[{}]");
    java.nio.file.Files.writeString(tmpDir.resolve("CaseType.json"),
        "[{\"ID\":\"TestType\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.CASE_TYPE)).hasSize(1);
  }

  // --- malformed JSON test ---

  @Test
  void malformedJsonReportsFilePath() throws Exception {
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("CaseField.json"), "{ NOT VALID JSON");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();

    assertThatThrownBy(() -> reader.read(opts, gaps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CaseField.json");
  }

  // --- multiple inputs test ---

  @Test
  void multipleInputDirectoriesAppendRows() throws Exception {
    Path dir1 = java.nio.file.Files.createTempDirectory("reader-test-1");
    Path dir2 = java.nio.file.Files.createTempDirectory("reader-test-2");
    java.nio.file.Files.writeString(dir1.resolve("CaseType.json"),
        "[{\"ID\":\"Type1\"}]");
    java.nio.file.Files.writeString(dir2.resolve("CaseType.json"),
        "[{\"ID\":\"Type2\"}]");
    ConversionOptions opts = ConversionOptions.builder()
        .inputs(List.of(dir1, dir2))
        .overlaySuffixes(Map.of())
        .build();
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.CASE_TYPE)).hasSize(2);
  }
}
