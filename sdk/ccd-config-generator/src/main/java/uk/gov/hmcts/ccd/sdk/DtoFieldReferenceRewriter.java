package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.google.common.base.Strings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DtoFieldReferenceRewriter {

  private static final Pattern LABEL_INTERPOLATION = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]*)}");

  private DtoFieldReferenceRewriter() {
  }

  public static String rewriteShowCondition(String expression, Class<?> dtoClass, String prefixStem) {
    if (Strings.isNullOrEmpty(expression) || dtoClass == null || Strings.isNullOrEmpty(prefixStem)) {
      return expression;
    }

    Map<String, String> fieldIds = getFieldIds(dtoClass, prefixStem);
    if (fieldIds.isEmpty()) {
      return expression;
    }

    String identifiers = fieldIds.keySet().stream()
        .map(Pattern::quote)
        .reduce((left, right) -> left + "|" + right)
        .orElse("");

    Pattern fieldReference = Pattern.compile(
        "(?<![A-Za-z0-9_])(" + identifiers + ")(?=\\s*(?:=|!=|>|<|>=|<=|CONTAINS\\b|IN\\b))");

    Matcher matcher = fieldReference.matcher(expression);
    StringBuffer rewritten = new StringBuffer();
    while (matcher.find()) {
      String replacement = fieldIds.get(matcher.group(1));
      matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  public static String rewriteLabel(String value, Class<?> dtoClass, String prefixStem) {
    if (Strings.isNullOrEmpty(value) || dtoClass == null || Strings.isNullOrEmpty(prefixStem)) {
      return value;
    }

    Map<String, String> fieldIds = getFieldIds(dtoClass, prefixStem);
    if (fieldIds.isEmpty()) {
      return value;
    }

    Matcher matcher = LABEL_INTERPOLATION.matcher(value);
    StringBuffer rewritten = new StringBuffer();
    while (matcher.find()) {
      String fieldName = matcher.group(1);
      String replacement = fieldIds.get(fieldName);
      matcher.appendReplacement(
          rewritten,
          Matcher.quoteReplacement(replacement == null ? matcher.group(0) : "${" + replacement + "}"));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  private static Map<String, String> getFieldIds(Class<?> dtoClass, String prefixStem) {
    Map<String, String> fieldIds = new LinkedHashMap<>();
    FieldUtils.getCaseFields(dtoClass).stream()
        .sorted((left, right) -> Integer.compare(right.getName().length(), left.getName().length()))
        .forEach(field -> fieldIds.put(field.getName(), prefixStem.concat(capitalize(field.getName()))));
    return fieldIds;
  }
}
