package uk.gov.hmcts.ccd.sdk.converter.passthrough;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PassthroughMerger}.
 */
class PassthroughMergerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
      new TypeReference<>() {};

  @TempDir
  Path tempDir;

  @Test
  void mergesBaseSheetIntoExistingFile() throws IOException {
    // Arrange: generated definition has 2 existing rows
    Path generatedDir = tempDir.resolve("generated");
    Files.createDirectories(generatedDir);
    List<Map<String, Object>> existing = new ArrayList<>();
    existing.add(row("ID", "field1", "Label", "Generated"));
    MAPPER.writeValue(generatedDir.resolve("CaseField.json").toFile(), existing);

    // Passthrough base adds a new row
    Path passthroughDir = tempDir.resolve("passthrough");
    Path baseDir = passthroughDir.resolve("base");
    Files.createDirectories(baseDir);
    List<Map<String, Object>> passthrough = new ArrayList<>();
    passthrough.add(row("ID", "field2", "Label", "Passthrough"));
    MAPPER.writeValue(baseDir.resolve("CaseField.json").toFile(), passthrough);

    // Manifest
    writeManifest(passthroughDir, List.of(
        manifestEntry("CaseField.json", List.of("ID"), null, null, null, null)));

    PassthroughMerger.merge(passthroughDir, generatedDir);

    List<Map<String, Object>> result = MAPPER.readValue(
        generatedDir.resolve("CaseField.json").toFile(), LIST_MAP_TYPE);
    assertThat(result).hasSize(2);
    List<Object> ids = result.stream().map(rw -> rw.get("ID")).toList();
    assertThat(ids).contains("field1", "field2");
  }

  @Test
  void mergesColumnIntoExistingRow() throws IOException {
    Path generatedDir = tempDir.resolve("generated2");
    Files.createDirectories(generatedDir);
    List<Map<String, Object>> existing = new ArrayList<>();
    existing.add(row("ID", "field1", "Label", "Original"));
    MAPPER.writeValue(generatedDir.resolve("CaseField.json").toFile(), existing);

    Path passthroughDir = tempDir.resolve("passthrough2");
    Path baseDir = passthroughDir.resolve("base");
    Files.createDirectories(baseDir);
    List<Map<String, Object>> passthrough = new ArrayList<>();
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("ID", "field1");
    extra.put("ExtraColumn", "extra-value");
    passthrough.add(extra);
    MAPPER.writeValue(baseDir.resolve("CaseField.json").toFile(), passthrough);

    writeManifest(passthroughDir, List.of(
        manifestEntry("CaseField.json", List.of("ID"), null, null, null, null)));

    PassthroughMerger.merge(passthroughDir, generatedDir);

    List<Map<String, Object>> result = MAPPER.readValue(
        generatedDir.resolve("CaseField.json").toFile(), LIST_MAP_TYPE);
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).containsEntry("Label", "Original"); // not overwritten
    assertThat(result.get(0)).containsEntry("ExtraColumn", "extra-value"); // grafted
  }

  @Test
  void mergesSuffixSheetWhenConditionActive() throws IOException {
    System.setProperty("CCD_DEF_ENV", "prod");
    try {
      Path generatedDir = tempDir.resolve("generated3");
      Files.createDirectories(generatedDir);
      MAPPER.writeValue(generatedDir.resolve("Banner.json").toFile(), new ArrayList<>());

      Path passthroughDir = tempDir.resolve("passthrough3");
      Path prodDir = passthroughDir.resolve("prod");
      Files.createDirectories(prodDir);
      List<Map<String, Object>> prodRows = new ArrayList<>();
      prodRows.add(row("CaseTypeID", "MyCase", "BannerDescription", "Prod banner"));
      MAPPER.writeValue(prodDir.resolve("Banner.json").toFile(), prodRows);

      writeManifest(passthroughDir, List.of(
          manifestEntry("Banner.json", List.of("CaseTypeID", "BannerDescription"),
              "prod", "CCD_DEF_ENV", "prod", false)));

      PassthroughMerger.merge(passthroughDir, generatedDir);

      List<Map<String, Object>> result = MAPPER.readValue(
          generatedDir.resolve("Banner.json").toFile(), LIST_MAP_TYPE);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).containsEntry("BannerDescription", "Prod banner");
    } finally {
      System.clearProperty("CCD_DEF_ENV");
    }
  }

  @Test
  void skipsSuffixSheetWhenConditionInactive() throws IOException {
    Path generatedDir = tempDir.resolve("generated4");
    Files.createDirectories(generatedDir);
    List<Map<String, Object>> existing = new ArrayList<>();
    existing.add(row("CaseTypeID", "MyCase", "BannerDescription", "Default"));
    MAPPER.writeValue(generatedDir.resolve("Banner.json").toFile(), existing);

    Path passthroughDir = tempDir.resolve("passthrough4");
    Path prodDir = passthroughDir.resolve("prod");
    Files.createDirectories(prodDir);
    List<Map<String, Object>> prodRows = new ArrayList<>();
    prodRows.add(row("CaseTypeID", "MyCase", "BannerDescription", "Prod banner"));
    MAPPER.writeValue(prodDir.resolve("Banner.json").toFile(), prodRows);

    // CCD_DEF_ENV not set → condition inactive
    writeManifest(passthroughDir, List.of(
        manifestEntry("Banner.json", List.of("CaseTypeID", "BannerDescription"),
            "prod", "CCD_DEF_ENV_TEST_SKIP", "prod", false)));

    PassthroughMerger.merge(passthroughDir, generatedDir);

    List<Map<String, Object>> result = MAPPER.readValue(
        generatedDir.resolve("Banner.json").toFile(), LIST_MAP_TYPE);
    // No change — condition was inactive
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).containsEntry("BannerDescription", "Default");
  }

  @Test
  void skipsSuffixSheetWhenValueIsNull() throws IOException {
    System.setProperty("CCD_DEF_ENV", "prod");
    try {
      Path generatedDir = tempDir.resolve("generated6");
      Files.createDirectories(generatedDir);
      List<Map<String, Object>> existing = new ArrayList<>();
      existing.add(row("CaseTypeID", "MyCase", "BannerDescription", "Default"));
      MAPPER.writeValue(generatedDir.resolve("Banner.json").toFile(), existing);

      Path passthroughDir = tempDir.resolve("passthrough6");
      Path prodDir = passthroughDir.resolve("prod");
      Files.createDirectories(prodDir);
      List<Map<String, Object>> prodRows = new ArrayList<>();
      prodRows.add(row("CaseTypeID", "MyCase", "BannerDescription", "Prod banner"));
      MAPPER.writeValue(prodDir.resolve("Banner.json").toFile(), prodRows);

      // value is null: even though the CCD_DEF_ENV system property matches "prod",
      // a manifest entry with no expected value must never activate the overlay.
      writeManifest(passthroughDir, List.of(
          manifestEntry("Banner.json", List.of("CaseTypeID", "BannerDescription"),
              "prod", "CCD_DEF_ENV", null, false)));

      PassthroughMerger.merge(passthroughDir, generatedDir);

      List<Map<String, Object>> result = MAPPER.readValue(
          generatedDir.resolve("Banner.json").toFile(), LIST_MAP_TYPE);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).containsEntry("BannerDescription", "Default");
    } finally {
      System.clearProperty("CCD_DEF_ENV");
    }
  }

  @Test
  void createsTargetFileWhenAbsent() throws IOException {
    Path generatedDir = tempDir.resolve("generated5");
    Files.createDirectories(generatedDir);
    // No pre-existing file

    Path passthroughDir = tempDir.resolve("passthrough5");
    Path baseDir = passthroughDir.resolve("base");
    Files.createDirectories(baseDir);
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(row("ID", "newField", "Label", "New"));
    MAPPER.writeValue(baseDir.resolve("NewSheet.json").toFile(), rows);

    writeManifest(passthroughDir, List.of(
        manifestEntry("NewSheet.json", List.of("ID"), null, null, null, null)));

    PassthroughMerger.merge(passthroughDir, generatedDir);

    Path target = generatedDir.resolve("NewSheet.json");
    assertThat(target).exists();
    List<Map<String, Object>> result = MAPPER.readValue(target.toFile(), LIST_MAP_TYPE);
    assertThat(result).hasSize(1);
    assertThat(result.get(0)).containsEntry("ID", "newField");
  }

  // --- helpers ---

  private Map<String, Object> row(String key1, Object val1, String key2, Object val2) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key1, val1);
    map.put(key2, val2);
    return map;
  }

  private Map<String, Object> manifestEntry(String relativePath, List<String> primaryKeys,
      String overlaySuffix, String envVar, Object value, Object negated) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("relativePath", relativePath);
    entry.put("primaryKeys", primaryKeys);
    entry.put("overlaySuffix", overlaySuffix);
    entry.put("envVar", envVar);
    entry.put("value", value);
    entry.put("negated", negated);
    return entry;
  }

  private void writeManifest(Path passthroughDir, List<Map<String, Object>> entries)
      throws IOException {
    Files.createDirectories(passthroughDir);
    MAPPER.writeValue(passthroughDir.resolve("manifest.json").toFile(), entries);
  }
}
