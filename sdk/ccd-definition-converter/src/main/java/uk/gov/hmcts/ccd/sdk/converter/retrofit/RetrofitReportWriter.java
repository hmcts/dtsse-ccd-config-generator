package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes a {@link RetrofitReport} to {@code retrofit-report.json} (structured) and
 * {@code retrofit-report.md} (human-readable) under the report directory.
 */
public final class RetrofitReportWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private RetrofitReportWriter() {
  }

  /**
   * Writes both report files.
   *
   * @param report the completed report
   * @param reportDir the directory to write into (created if absent)
   */
  public static void write(RetrofitReport report, Path reportDir) {
    try {
      Files.createDirectories(reportDir);
      MAPPER.writeValue(reportDir.resolve("retrofit-report.json").toFile(), report);
      Files.writeString(reportDir.resolve("retrofit-report.md"), renderMarkdown(report));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed writing retrofit report to " + reportDir, e);
    }
  }

  /**
   * Renders the report as Markdown.
   *
   * @param report the completed report
   * @return the Markdown document
   */
  public static String renderMarkdown(RetrofitReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append("# CCD Retrofit Match Report\n\n");
    sb.append("- **Case type:** ").append(report.getCaseTypeId()).append("\n");
    sb.append("- **Model class:** `").append(report.getModelClass()).append("`\n");
    sb.append("- **Model source root:** `").append(report.getModelSourceRoot()).append("`\n\n");

    if (report.isMapBased()) {
      sb.append("## Verdict: retrofit not applicable\n\n");
      sb.append(report.getNotApplicableReason()).append("\n\n");
      appendStateSection(sb, report);
      return sb.toString();
    }

    appendSummary(sb, report);
    appendBucketSection(sb, report, "Exact matches", RetrofitReport.Bucket.EXACT_MATCH,
        "Name resolves and the Java type infers to the definition's FieldType — annotate only.");
    appendBucketSection(sb, report, "Type conflicts", RetrofitReport.Bucket.TYPE_CONFLICT,
        "Property resolves but the inferred type differs — add a @CCD type override. Concrete "
            + "collection wrappers (decision 8) need @CCD(typeParameterOverride=…).");
    appendBucketSection(sb, report, "Unmatched definition fields",
        RetrofitReport.Bucket.UNMATCHED_DEFINITION_FIELD,
        "No Java property resolves — synthesise a typed @CCD field on the model class (decision 4).");
    appendUnmatchedJava(sb, report);
    appendStateSection(sb, report);
    appendCollectionSection(sb, report);
    appendAnnotationSection(sb, report);
    return sb.toString();
  }

  private static void appendSummary(StringBuilder sb, RetrofitReport report) {
    sb.append("## Summary\n\n");
    sb.append("| Metric | Value |\n|---|---|\n");
    sb.append(row("Total CaseField IDs", report.getTotalDefinitionFields()));
    sb.append(row("Label fields (excluded)", report.getLabelFields()));
    sb.append(row("Data-bearing fields (denominator)", report.getDataBearingFields()));
    sb.append(row("Resolvable model properties", report.getResolvableModelProperties()));
    sb.append("\n");
    sb.append("| Bucket | Count | % of data-bearing |\n|---|---|---|\n");
    sb.append(pctRow("EXACT_MATCH", report.getExactMatches(), report.getDataBearingFields()));
    sb.append(pctRow("TYPE_CONFLICT", report.getTypeConflicts(), report.getDataBearingFields()));
    sb.append(pctRow("UNMATCHED_DEFINITION_FIELD", report.getUnmatchedDefinitionFields(),
        report.getDataBearingFields()));
    sb.append("\n");
    sb.append(String.format(Locale.ROOT,
        "**Resolved (exact + type-conflict): %.1f%%. Exact: %.1f%%.**%n%n",
        report.resolvedPercent(), report.exactPercent()));
  }

  private static void appendBucketSection(StringBuilder sb, RetrofitReport report, String title,
      RetrofitReport.Bucket bucket, String blurb) {
    List<RetrofitReport.FieldFinding> fields = report.getFields().stream()
        .filter(f -> f.getBucket() == bucket)
        .collect(Collectors.toList());
    sb.append("## ").append(title).append(" (").append(fields.size()).append(")\n\n");
    sb.append(blurb).append("\n\n");
    if (fields.isEmpty()) {
      sb.append("_None._\n\n");
      return;
    }
    sb.append("| CaseField ID | Def FieldType | Model member | Inferred | Action |\n");
    sb.append("|---|---|---|---|---|\n");
    for (RetrofitReport.FieldFinding f : fields) {
      sb.append("| `").append(f.getId()).append("` | ")
          .append(nullToBlank(f.getDefinitionFieldType())).append(" | ")
          .append(memberRef(f)).append(" | ")
          .append(nullToBlank(f.getInferredFieldType())).append(" | ")
          .append(nullToBlank(f.getAction())).append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendUnmatchedJava(StringBuilder sb, RetrofitReport report) {
    List<RetrofitReport.UnmatchedJavaField> fields = report.getUnmatchedJavaFields();
    sb.append("## Unmatched Java fields (").append(fields.size()).append(")\n\n");
    sb.append("Model properties with no definition ID — candidates for `@CCD(ignore = true)`.\n\n");
    if (fields.isEmpty()) {
      sb.append("_None._\n\n");
      return;
    }
    sb.append("| Model ID | Class | Member | Action |\n|---|---|---|---|\n");
    for (RetrofitReport.UnmatchedJavaField f : fields) {
      sb.append("| `").append(f.getId()).append("` | ")
          .append(nullToBlank(f.getModelClass())).append(" | ")
          .append(nullToBlank(f.getMemberName())).append(" | ")
          .append(nullToBlank(f.getAction())).append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendStateSection(StringBuilder sb, RetrofitReport report) {
    RetrofitReport.StateVerdict verdict = report.getStateVerdict();
    if (verdict == null) {
      return;
    }
    sb.append("## State enum\n\n");
    sb.append(verdict.getSummary()).append("\n\n");
    if (verdict.isStateEnumFound()) {
      sb.append("| Metric | Value |\n|---|---|\n");
      sb.append(row("State enum", verdict.getStateEnumClass()));
      sb.append(row("toString() overridden", String.valueOf(verdict.isToStringOverridden())));
      sb.append(row("Definition states", verdict.getDefinitionStates()));
      sb.append(row("Matched", verdict.getMatchedStates()));
      sb.append(row("Conflicting", verdict.getConflictingStates()));
      sb.append("\n");
      if (!verdict.getConflicts().isEmpty()) {
        sb.append("Conflicting state IDs (no matching enum constant): ")
            .append(verdict.getConflicts().stream().map(s -> "`" + s + "`")
                .collect(Collectors.joining(", ")))
            .append("\n\n");
      }
    }
  }

  private static void appendCollectionSection(StringBuilder sb, RetrofitReport report) {
    RetrofitReport.CollectionSurvey survey = report.getCollectionSurvey();
    if (survey == null) {
      return;
    }
    sb.append("## Collection-wrapper survey\n\n");
    sb.append("| Metric | Value |\n|---|---|\n");
    sb.append(row("Total collection fields", survey.getTotalCollectionFields()));
    sb.append(row("Generic wrappers (descend correctly)", survey.getGenericWrapperFields()));
    sb.append(row("Concrete wrappers (need typeParameterOverride)",
        survey.getConcreteWrapperFields()));
    sb.append("\n");
    if (!survey.getConcreteWrapperTypes().isEmpty()) {
      sb.append("Concrete wrapper element types: ")
          .append(survey.getConcreteWrapperTypes().stream().map(s -> "`" + s + "`")
              .collect(Collectors.joining(", ")))
          .append("\n\n");
    }
  }

  private static void appendAnnotationSection(StringBuilder sb, RetrofitReport report) {
    sb.append("## Model annotations\n\n");
    sb.append("| Metric | Value |\n|---|---|\n");
    sb.append(row("@JsonUnwrapped sub-objects", report.getJsonUnwrappedCount()));
    sb.append(row("… prefix-less", report.getPrefixlessJsonUnwrappedCount()));
    sb.append(row("@JsonIgnore / @CCD(ignore) fields", report.getJsonIgnoreCount()));
    sb.append(row("Superclasses walked", report.getSuperclassCount()));
    sb.append("\n");
  }

  private static String memberRef(RetrofitReport.FieldFinding f) {
    if (f.getMemberName() == null) {
      return "";
    }
    return "`" + nullToBlank(f.getModelClass()) + "." + f.getMemberName() + "`";
  }

  private static String row(String key, Object value) {
    return "| " + key + " | " + value + " |\n";
  }

  private static String pctRow(String key, int count, int total) {
    String pct = total == 0 ? "-"
        : String.format(Locale.ROOT, "%.1f%%", 100.0 * count / total);
    return "| " + key + " | " + count + " | " + pct + " |\n";
  }

  private static String nullToBlank(String s) {
    return s == null ? "" : s;
  }

  /**
   * A machine-readable summary map (used by tests and callers that want the numbers without the
   * full serialised report).
   *
   * @param report the report
   * @return an ordered summary map
   */
  public static Map<String, Object> summaryMap(RetrofitReport report) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("caseType", report.getCaseTypeId());
    map.put("mapBased", report.isMapBased());
    map.put("dataBearingFields", report.getDataBearingFields());
    map.put("exactMatches", report.getExactMatches());
    map.put("typeConflicts", report.getTypeConflicts());
    map.put("unmatched", report.getUnmatchedDefinitionFields());
    map.put("resolvedPercent", report.resolvedPercent());
    map.put("exactPercent", report.exactPercent());
    return map;
  }
}
