package uk.gov.hmcts.ccd.sdk.converter.reader;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.DefinitionReader;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Reads JSON CCD definition files into the intermediate representation.
 *
 * <p>Supports three on-disk layouts seen across HMCTS service teams:
 * <ul>
 *   <li>Flat sheet files: {@code CaseEvent.json}</li>
 *   <li>Overlay flat files: {@code CaseEvent-prod.json} (suffix must be configured)</li>
 *   <li>Fragment directories: {@code CaseEvent/} containing recursively nested {@code *.json}
 *       files, aggregated in deterministic (path-sorted) order; a fragment whose base name
 *       ends with {@code -<configuredSuffix>} carries that overlay tag, while any other
 *       name — including kebab-case names like {@code manage-orders.json} — yields base
 *       rows.</li>
 * </ul>
 *
 * <p>Top-level {@code .json} files that are not a recognised CCD sheet (e.g.
 * {@code ChangeHistory.json}) are recorded as {@link GapCategory#UNSUPPORTED_SHEET} gaps and
 * skipped. Non-JSON files and non-sheet directories (e.g. {@code env/}) are silently ignored.
 *
 * <p>Row keys that name no CCD column are dropped as the row is read, because {@code json2xlsx}
 * writes no cell for them and the definition store therefore never sees their values — see
 * {@link #dropInertColumns} and {@link ColumnVocabulary}.
 *
 * <p>A flat {@code X.json} file that collides with a sibling {@code X/} fragment directory is
 * skipped entirely, mirroring {@code json2xlsx}: it groups sources by literal base name and, per
 * group, writes the whole table to the sheet's fixed anchor cell, so the directory's later write
 * (its custom sort always orders the flat file first) fully overwrites the flat file's rows —
 * none of them reach the imported definition.
 */
public class JsonDefinitionReader implements DefinitionReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The environment placeholder a shared definition fragment addresses its rows to instead of naming
   * a case type it does not belong to. Substituted at read time — see
   * {@link #resolveCaseTypePlaceholder}.
   */
  private static final String CASE_TYPE_ID_PLACEHOLDER = "${CCD_DEF_CASE_TYPE_ID}";

  @Override
  public DefinitionIr read(ConversionOptions options, GapCollector gaps) {
    ListMultimap<SheetName, SheetRow> rows = LinkedListMultimap.create();
    for (Path inputDir : options.getInputs()) {
      processInputDirectory(inputDir, options, gaps, rows);
    }
    return new DefinitionIr(rows);
  }

  private void processInputDirectory(
      Path inputDir,
      ConversionOptions options,
      GapCollector gaps,
      ListMultimap<SheetName, SheetRow> rows) {
    File[] entries = inputDir.toFile().listFiles();
    if (entries == null) {
      return;
    }
    Arrays.sort(entries, Comparator.comparing(File::getName));
    Set<String> directoryBaseNames = new HashSet<>();
    for (File entry : entries) {
      if (entry.isDirectory()) {
        directoryBaseNames.add(entry.getName());
      }
    }
    for (File entry : entries) {
      if (entry.isDirectory()) {
        processSheetDirectory(entry.toPath(), options, gaps, rows);
      } else if (entry.getName().endsWith(".json")) {
        // json2xlsx (ccd-definition-processor) groups source files by their literal file/directory
        // base name and, for each group, calls SpreadsheetBuilder.updateSheetDataJson(sheetName, ...)
        // — which writes the whole table to the sheet's fixed A4 anchor. Its custom path sort
        // (toRelativePaths in file-utils.js) guarantees a flat "X.json" file always sorts, and is
        // therefore always processed, before any path nested under a same-named "X/" directory; the
        // directory's later write to the same anchor cell then fully overwrites the flat file's rows.
        // A flat file whose base name collides with a sibling fragment directory never survives into
        // the real xlsx, so its rows must not be aggregated here either — otherwise the "expected"
        // side of a round-trip comparison carries phantom rows the real import discards.
        if (directoryBaseNames.contains(stripJsonExtension(entry.getName()))) {
          continue;
        }
        processFlatFile(entry.toPath(), options, gaps, rows);
      }
    }
  }

  private void processFlatFile(
      Path file,
      ConversionOptions options,
      GapCollector gaps,
      ListMultimap<SheetName, SheetRow> rows) {
    String fileName = file.getFileName().toString();
    String baseName = stripJsonExtension(fileName);
    Optional<SheetName> sheet = resolveSheetAndSuffix(baseName, options, file, gaps);
    if (sheet.isEmpty()) {
      return;
    }
    Set<String> overlayTags = extractOverlayTags(baseName, options);
    List<Map<String, Object>> parsed = parseJsonFile(file);
    for (Map<String, Object> col : parsed) {
      resolveCaseTypePlaceholder(col, options.getCaseTypeId());
      dropInertColumns(sheet.get(), col, file, gaps);
      rows.put(sheet.get(), SheetRow.builder()
          .sheet(sheet.get())
          .columns(col)
          .overlayTags(overlayTags)
          .source(file)
          .build());
    }
  }

  private void processSheetDirectory(
      Path dir,
      ConversionOptions options,
      GapCollector gaps,
      ListMultimap<SheetName, SheetRow> rows) {
    String dirName = dir.getFileName().toString();
    Optional<SheetName> sheetOpt = SheetName.forFileBaseName(dirName);
    if (sheetOpt.isEmpty()) {
      return;
    }
    SheetName sheet = sheetOpt.get();
    List<Path> fragmentFiles = collectFragmentFiles(dir);
    for (Path fragment : fragmentFiles) {
      String fragmentName = stripJsonExtension(fragment.getFileName().toString());
      Set<String> overlayTags = extractOverlayTags(fragmentName, options);
      List<Map<String, Object>> parsed = parseJsonFile(fragment);
      for (Map<String, Object> col : parsed) {
        resolveCaseTypePlaceholder(col, options.getCaseTypeId());
        dropInertColumns(sheet, col, fragment, gaps);
        rows.put(sheet, SheetRow.builder()
            .sheet(sheet)
            .columns(col)
            .overlayTags(overlayTags)
            .source(fragment)
            .build());
      }
    }
  }

  /**
   * Removes the row's keys that name no CCD column, so the IR carries only what the real import
   * would see.
   *
   * <p>{@code json2xlsx} builds each spreadsheet row as {@code headers.map(key => record[key])}
   * against {@code ccd-template.xlsx}'s header row, so a key naming no column contributes no cell —
   * its value never reaches the xlsx, let alone the definition store. Honouring one here would make
   * the converter emit a definition the team's own pipeline does not produce; leaving it on the row
   * would put it on the round-trip's expected side, which the real xlsx never had. sscs's
   * {@code CaseEventToFields} row for {@code adjournCase/adjournCaseTime} carries both
   * {@code ",ShowSummaryChangeOption"} and a correctly-spelled {@code ShowSummaryChangeOption} — the
   * typo'd twin has been doing nothing since it was written, and after this so has the converter's
   * reading of it.
   *
   * <p>Reported as {@link GapCategory#UNSUPPORTED_COLUMN} / {@link GapAction#ADVISORY}: advisory
   * because there is nothing to reproduce or pass through — the value is unreachable by
   * construction, so the finding is about the definition, not about a converter limitation. The
   * authors' own inline documentation is dropped silently ({@link ColumnVocabulary#isDocumentation}),
   * since several hundred {@code _Comment} entries would bury the handful of real findings.
   *
   * @param sheet the sheet the row belongs to
   * @param row the parsed row, mutated in place
   * @param source the file the row came from, for the gap detail
   * @param gaps the collector to record droppings on
   */

  private void dropInertColumns(
      SheetName sheet, Map<String, Object> row, Path source, GapCollector gaps) {
    for (String key : ColumnVocabulary.unknownKeys(row)) {
      Object value = row.remove(key);
      if (ColumnVocabulary.isDocumentation(key)) {
        continue;
      }
      String intended = ColumnVocabulary.punctuationTypoOf(key);
      gaps.add(GapEntry.builder()
          .sheet(sheet.getName())
          .rowKey(rowKeyOf(row))
          .column(key)
          .value(value == null ? null : String.valueOf(value))
          .category(GapCategory.UNSUPPORTED_COLUMN)
          .action(GapAction.ADVISORY)
          .detail(intended == null
              ? "Key '" + key + "' in " + source.getFileName() + " names no CCD column, so"
                  + " json2xlsx writes no cell for it and the definition store never sees the value."
                  + " Dropped; it has no effect on the imported definition either."
              : "Key '" + key + "' in " + source.getFileName() + " is '" + intended + "' with stray"
                  + " punctuation, so json2xlsx matches no header and writes no cell: this row has"
                  + " never had the " + intended + " the definition appears to set. Dropped to match"
                  + " the real import; fix the key in the definition to actually apply it.")
          .build());
    }
  }

  /**
   * Resolves {@code ${CCD_DEF_CASE_TYPE_ID}} to the case type being converted, wherever it appears
   * as a whole cell value.
   *
   * <p>A definition split across several case types keeps their shared rows in a tree that names no
   * case type of its own, because the same row has to become a row of whichever case type is being
   * built. finrem does this: {@code definitions/common/json} is copied into
   * {@code definitions/contested/json} or {@code definitions/consented/json} by
   * {@code yarn copy-common-components-*} and the build then sets the variable —
   * {@code CCD_DEF_CASE_TYPE_ID=FinancialRemedyContested yarn json2xlsx -D definitions/contested/json}
   * — so its 33 {@code CaseTypeID} cells resolve differently per build. Read literally, the
   * placeholder matches no case type at all and {@link DefinitionIr#rowsForCaseType} drops every
   * shared row: 13 {@code CaseField}s including both {@code OrganisationPolicy} fields, and the 17
   * {@code AuthorisationCaseField} rows granting them.
   *
   * <p>Resolved here, once, rather than at each of the reads that compare a row's case type: the
   * value is settled for the whole conversion by {@code --case-type}, and the expected side of
   * {@code retrofit-verify} is read through this same reader, so both sides substitute alike or
   * neither does.
   *
   * <p>Only this one variable is substituted. Every other {@code ${CCD_DEF_*}} is genuinely deferred
   * to deployment — a callback host, or sscs's {@code ${CCD_DEF_PUBLISH}}, which {@link SheetRow}
   * deliberately reads as <em>absent</em> so the placeholder round-trips instead of collapsing to a
   * literal {@code N} the definition never stated.
   *
   * @param row the parsed row, mutated in place
   * @param caseTypeId the case type being converted, or null when the conversion names none
   */
  private void resolveCaseTypePlaceholder(Map<String, Object> row, String caseTypeId) {
    if (caseTypeId == null || caseTypeId.isBlank()) {
      return;
    }
    for (Map.Entry<String, Object> cell : row.entrySet()) {
      if (CASE_TYPE_ID_PLACEHOLDER.equals(cell.getValue())) {
        cell.setValue(caseTypeId);
      }
    }
  }

  /**
   * A best-effort human-readable key for a row, for gap reporting only: the row's own {@code ID}, or
   * the event/field pair that identifies the per-sheet row shapes carrying most inert keys.
   */
  private String rowKeyOf(Map<String, Object> row) {
    Object id = row.get("ID");
    if (id != null) {
      return String.valueOf(id);
    }
    Object event = row.get("CaseEventID");
    Object field = row.get("CaseFieldID");
    if (event != null && field != null) {
      return event + "|" + field;
    }
    return String.valueOf(field != null ? field : row.get("TabID"));
  }

  private List<Path> collectFragmentFiles(Path dir) {
    List<Path> result = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .forEach(result::add);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to walk fragment directory " + dir, ex);
    }
    result.sort(Comparator.comparing(p -> dir.relativize(p).toString()));
    return result;
  }

  private Optional<SheetName> resolveSheetAndSuffix(
      String baseName,
      ConversionOptions options,
      Path file,
      GapCollector gaps) {
    Optional<SheetName> direct = SheetName.forFileBaseName(baseName);
    if (direct.isPresent()) {
      return direct;
    }
    int dashIdx = baseName.indexOf('-');
    if (dashIdx > 0) {
      String sheetBase = baseName.substring(0, dashIdx);
      String suffix = baseName.substring(dashIdx + 1);
      Optional<SheetName> sheetOpt = SheetName.forFileBaseName(sheetBase);
      if (sheetOpt.isPresent()) {
        if (!options.getOverlaySuffixes().containsKey(suffix)) {
          throw new IllegalArgumentException(
              "Unknown overlay suffix '" + suffix + "' in file " + file
                  + ". Configured suffixes: " + options.getOverlaySuffixes().keySet());
        }
        return sheetOpt;
      }
    }
    gaps.add(GapEntry.builder()
        .sheet(baseName)
        .category(GapCategory.UNSUPPORTED_SHEET)
        .action(GapAction.OMITTED_FAIL)
        .detail("File " + file.getFileName() + " is not a recognised CCD definition-store sheet.")
        .build());
    return Optional.empty();
  }

  /**
   * Overlay tags for a file base name: the longest configured suffix {@code s} such that the
   * base name ends with {@code -s}, or the empty set when no configured suffix matches.
   *
   * <p>Real fragment file names are frequently kebab-case ({@code manage-orders.json},
   * {@code gatekeeping-order.json}); a dash alone must never be treated as an overlay marker.
   *
   * @param baseName the file base name without the {@code .json} extension
   * @param options the conversion configuration holding the configured suffixes
   * @return a singleton set with the matched suffix, or an empty set for base rows
   */
  private Set<String> extractOverlayTags(String baseName, ConversionOptions options) {
    String best = null;
    for (String suffix : options.getOverlaySuffixes().keySet()) {
      if (baseName.endsWith("-" + suffix) && (best == null || suffix.length() > best.length())) {
        best = suffix;
      }
    }
    return best == null ? Collections.emptySet() : Set.of(best);
  }

  private String stripJsonExtension(String fileName) {
    if (fileName.endsWith(".json")) {
      return fileName.substring(0, fileName.length() - 5);
    }
    return fileName;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> parseJsonFile(Path file) {
    try {
      Object parsed = MAPPER.readValue(file.toFile(), Object.class);
      if (parsed instanceof List) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : (List<?>) parsed) {
          if (item instanceof Map) {
            list.add(new LinkedHashMap<>((Map<String, Object>) item));
          } else {
            throw new IllegalArgumentException(
                "Unexpected non-object element in array in " + file);
          }
        }
        return list;
      } else if (parsed instanceof Map) {
        return List.of(new LinkedHashMap<>((Map<String, Object>) parsed));
      } else {
        throw new IllegalArgumentException(
            "JSON file must contain an array or object but found " + parsed.getClass() + " in "
                + file);
      }
    } catch (JsonParseException ex) {
      throw new IllegalArgumentException(
          "Malformed JSON in file " + file + ": " + ex.getOriginalMessage(), ex);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read JSON file " + file, ex);
    }
  }
}
