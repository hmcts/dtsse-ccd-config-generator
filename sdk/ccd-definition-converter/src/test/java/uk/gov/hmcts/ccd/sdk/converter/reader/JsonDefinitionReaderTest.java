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
  void flatFileCollidingWithSameNamedFragmentDirectoryIsSkipped() throws Exception {
    // json2xlsx groups source files by literal base name and, per group, rewrites the whole sheet
    // from a fixed anchor cell; its file sort always processes a flat "X.json" before any path
    // under a same-named "X/" directory, so the directory's later write to the same sheet fully
    // overwrites the flat file's rows. None of the flat file's rows reach the real import — the
    // reader must not aggregate them either, or the "expected" side of a round-trip comparison
    // would carry phantom rows.
    URL url = getClass().getClassLoader().getResource("reader-fixtures/flat-dir-collision");
    Path input = Paths.get(url.toURI());
    ConversionOptions opts = optionsFor(input, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    List<SheetRow> rows = ir.rows(SheetName.CASE_EVENT_TO_COMPLEX_TYPES);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getString("ID")).contains("dirOnlyRow");
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

  // --- inert-column tests ---

  @Test
  void keyNamingNoCcdColumnIsDroppedAndReportedAsAdvisory() throws Exception {
    // sscs's real CaseEventToFields row for adjournCase/adjournCaseTime, trimmed: a stray comma
    // swallowed into the key. json2xlsx builds each row as headers.map(key => record[key]) against
    // ccd-template.xlsx, so the typo'd key matches no header, no cell is written, and the definition
    // store never sees the value — the row's correctly-spelled ShowSummaryChangeOption is the only
    // one that has ever applied. Reading the typo'd key would make the converter emit a definition
    // the team's own pipeline does not produce.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("CaseEventToFields.json"),
        "[{\"CaseEventID\":\"adjournCase\",\"CaseFieldID\":\"adjournCaseTime\","
            + "\",ShowSummaryChangeOption\":\"Y\",\"DisplayContext\":\"OPTIONAL\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    SheetRow row = ir.rows(SheetName.CASE_EVENT_TO_FIELDS).get(0);
    assertThat(row.getColumns()).containsOnlyKeys(
        "CaseEventID", "CaseFieldID", "DisplayContext");
    assertThat(gaps.getEntries()).hasSize(1);
    assertThat(gaps.getEntries().get(0).getCategory()).isEqualTo(GapCategory.UNSUPPORTED_COLUMN);
    assertThat(gaps.getEntries().get(0).getAction()).isEqualTo(GapAction.ADVISORY);
    assertThat(gaps.getEntries().get(0).getSheet()).isEqualTo("CaseEventToFields");
    assertThat(gaps.getEntries().get(0).getRowKey()).isEqualTo("adjournCase|adjournCaseTime");
    assertThat(gaps.getEntries().get(0).getColumn()).isEqualTo(",ShowSummaryChangeOption");
    assertThat(gaps.getEntries().get(0).getValue()).isEqualTo("Y");
    // The punctuation makes the author's intent unambiguous, so the report names the column the row
    // has silently never had rather than just calling the key unrecognised.
    assertThat(gaps.getEntries().get(0).getDetail())
        .contains("ShowSummaryChangeOption")
        .contains("stray punctuation");
  }

  @Test
  void columnNamesAreMatchedCaseInsensitivelyLikeTheImporter() throws Exception {
    // ColumnName.equalsColumnNameOrAlias compares equalsIgnoreCase and json2xlsx writes under the
    // template's own header casing, so sscs's SearchPartyDoB imports exactly as SearchPartyDOB would.
    // Dropping it as unknown would delete a value the real definition applies.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("SearchParty.json"),
        "[{\"CaseTypeID\":\"Benefit\",\"SearchPartyDoB\":\"appellantDob\","
            + "\"searchpartyname\":\"appellantName\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.SEARCH_PARTY).get(0).getColumns())
        .containsOnlyKeys("CaseTypeID", "SearchPartyDoB", "searchpartyname");
    assertThat(gaps.getEntries()).isEmpty();
  }

  @Test
  void authorsInlineDocumentationIsDroppedWithoutAGapEntry() throws Exception {
    // Civil annotates rows with _Comment/_Category/_Definition and fpl with Comment/Comments; these
    // are as inert as a typo but deliberately so. Reporting several hundred of them would bury the
    // handful of findings that are real, so they are dropped silently.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("CaseField.json"),
        "[{\"ID\":\"applicantName\",\"_Comment\":\"why\",\"Comment\":\"who\","
            + "\"Comments\":\"when\",\"comment_\":\"where\",\"_Category\":\"party\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.CASE_FIELD).get(0).getColumns()).containsOnlyKeys("ID");
    assertThat(gaps.getEntries()).isEmpty();
  }

  @Test
  void json2xlsxStageColumnsSurviveDespiteNotBeingTemplateHeaders() throws Exception {
    // AccessControl/UserRoles/UserRole match no header in ccd-template.xlsx, but they are not inert:
    // ccd-definition-processor's access-control-transformer runs BEFORE updateSheetDataJson and
    // expands them into per-role rows, renaming UserRole to AccessProfile. Dropping them would
    // discard every access grant in an fpl-style definition.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("AuthorisationCaseField.json"),
        "[{\"CaseFieldID\":\"applicantName\",\"UserRoles\":[\"caseworker\"],\"CRUD\":\"CRU\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.AUTHORISATION_CASE_FIELD).get(0).getColumns())
        .containsOnlyKeys("CaseFieldID", "UserRoles", "CRUD");
    assertThat(gaps.getEntries()).isEmpty();
  }

  @Test
  void inertKeysInFragmentDirectoriesAreDroppedToo() throws Exception {
    // The fragment layout is where sscs and fpl actually author their rows, so the drop cannot live
    // on the flat-file path alone.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    Path sheetDir = java.nio.file.Files.createDirectory(tmpDir.resolve("CaseEventToFields"));
    java.nio.file.Files.writeString(sheetDir.resolve("writeAdjournmentNotice.json"),
        "[{\"CaseEventID\":\"adjournCase\",\"CaseFieldID\":\"adjournCaseTime\","
            + "\",ShowSummaryChangeOption\":\"Y\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.CASE_EVENT_TO_FIELDS).get(0).getColumns())
        .containsOnlyKeys("CaseEventID", "CaseFieldID");
    assertThat(gaps.getEntries()).hasSize(1);
    assertThat(gaps.getEntries().get(0).getDetail())
        .contains("writeAdjournmentNotice.json");
  }

  @Test
  void misspeltColumnNameIsReportedWithoutGuessingTheIntendedColumn() throws Exception {
    // fpl's FieldShownCondition and civil's PageShowShowCondition misspell the column name itself,
    // not its punctuation. They are dropped for the same reason, but the report makes no claim about
    // which column was meant: a nearest-name search over 119 columns sounds equally confident when
    // it is wrong, and the reader has no basis to distinguish a misspelling from an unrelated key.
    Path tmpDir = java.nio.file.Files.createTempDirectory("reader-test");
    java.nio.file.Files.writeString(tmpDir.resolve("CaseEventToFields.json"),
        "[{\"CaseEventID\":\"e\",\"CaseFieldID\":\"f\",\"FieldShownCondition\":\"x = \\\"y\\\"\"}]");
    ConversionOptions opts = optionsFor(tmpDir, Map.of());
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = reader.read(opts, gaps);

    assertThat(ir.rows(SheetName.CASE_EVENT_TO_FIELDS).get(0).getColumns())
        .containsOnlyKeys("CaseEventID", "CaseFieldID");
    assertThat(gaps.getEntries()).hasSize(1);
    assertThat(gaps.getEntries().get(0).getColumn()).isEqualTo("FieldShownCondition");
    assertThat(gaps.getEntries().get(0).getDetail())
        .contains("names no CCD column")
        .doesNotContain("stray punctuation");
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
