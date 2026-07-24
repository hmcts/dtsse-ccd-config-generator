package uk.gov.hmcts.ccd.sdk.converter.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.ReportWriter;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.PassthroughSheet;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Writes the conversion's non-source outputs: passthrough JSON and gap reports.
 *
 * <p>Outputs produced under {@link ConversionOptions#getPassthroughDir()}:
 * <ul>
 *   <li>{@code base/<relativePath>} — base (unconditional) passthrough content
 *   <li>{@code <suffix>/<relativePath>} — overlay content active under a given env predicate
 *   <li>{@code manifest.json} — index of all sheets with merge metadata
 * </ul>
 *
 * <p>Outputs produced under {@link ConversionOptions#getReportDir()}:
 * <ul>
 *   <li>{@code gap-report.json} — machine-readable gap findings
 *   <li>{@code gap-report.md} — human-readable gap summary
 * </ul>
 */
public class GapAndPassthroughWriter implements ReportWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Override
  public void write(CaseTypeModel model, GapCollector gaps, ConversionOptions options) {
    writePassthrough(model, options);
    writeReports(model, gaps, options);
  }

  private void writePassthrough(CaseTypeModel model, ConversionOptions options) {
    Path passthroughDir = options.getPassthroughDir();
    List<Map<String, Object>> manifest = new ArrayList<>();

    for (PassthroughSheet sheet : model.getPassthroughSheets()) {
      String prefix = sheet.getOverlaySuffix() == null ? "base" : sheet.getOverlaySuffix();
      Path target = passthroughDir.resolve(prefix).resolve(sheet.getRelativePath());

      try {
        Files.createDirectories(target.getParent());
        MAPPER.writeValue(target.toFile(), sheet.getRows());
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed writing passthrough " + target, ex);
      }

      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("relativePath", sheet.getRelativePath());
      entry.put("primaryKeys", sheet.getPrimaryKeys());
      entry.put("overwriteColumns", sheet.getOverwriteColumns());
      entry.put("overlaySuffix", sheet.getOverlaySuffix());
      if (sheet.getOverlayCondition() != null) {
        entry.put("envVar", sheet.getOverlayCondition().getEnvVar());
        entry.put("value", sheet.getOverlayCondition().getExpectedValue());
        entry.put("negated", sheet.getOverlayCondition().isNegated());
      } else {
        entry.put("envVar", null);
        entry.put("value", null);
        entry.put("negated", null);
      }
      manifest.add(entry);
    }

    try {
      Files.createDirectories(passthroughDir);
      MAPPER.writeValue(passthroughDir.resolve("manifest.json").toFile(), manifest);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed writing passthrough manifest", ex);
    }
  }

  private void writeReports(CaseTypeModel model, GapCollector gaps, ConversionOptions options) {
    Path reportDir = options.getReportDir();
    try {
      Files.createDirectories(reportDir);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed creating report directory " + reportDir, ex);
    }
    writeGapReportJson(gaps, reportDir);
    writeGapReportMd(model, gaps, reportDir);
  }

  /**
   * The access-emission summary section: the composition scheme's total class count broken into
   * groups/atoms/dedicated fallbacks, the per-field {@code @CCD(access)} array distribution and the
   * mined-group table (see {@code AccessClassComputer}). Documents how many access classes each case
   * type needs and how readable its per-field arrays are.
   */
  private void appendAccessSummary(StringBuilder sb, CaseTypeModel model) {
    sb.append("\n## Access emission\n\n");
    sb.append("Total access classes: ").append(model.getAccessClasses().size()).append("\n\n");
    if (model.getAccessSummaryNote() != null) {
      sb.append(model.getAccessSummaryNote()).append("\n");
    }
  }

  private void writeGapReportJson(GapCollector gaps, Path reportDir) {
    List<GapEntry> entries = gaps.getEntries();
    Map<String, Object> report = new LinkedHashMap<>();

    List<Map<String, Object>> entriesList = new ArrayList<>();
    for (GapEntry entry : entries) {
      Map<String, Object> entryMap = new LinkedHashMap<>();
      entryMap.put("sheet", entry.getSheet());
      entryMap.put("rowKey", entry.getRowKey());
      entryMap.put("column", entry.getColumn());
      entryMap.put("value", entry.getValue());
      entryMap.put("category", entry.getCategory().name());
      entryMap.put("action", entry.getAction().name());
      entryMap.put("detail", entry.getDetail());
      entriesList.add(entryMap);
    }
    report.put("entries", entriesList);

    Map<String, Long> byCategory = new TreeMap<>();
    Map<String, Long> byAction = new TreeMap<>();
    for (GapCategory cat : GapCategory.values()) {
      long count = entries.stream().filter(e -> e.getCategory() == cat).count();
      if (count > 0) {
        byCategory.put(cat.name(), count);
      }
    }
    for (GapAction action : GapAction.values()) {
      long count = entries.stream().filter(e -> e.getAction() == action).count();
      if (count > 0) {
        byAction.put(action.name(), count);
      }
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total", (long) entries.size());
    summary.put("byCategory", byCategory);
    summary.put("byAction", byAction);
    report.put("summary", summary);

    try {
      MAPPER.writeValue(reportDir.resolve("gap-report.json").toFile(), report);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed writing gap-report.json", ex);
    }
  }

  private void writeGapReportMd(CaseTypeModel model, GapCollector gaps, Path reportDir) {
    StringBuilder sb = new StringBuilder();
    sb.append("# CCD Definition Conversion Gap Report\n\n");
    appendAccessSummary(sb, model);
    sb.append('\n');
    sb.append("## What Each Action Means\n\n");
    sb.append("| Action | Meaning |\n");
    sb.append("|--------|---------|\n");
    sb.append("| `PASSTHROUGH_ROW` | Full row written to passthrough JSON, merged after generation |\n");
    sb.append("| `PASSTHROUGH_COLUMN` | Only inexpressible columns grafted onto generated row |\n");
    sb.append("| `CONDITIONAL_CODE` | Expressed as environment-guarded Java code |\n");
    sb.append("| `ADVISORY` | Redundant input declaration; no output, safe to delete |\n");
    sb.append("| `OMITTED_FAIL` | Could not be expressed; conversion fails unless --allow-gaps |\n\n");

    List<GapEntry> entries = gaps.getEntries();
    sb.append("## Summary\n\n");
    sb.append("Total gap findings: ").append(entries.size()).append("\n\n");
    sb.append("### By Category\n\n");
    sb.append("| Category | Count |\n");
    sb.append("|----------|-------|\n");
    for (GapCategory cat : GapCategory.values()) {
      long count = entries.stream().filter(e -> e.getCategory() == cat).count();
      if (count > 0) {
        sb.append("| `").append(cat.name()).append("` | ").append(count).append(" |\n");
      }
    }
    sb.append("\n### By Action\n\n");
    sb.append("| Action | Count |\n");
    sb.append("|--------|-------|\n");
    for (GapAction action : GapAction.values()) {
      long count = entries.stream().filter(e -> e.getAction() == action).count();
      if (count > 0) {
        sb.append("| `").append(action.name()).append("` | ").append(count).append(" |\n");
      }
    }
    sb.append("\n## All Gap Entries\n\n");
    sb.append("| Sheet | Row Key | Column | Value | Action | Detail |\n");
    sb.append("|-------|---------|--------|-------|--------|--------|\n");
    for (GapEntry entry : entries) {
      sb.append("| ").append(entry.getSheet())
          .append(" | ").append(entry.getRowKey())
          .append(" | ").append(entry.getColumn() != null ? entry.getColumn() : "")
          .append(" | ").append(entry.getValue() != null ? entry.getValue() : "")
          .append(" | `").append(entry.getAction().name()).append("`")
          .append(" | ").append(entry.getDetail() != null ? entry.getDetail() : "")
          .append(" |\n");
    }

    try {
      Files.writeString(reportDir.resolve("gap-report.md"), sb.toString());
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed writing gap-report.md", ex);
    }
  }
}
