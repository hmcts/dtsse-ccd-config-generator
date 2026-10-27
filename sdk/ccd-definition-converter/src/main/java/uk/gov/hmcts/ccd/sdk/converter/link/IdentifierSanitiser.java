package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.Set;

/**
 * Turns CCD definition ID strings into legal Java identifiers for generated code.
 *
 * <p>CCD IDs allow characters (leading digits, hyphens, dots) that are not legal in Java
 * source. This helper produces member names, enum constant names and constant-case forms,
 * and reports whether the input was already a legal identifier so the linker can raise an
 * {@code IDENTIFIER_SANITISED} gap only when a rewrite actually happened.
 */
final class IdentifierSanitiser {

  private static final Set<String> JAVA_KEYWORDS = Set.of(
      "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
      "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
      "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
      "interface", "long", "native", "new", "package", "private", "protected", "public",
      "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
      "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
      "null", "var", "record", "yield");

  private IdentifierSanitiser() {
  }

  /**
   * Whether the value is already a legal, non-reserved Java identifier and needs no rewrite.
   *
   * @param value the candidate identifier
   * @return true when the value can be used verbatim as a Java identifier
   */
  static boolean isLegalIdentifier(String value) {
    if (value == null || value.isEmpty() || JAVA_KEYWORDS.contains(value)) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(value.charAt(0))) {
      return false;
    }
    for (int i = 1; i < value.length(); i++) {
      if (!Character.isJavaIdentifierPart(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * A legal Java member name derived from the value, preserving it verbatim when already
   * legal and otherwise replacing illegal characters and prefixing a leading digit.
   *
   * @param value the CCD ID
   * @return a legal Java member name
   */
  static String toMemberName(String value) {
    if (isLegalIdentifier(value)) {
      return value;
    }
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      builder.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
    }
    String result = builder.toString();
    if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
      result = "_" + result;
    }
    if (JAVA_KEYWORDS.contains(result)) {
      result = result + "_";
    }
    return result;
  }

  /**
   * A CONSTANT_CASE Java identifier for enum constants, splitting camelCase and
   * non-alphanumeric runs into underscore-separated upper-case segments.
   *
   * @param value the CCD code
   * @return a legal upper-case Java constant name
   */
  static String toConstantName(String value) {
    StringBuilder builder = new StringBuilder();
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char ch = chars[i];
      if (Character.isLetterOrDigit(ch)) {
        boolean boundary = i > 0
            && Character.isUpperCase(ch)
            && (Character.isLowerCase(chars[i - 1]) || Character.isDigit(chars[i - 1]));
        if (boundary && builder.length() > 0 && builder.charAt(builder.length() - 1) != '_') {
          builder.append('_');
        }
        builder.append(Character.toUpperCase(ch));
      } else if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '_') {
        builder.append('_');
      }
    }
    while (builder.length() > 0 && builder.charAt(builder.length() - 1) == '_') {
      builder.deleteCharAt(builder.length() - 1);
    }
    String result = builder.toString();
    if (result.isEmpty() || Character.isDigit(result.charAt(0))) {
      result = "_" + result;
    }
    if (JAVA_KEYWORDS.contains(result.toLowerCase())) {
      result = result + "_";
    }
    return result;
  }
}
