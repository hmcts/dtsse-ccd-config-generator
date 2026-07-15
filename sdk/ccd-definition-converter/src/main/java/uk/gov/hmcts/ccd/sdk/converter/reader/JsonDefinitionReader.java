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
 */
public class JsonDefinitionReader implements DefinitionReader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    for (File entry : entries) {
      if (entry.isDirectory()) {
        processSheetDirectory(entry.toPath(), options, gaps, rows);
      } else if (entry.getName().endsWith(".json")) {
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
        rows.put(sheet, SheetRow.builder()
            .sheet(sheet)
            .columns(col)
            .overlayTags(overlayTags)
            .source(fragment)
            .build());
      }
    }
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
