package uk.gov.hmcts.ccd.sdk.api;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Typed, prefix-aware show condition builder for CCD event fields.
 *
 * <p>Usage:
 * <pre>
 *   ShowCondition.when(MyDto::getCountry).is("England")
 *   ShowCondition.when(MyDto::getFlag).is(YesOrNo.YES)
 *   ShowCondition.when(MyDto::getCountry).isAnyOf("England", "Wales")
 *   ShowCondition.when(MyDto::getFlag).is(YesOrNo.YES)
 *       .and(ShowCondition.when(MyDto::getView).is("ALL"))
 * </pre>
 */
public class ShowCondition {

  private static final Pattern FIELD_PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

  private final String expression;

  private ShowCondition(String expression) {
    this.expression = expression;
  }

  public static <T> FieldCondition<T> when(TypedPropertyGetter<T, ?> getter) {
    return new FieldCondition<>(getter);
  }

  public ShowCondition and(ShowCondition other) {
    return new ShowCondition(this.expression + " AND " + other.expression);
  }

  public ShowCondition or(ShowCondition other) {
    return new ShowCondition(this.expression + " OR " + other.expression);
  }

  /**
   * Resolves the show condition to a CCD-format string with the given prefix applied
   * to all field references.
   */
  public String resolve(String prefix) {
    Matcher m = FIELD_PLACEHOLDER.matcher(expression);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String fieldName = m.group(1);
      String resolved = (prefix != null && !prefix.isEmpty())
          ? prefix + capitalize(fieldName)
          : fieldName;
      m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @Override
  public String toString() {
    return resolve("");
  }

  public static class FieldCondition<T> {

    private final TypedPropertyGetter<T, ?> getter;
    private String fieldName;

    FieldCondition(TypedPropertyGetter<T, ?> getter) {
      this.getter = getter;
    }

    private String resolveFieldName() {
      if (fieldName == null) {
        fieldName = LambdaUtils.resolvePropertyName(getter);
      }
      return fieldName;
    }

    public ShowCondition is(Object value) {
      return new ShowCondition("{{" + resolveFieldName() + "}}=\"" + formatValue(value) + "\"");
    }

    public ShowCondition isAnyOf(Object... values) {
      String expr = Arrays.stream(values)
          .map(v -> "{{" + resolveFieldName() + "}}=\"" + formatValue(v) + "\"")
          .collect(Collectors.joining(" OR "));
      return new ShowCondition(expr);
    }

    public ShowCondition contains(Object value) {
      return new ShowCondition(
          "{{" + resolveFieldName() + "}} CONTAINS \"" + formatValue(value) + "\"");
    }

    /**
     * Formats a value for CCD show conditions. For enums, respects @JsonProperty
     * and @JsonValue annotations to match CCD's serialization format.
     */
    static String formatValue(Object value) {
      if (value instanceof Enum<?> e) {
        // Check for @JsonProperty on the enum constant field
        try {
          Field enumField = e.getClass().getField(e.name());
          JsonProperty jp = enumField.getAnnotation(JsonProperty.class);
          if (jp != null) {
            return jp.value();
          }
        } catch (NoSuchFieldException ignored) {
          // fall through
        }

        // Check for @JsonValue on a method
        for (Method method : e.getClass().getMethods()) {
          if (method.isAnnotationPresent(JsonValue.class)
              && method.getParameterCount() == 0
              && method.getReturnType() == String.class) {
            try {
              return (String) method.invoke(e);
            } catch (Exception ignored) {
              // fall through
            }
          }
        }

        // Default: use enum name
        return e.name();
      }
      return String.valueOf(value);
    }
  }
}
