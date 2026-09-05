package uk.gov.hmcts.ccd.sdk.converter.reader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithful Java port of the Node {@code ccd-definition-processor} {@code substitutor.js}.
 *
 * <p>The Node tool replaces every occurrence of {@code ${VAR}} in string values with the
 * matching environment variable value. Only variables whose names start with the prefix
 * {@code CCD_DEF} are substituted. Variables not present in the supplied environment are
 * left as-is (the JS simply skips the replace for absent keys — it iterates over the
 * environment rather than over the placeholders, so missing variables produce no error and
 * the placeholder remains verbatim).
 *
 * <p>Values that are not strings (numbers, booleans, {@code null}) are passed through
 * unchanged; the JS tool serialises everything to a string before calling
 * {@code String.replace}, but the definition JSON stores numbers and booleans as their
 * native types and the replacement can only affect string fields.
 */
public final class Substitutor {

  /** The environment variable prefix recognised by the Node tool. */
  static final String ENV_PREFIX = "CCD_DEF";

  private Substitutor() {
  }

  /**
   * Injects environment variables into every string value in the supplied row list.
   *
   * <p>Only entries in {@code env} whose keys start with {@code CCD_DEF} are substituted.
   * Each occurrence of {@code ${KEY}} in a string value is replaced with the variable's value.
   * Non-string values are left unchanged. The method returns a new list; the originals are
   * not mutated.
   *
   * @param env the environment to read substitution values from (e.g. a copy of
   *     {@code System.getenv()})
   * @param rows the rows whose string column values will have placeholders replaced
   * @return a new list of rows with placeholders substituted
   */
  public static List<Map<String, Object>> injectEnvironmentVariables(
      Map<String, String> env,
      List<Map<String, Object>> rows) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      result.add(substituteRow(env, row));
    }
    return result;
  }

  /**
   * Substitutes {@code CCD_DEF*} placeholders in a single string value.
   *
   * @param env the environment to read substitution values from
   * @param value the string value that may contain {@code ${VAR}} placeholders
   * @return the string with all known placeholders replaced
   */
  public static String substituteValue(Map<String, String> env, String value) {
    String result = value;
    for (Map.Entry<String, String> entry : env.entrySet()) {
      if (entry.getKey().startsWith(ENV_PREFIX)) {
        String placeholder = Pattern.quote("${" + entry.getKey() + "}");
        result = result.replaceAll(placeholder, Matcher.quoteReplacement(entry.getValue()));
      }
    }
    return result;
  }

  private static Map<String, Object> substituteRow(Map<String, String> env, Map<String, Object> row) {
    Map<String, Object> substituted = new LinkedHashMap<>(row.size());
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String) {
        value = substituteValue(env, (String) value);
      }
      substituted.put(entry.getKey(), value);
    }
    return substituted;
  }
}
