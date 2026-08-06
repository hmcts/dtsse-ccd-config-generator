package uk.gov.hmcts.ccd.sdk.converter.passthrough;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils;

/**
 * Merges passthrough JSON content from a passthrough directory into a generated definition
 * directory, using the {@code manifest.json} to determine which sheets to merge and under
 * what environment conditions.
 *
 * <p>Base sheets (those without an overlay suffix) are always merged. Suffix-tagged sheets
 * are merged only when their environment predicate — checked as a system property first, then
 * an environment variable — matches the expected value (optionally negated).
 *
 * <p>Usage: {@code PassthroughMerger <passthroughDir> <generatedDefinitionDir>}
 */
public final class PassthroughMerger {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
      new TypeReference<>() {};

  private PassthroughMerger() {
  }

  /**
   * CLI entry point.
   *
   * @param args {@code <passthroughDir> <generatedDefinitionDir>}
   */
  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Usage: PassthroughMerger <passthroughDir> <generatedDefinitionDir>");
      System.exit(1);
    }
    merge(Paths.get(args[0]), Paths.get(args[1]));
  }

  /**
   * Merges passthrough content from {@code passthroughDir} into {@code generatedDir}.
   *
   * <p>Reads {@code passthroughDir/manifest.json}; merges base sheets unconditionally;
   * merges suffix-tagged sheets only when their environment predicate is active.
   *
   * @param passthroughDir the directory containing the manifest and passthrough JSON files
   * @param generatedDir the generated definition directory to merge into
   */
  public static void merge(Path passthroughDir, Path generatedDir) {
    Path manifestPath = passthroughDir.resolve("manifest.json");
    List<Map<String, Object>> entries;
    try {
      entries = MAPPER.readValue(manifestPath.toFile(), LIST_MAP_TYPE);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed reading passthrough manifest: " + manifestPath, ex);
    }

    for (Map<String, Object> entry : entries) {
      String relativePath = (String) entry.get("relativePath");
      String overlaySuffix = (String) entry.get("overlaySuffix");
      String envVar = (String) entry.get("envVar");

      if (!isActive(overlaySuffix, envVar, entry)) {
        continue;
      }

      String prefix = overlaySuffix == null ? "base" : overlaySuffix;
      Path sourceFile = passthroughDir.resolve(prefix).resolve(relativePath);

      List<Map<String, Object>> rows;
      try {
        rows = MAPPER.readValue(sourceFile.toFile(), LIST_MAP_TYPE);
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed reading passthrough file: " + sourceFile, ex);
      }

      String[] primaryKeys = toPrimaryKeys(entry);
      Path targetFile = generatedDir.resolve(relativePath);
      if (isColumnsOnly(entry)) {
        rows = retainMatchedRows(rows, targetFile, primaryKeys);
        if (rows.isEmpty()) {
          continue;
        }
      }
      try {
        // The generator only creates a sheet's directory when it emits at least one row for it;
        // a passthrough-only sheet (e.g. an EventToComplexTypes file for an event the generator
        // produced no complex overrides for) has no directory yet, so create the parents before
        // merging.
        Path parent = targetFile.getParent();
        if (parent != null) {
          java.nio.file.Files.createDirectories(parent);
        }
        // Columns in overwriteColumns replace the generator's value on a matched row (for SDK
        // forced-default columns the input specifies differently, e.g. a State's Description);
        // every other column stays strictly additive — filled in only when the generator omitted
        // it. An empty set is exactly AddMissing.
        JsonUtils.mergeInto(targetFile, rows,
            new JsonUtils.OverwriteSpecific(toOverwriteColumns(entry)), primaryKeys);
      } catch (Exception ex) {
        throw new RuntimeException("Failed merging " + sourceFile + " into " + targetFile, ex);
      }
    }
  }

  /**
   * Drops the passthrough rows no generated row matches, for a columns-only sheet.
   *
   * <p>{@code JsonUtils.mergeInto} appends an unmatched row, which is right for a whole-sheet or
   * row-level passthrough but wrong for a column graft: the graft carries only the merge key plus
   * the grafted columns, so appending it writes a row missing every other column the sheet requires
   * (see {@link uk.gov.hmcts.ccd.sdk.converter.model.PassthroughSheet#isColumnsOnly()}). The key
   * comparison mirrors {@code mergeInto}'s exactly — string-compared, and a key absent on both sides
   * counts as agreement — so a row kept here is a row that will match there.
   *
   * @return the subset of {@code rows} that has a generated row to graft onto
   */
  private static List<Map<String, Object>> retainMatchedRows(
      List<Map<String, Object>> rows, Path targetFile, String[] primaryKeys) {
    List<Map<String, Object>> existing;
    try {
      existing = java.nio.file.Files.isRegularFile(targetFile)
          ? MAPPER.readValue(targetFile.toFile(), LIST_MAP_TYPE)
          : List.of();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed reading generated file: " + targetFile, ex);
    }
    List<Map<String, Object>> kept = new java.util.ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      if (existing.stream().anyMatch(candidate -> matchesOn(candidate, row, primaryKeys))) {
        kept.add(row);
      }
    }
    return kept;
  }

  /**
   * Whether two rows agree on every primary key, using {@code JsonUtils.mergeInto}'s rules.
   */
  private static boolean matchesOn(
      Map<String, Object> existing, Map<String, Object> incoming, String[] primaryKeys) {
    for (String key : primaryKeys) {
      boolean inExisting = existing.containsKey(key);
      boolean inIncoming = incoming.containsKey(key);
      if (!inExisting || !inIncoming) {
        if (inExisting != inIncoming) {
          return false;
        }
        continue;
      }
      if (!existing.get(key).equals(incoming.get(key).toString())) {
        return false;
      }
    }
    return true;
  }

  private static boolean isColumnsOnly(Map<String, Object> entry) {
    Object raw = entry.get("columnsOnly");
    return raw instanceof Boolean && (Boolean) raw;
  }

  private static boolean isActive(String overlaySuffix, String envVar,
      Map<String, Object> entry) {
    if (overlaySuffix == null || envVar == null) {
      return true;
    }
    String expectedValue = (String) entry.get("value");
    // A manifest entry missing its expected value can never match; guard here rather than
    // constructing an OverlayCondition that would NPE on a null expectedValue.
    if (expectedValue == null) {
      return false;
    }
    Object negatedObj = entry.get("negated");
    boolean negated = negatedObj instanceof Boolean && (Boolean) negatedObj;
    return new OverlayCondition(envVar, expectedValue, negated).isActive();
  }

  @SuppressWarnings("unchecked")
  private static String[] toPrimaryKeys(Map<String, Object> entry) {
    Object raw = entry.get("primaryKeys");
    if (raw instanceof List) {
      List<String> keys = (List<String>) raw;
      return keys.toArray(new String[0]);
    }
    return new String[0];
  }

  @SuppressWarnings("unchecked")
  private static java.util.Set<String> toOverwriteColumns(Map<String, Object> entry) {
    Object raw = entry.get("overwriteColumns");
    if (raw instanceof List) {
      return new java.util.LinkedHashSet<>((List<String>) raw);
    }
    return java.util.Set.of();
  }
}
