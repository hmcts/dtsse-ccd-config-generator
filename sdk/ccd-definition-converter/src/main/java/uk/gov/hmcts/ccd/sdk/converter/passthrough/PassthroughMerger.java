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
