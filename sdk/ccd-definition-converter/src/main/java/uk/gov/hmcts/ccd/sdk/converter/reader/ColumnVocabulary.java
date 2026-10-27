package uk.gov.hmcts.ccd.sdk.converter.reader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;

/**
 * Which keys on a definition JSON row name a real CCD column, and which are inert.
 *
 * <p>A key that names no column never reaches the imported definition. {@code json2xlsx}
 * (ccd-definition-processor, {@code lib/ccd-spreadsheet-utils.js}) builds each spreadsheet row as
 * {@code headers.map(key => record[key])} against the sheet's header row in
 * {@code data/ccd-template.xlsx}, so a key matching no header contributes no cell — the value is
 * gone before the xlsx exists, let alone before the definition store's parsers run. The template's
 * headers are the importer's own {@code ColumnName} vocabulary, which {@link Columns} transcribes,
 * matched by {@code equalsColumnNameOrAlias}, i.e. {@code equalsIgnoreCase}.
 *
 * <p>Real definitions carry such keys in two shapes: inline documentation the authors know CCD
 * ignores ({@code Comment}, civil's {@code _Comment}/{@code _Category}/{@code _Definition}), and
 * typos they do not — sscs's {@code ",ShowSummaryChangeOption"} (a stray comma swallowed into the
 * key), fpl's {@code FieldShownCondition} and {@code "FieldShowCondition:"}, civil's
 * {@code PageShowShowCondition} and {@code retainHiddenValues}.
 *
 * <p>The converter must drop both, for the same reason: honouring either would generate a definition
 * the team's own pipeline does not produce, and the round-trip's expected side would carry columns
 * the real xlsx never had. The only difference is what the gap report says — a documentation key is
 * noise, whereas a key one edit from a real column is a finding worth handing back to the team,
 * since the definition plainly meant it and has silently not had it. This class draws that line;
 * the drop itself is {@link JsonDefinitionReader}'s.
 */
final class ColumnVocabulary {

  /**
   * Every CCD column name, lower-cased, read from {@link Columns}. Case-insensitive because
   * {@code equalsColumnNameOrAlias} is: sscs authors {@code SearchPartyDoB} where the template's
   * header reads {@code SearchPartyDOB}, and both import.
   */
  private static final Set<String> KNOWN = knownColumns();

  private ColumnVocabulary() {
  }

  /**
   * Whether a row key names a real CCD column.
   *
   * @param key the JSON key as authored
   * @return true when the definition store could read a value under it
   */
  static boolean isKnown(String key) {
    return key != null && KNOWN.contains(key.toLowerCase(Locale.ROOT));
  }

  /**
   * The keys on a row that name no CCD column, in row order.
   *
   * @param row a parsed definition row
   * @return the inert keys; empty when every key is a column
   */
  static List<String> unknownKeys(Map<String, Object> row) {
    List<String> unknown = new ArrayList<>();
    for (String key : row.keySet()) {
      if (!isKnown(key)) {
        unknown.add(key);
      }
    }
    return unknown;
  }

  /**
   * Whether an unknown key is the authors' own inline documentation rather than a mistake: a
   * {@code Comment}/{@code Comments} in any casing, or a name fenced with an underscore, which is
   * civil's convention for annotating a row ({@code _Comment}, {@code _Category}, {@code comment_}).
   * Such keys are dropped without a gap entry — reporting them would bury the real findings under
   * hundreds of lines of the authors' own notes.
   *
   * @param key an unknown key
   * @return true when the key carries documentation, not a miswritten column name
   */
  static boolean isDocumentation(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.startsWith("_") || lower.endsWith("_")
        || lower.equals("comment") || lower.equals("comments");
  }

  /**
   * The column an unknown key would have named had its punctuation been written correctly — sscs's
   * {@code ",ShowSummaryChangeOption"} and fpl's {@code "FieldShowCondition:"} both differ from a
   * real column only by a stray character outside the name.
   *
   * <p>Deliberately punctuation-only, not a general edit-distance search: a nearest-name guess over
   * 119 columns produces confident-sounding matches for keys the author never meant (fpl's
   * {@code FieldShownCondition} and civil's {@code PageShowShowCondition} are misspellings of a
   * column name itself, which no edit budget distinguishes from a genuinely unrelated key). Those
   * are still reported — just as an unrecognised column, without a claim about intent.
   *
   * @param key an unknown key
   * @return the intended column, or null when stripping punctuation does not yield one
   */
  static String punctuationTypoOf(String key) {
    String stripped = key.replaceAll("[^A-Za-z0-9()]", "");
    if (stripped.isEmpty() || stripped.equalsIgnoreCase(key) || !isKnown(stripped)) {
      return null;
    }
    return canonicalSpellingOf(stripped);
  }

  /**
   * The spelling a column is declared under in {@link Columns}, for reporting a
   * {@link #punctuationTypoOf} match in the definition's own vocabulary.
   */
  private static String canonicalSpellingOf(String name) {
    for (String declared : declaredNames()) {
      if (declared.equalsIgnoreCase(name)) {
        return declared;
      }
    }
    throw new IllegalStateException("not a column: " + name);
  }

  private static Set<String> knownColumns() {
    Set<String> lower = new HashSet<>();
    for (String name : declaredNames()) {
      lower.add(name.toLowerCase(Locale.ROOT));
    }
    return Collections.unmodifiableSet(lower);
  }

  /**
   * Every {@code String} constant on {@link Columns}, in declaration order. Read reflectively rather
   * than re-listed here so the vocabulary cannot drift from the constants the rest of the converter
   * reads rows by: teaching the converter a new column is the single edit that teaches this too.
   */
  private static List<String> declaredNames() {
    List<String> names = new ArrayList<>();
    for (Field field : Columns.class.getDeclaredFields()) {
      if (field.getType() != String.class) {
        continue;
      }
      try {
        names.add((String) field.get(null));
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException("Columns constant not readable: " + field.getName(), ex);
      }
    }
    return names;
  }
}
