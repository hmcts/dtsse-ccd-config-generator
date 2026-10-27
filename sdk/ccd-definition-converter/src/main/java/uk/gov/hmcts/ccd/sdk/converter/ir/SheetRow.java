package uk.gov.hmcts.ccd.sdk.converter.ir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * One row of one definition sheet, as read from a JSON file.
 *
 * <p>The raw column map is preserved verbatim (keys are definition column names, see
 * {@link Columns}) so that unknown columns can be detected and passed through rather than
 * silently dropped. Overlay tags record which filename suffixes (e.g. {@code prod},
 * {@code WA-nonprod}) the source file carried; an empty set means the row is part of the
 * base definition.
 */
@Value
@Builder
public class SheetRow {

  SheetName sheet;

  /** Insertion-ordered raw columns, exactly as read from the JSON. */
  Map<String, Object> columns;

  /** Overlay suffix tags parsed from the source file name; empty for base rows. */
  Set<String> overlayTags;

  /** The file this row was read from, for error reporting. */
  Path source;

  public static class SheetRowBuilder {
    public SheetRowBuilder columns(Map<String, Object> columns) {
      this.columns = new LinkedHashMap<>(columns);
      return this;
    }
  }

  /**
   * The value of a column as a string, treating absent, null and blank values uniformly.
   *
   * @param column the definition column name
   * @return the trimmed string value, or empty if the column is absent, null or blank
   */
  public Optional<String> getString(String column) {
    Object value = columns.get(column);
    if (value == null) {
      return Optional.empty();
    }
    String s = String.valueOf(value).trim();
    return s.isEmpty() ? Optional.empty() : Optional.of(s);
  }

  /**
   * The value of a column read via its canonical name or a legacy alias
   * (e.g. AccessProfile/UserRole).
   *
   * @param column the canonical column name
   * @param alias the legacy alias
   * @return the first present value, checked canonical-first
   */
  public Optional<String> getString(String column, String alias) {
    Optional<String> canonical = getString(column);
    return canonical.isPresent() ? canonical : getString(alias);
  }

  /**
   * The verbatim value of a display-text column (e.g. {@code Label}, {@code HintText}), with
   * surrounding and interior whitespace preserved exactly as authored.
   *
   * <p>Unlike {@link #getString(String)} this does <em>not</em> trim: labels and hint text are
   * user-facing prose that legitimately carry leading/trailing spaces, carriage returns and
   * blank lines (bullet lists, indentation), and the definition store stores them verbatim.
   * Trimming them would silently alter the rendered text, so display-text columns are read raw.
   * A {@code null} or genuinely empty string still yields {@link Optional#empty()} so that an
   * absent label behaves as before.
   *
   * @param column the definition column name
   * @return the untrimmed string value, or empty if the column is absent, null or empty
   */
  public Optional<String> getDisplayText(String column) {
    Object value = columns.get(column);
    if (value == null) {
      return Optional.empty();
    }
    String s = String.valueOf(value);
    return s.isEmpty() ? Optional.empty() : Optional.of(s);
  }

  /**
   * The verbatim value of a column, preserving a present-but-empty or blank value as distinct
   * from the column being wholly absent. Unlike {@link #getDisplayText(String)} — which folds an
   * empty string to "absent" for display-text columns such as {@code Label}, where the
   * distinction has no meaning — some columns (e.g. CaseEvent's {@code Description}) can
   * legitimately be authored as an explicit empty or whitespace-only string, and that authored
   * value must round-trip unchanged rather than being read as null and silently defaulted
   * downstream.
   *
   * @param column the definition column name
   * @return the untrimmed string value, or empty only if the column is absent or JSON null
   */
  public Optional<String> getVerbatimText(String column) {
    if (!columns.containsKey(column)) {
      return Optional.empty();
    }
    Object value = columns.get(column);
    return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
  }

  /**
   * The value of a numeric column; JSON definitions carry numbers both as JSON numbers and
   * as strings. Excel-derived JSON (xlsx2json) represents all numbers as floating point, so
   * an integral value may arrive as {@code "1.0"}; that is accepted and truncated, while a
   * genuinely fractional value is rejected.
   *
   * @param column the definition column name
   * @return the integer value, or empty if absent or blank
   * @throws IllegalArgumentException if present but not an integral number
   */
  public Optional<Integer> getInteger(String column) {
    return getString(column).map(s -> {
      try {
        return new BigDecimal(s).intValueExact();
      } catch (ArithmeticException | NumberFormatException e) {
        throw new IllegalArgumentException(
            "Column " + column + " in " + source + " has non-integer value '" + s + "'", e);
      }
    });
  }

  /**
   * The value of a Y/N-style boolean column. Accepts the spellings the definition store
   * accepts: Y/Yes/True and N/No/False, case-insensitively.
   *
   * @param column the definition column name
   * @return true/false, or empty if the column is absent or blank
   * @throws IllegalArgumentException if present but not a recognised boolean spelling
   */
  public Optional<Boolean> getYesNo(String column) {
    return getString(column).flatMap(s -> {
      // An unresolved ${CCD_DEF_*} environment placeholder is not a static boolean the
      // converter can act on (its value is decided per-environment at import time). Treat it
      // as absent here; the raw placeholder is preserved through the round-trip via the
      // column's passthrough/verbatim handling rather than crashing the conversion.
      if (s.startsWith("${") && s.endsWith("}")) {
        return Optional.empty();
      }
      return Optional.of(switch (s.toLowerCase()) {
        case "y", "yes", "true", "t" -> true;
        case "n", "no", "false", "f" -> false;
        default -> throw new IllegalArgumentException(
            "Column " + column + " in " + source + " has non-boolean value '" + s + "'");
      });
    });
  }

  public boolean isBase() {
    return overlayTags.isEmpty();
  }
}
