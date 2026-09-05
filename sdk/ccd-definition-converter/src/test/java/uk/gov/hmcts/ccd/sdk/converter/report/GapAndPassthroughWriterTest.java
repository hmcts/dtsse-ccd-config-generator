package uk.gov.hmcts.ccd.sdk.converter.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.EventModel;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.PassthroughSheet;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Unit tests for {@link GapAndPassthroughWriter}.
 */
class GapAndPassthroughWriterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
      new TypeReference<>() {};

  @TempDir
  Path tempDir;

  private final GapAndPassthroughWriter writer = new GapAndPassthroughWriter();

  @Test
  void writesBasePassthroughFile() throws IOException {
    PassthroughSheet sheet = PassthroughSheet.builder()
        .relativePath("Banner.json")
        .primaryKeys(List.of("CaseTypeID", "BannerDescription"))
        .overlaySuffix(null)
        .overlayCondition(null)
        .rows(List.of(Map.of("CaseTypeID", "MyCase", "BannerDescription", "Test banner")))
        .build();

    CaseTypeModel model = buildModel(List.of(sheet), List.of());
    writer.write(model, new GapCollector(), buildOptions());

    Path baseFile = tempDir.resolve("passthrough/base/Banner.json");
    assertThat(baseFile).exists();
    List<Map<String, Object>> rows = MAPPER.readValue(baseFile.toFile(), LIST_MAP_TYPE);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("BannerDescription", "Test banner");
  }

  @Test
  void writesSuffixPassthroughFile() throws IOException {
    OverlayCondition cond = new OverlayCondition("CCD_DEF_ENV", "prod", false);
    PassthroughSheet sheet = PassthroughSheet.builder()
        .relativePath("Banner.json")
        .primaryKeys(List.of("CaseTypeID"))
        .overlaySuffix("prod")
        .overlayCondition(cond)
        .rows(List.of(Map.of("CaseTypeID", "MyCase")))
        .build();

    writer.write(buildModel(List.of(sheet), List.of()), new GapCollector(), buildOptions());

    Path suffixFile = tempDir.resolve("passthrough/prod/Banner.json");
    assertThat(suffixFile).exists();
  }

  @Test
  void writesManifestJson() throws IOException {
    OverlayCondition cond = new OverlayCondition("CCD_DEF_ENV", "prod", false);
    PassthroughSheet base = PassthroughSheet.builder()
        .relativePath("A.json").primaryKeys(List.of("ID"))
        .overlaySuffix(null).overlayCondition(null)
        .rows(List.of()).build();
    PassthroughSheet suffix = PassthroughSheet.builder()
        .relativePath("B.json").primaryKeys(List.of("ID", "Name"))
        .overlaySuffix("prod").overlayCondition(cond)
        .rows(List.of()).build();

    writer.write(buildModel(List.of(base, suffix), List.of()), new GapCollector(), buildOptions());

    Path manifestPath = tempDir.resolve("passthrough/manifest.json");
    assertThat(manifestPath).exists();
    List<Map<String, Object>> manifest = MAPPER.readValue(manifestPath.toFile(), LIST_MAP_TYPE);
    assertThat(manifest).hasSize(2);

    Map<String, Object> baseEntry = manifest.get(0);
    assertThat(baseEntry).containsEntry("relativePath", "A.json");
    assertThat(baseEntry.get("overlaySuffix")).isNull();

    Map<String, Object> suffixEntry = manifest.get(1);
    assertThat(suffixEntry).containsEntry("overlaySuffix", "prod");
    assertThat(suffixEntry).containsEntry("envVar", "CCD_DEF_ENV");
    assertThat(suffixEntry).containsEntry("value", "prod");
    assertThat(suffixEntry).containsEntry("negated", false);
  }

  @Test
  void writesGapReportJson() throws IOException {
    GapCollector gaps = new GapCollector();
    gaps.add(GapEntry.builder()
        .sheet("CaseEvent")
        .rowKey("startAppeal")
        .column("SignificantEvent")
        .value("Yes")
        .category(GapCategory.UNSUPPORTED_COLUMN)
        .action(GapAction.PASSTHROUGH_COLUMN)
        .detail("No builder API; grafted via passthrough")
        .build());

    writer.write(buildModel(List.of(), List.of()), gaps, buildOptions());

    Path reportFile = tempDir.resolve("reports/gap-report.json");
    assertThat(reportFile).exists();
    Map<String, Object> report = MAPPER.readValue(reportFile.toFile(), MAP_TYPE);

    assertThat(report).containsKey("entries");
    assertThat(report).containsKey("summary");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) report.get("entries");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0)).containsEntry("sheet", "CaseEvent");
    assertThat(entries.get(0)).containsEntry("category", "UNSUPPORTED_COLUMN");

    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
    assertThat(summary).containsEntry("total", 1);
  }

  @Test
  void writesGapReportMarkdown() throws IOException {
    GapCollector gaps = new GapCollector();
    gaps.add(GapEntry.builder()
        .sheet("CaseEvent")
        .rowKey("myEvent")
        .category(GapCategory.UNSUPPORTED_COLUMN)
        .action(GapAction.PASSTHROUGH_COLUMN)
        .detail("test detail")
        .build());

    writer.write(buildModel(List.of(), List.of()), gaps, buildOptions());

    Path mdFile = tempDir.resolve("reports/gap-report.md");
    assertThat(mdFile).exists();
    String content = Files.readString(mdFile);
    assertThat(content).contains("UNSUPPORTED_COLUMN");
    assertThat(content).contains("myEvent");
    assertThat(content).contains("PASSTHROUGH_COLUMN");
  }

  // --- helpers ---

  private ConversionOptions buildOptions() {
    return ConversionOptions.builder()
        .passthroughDir(tempDir.resolve("passthrough"))
        .reportDir(tempDir.resolve("reports"))
        .configPackage("uk.gov.hmcts.test.config")
        .modelPackage("uk.gov.hmcts.test.model")
        .outputSrc(tempDir)
        .inputs(List.of())
        .eventsPerConfig(40)
        .overlaySuffixes(Map.of())
        .build();
  }

  private CaseTypeModel buildModel(List<PassthroughSheet> sheets, List<EventModel> events) {
    return CaseTypeModel.builder()
        .caseTypeId("MyCase")
        .events(events)
        .passthroughSheets(sheets)
        .states(List.of())
        .roles(List.of())
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of())
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .build();
  }
}
