package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import java.util.Locale;
import java.util.Optional;

/**
 * The Jackson class-level {@code @JsonNaming} property-naming strategies the retrofit resolver can
 * evaluate statically, so a member's real serialised name is known without running Jackson.
 *
 * <p>This exists because the SDK is naming-strategy BLIND: {@code PropertyUtils.getPropertyName}
 * resolves a CCD id from {@code @JsonGetter}/{@code @JsonProperty} on the read method or field, else
 * the bean/declared field name — it never consults {@code @JsonNaming}. Civil's
 * {@code @JsonNaming(UpperCamelCaseStrategy) Address} therefore serialises as {@code AddressLine1}
 * (which is what its CCD definition rows say) while the SDK would generate {@code addressLine1}. The
 * gap is closed by pinning an explicit {@code @JsonProperty} carrying the strategy's own answer — a
 * runtime no-op for Jackson (an explicit {@code @JsonProperty} on a field takes precedence over the
 * class strategy and here carries the identical value) that makes the SDK agree. See
 * {@link RetrofitPinnedNames} for how the two halves are kept in lockstep.
 *
 * <p>Only strategies whose transformation is a known, pure function of the field name are supported.
 * A custom strategy class (probate's {@code RegularCaseNamingStrategy}) is arbitrary Java the
 * converter cannot evaluate, so it yields empty and every member of such a class keeps its
 * pre-existing refuse-to-guess behaviour rather than being guessed at.
 */
enum NamingStrategy {

  /**
   * {@code PropertyNamingStrategies.UpperCamelCaseStrategy} — {@code addressLine1 → AddressLine1}.
   */
  UPPER_CAMEL_CASE {
    @Override
    String idFor(String fieldName) {
      if (fieldName == null || fieldName.isEmpty()) {
        return fieldName;
      }
      return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }
  },

  /**
   * {@code PropertyNamingStrategies.SnakeCaseStrategy} — {@code postTown → post_town}.
   */
  SNAKE_CASE {
    @Override
    String idFor(String fieldName) {
      if (fieldName == null || fieldName.isEmpty()) {
        return fieldName;
      }
      // Jackson's own algorithm (PropertyNamingStrategies.SnakeCaseStrategy#translate): lower-case
      // everything, inserting a single '_' before each upper-case character that follows a character
      // already written, and collapsing a run of upper-case into one underscore-prefixed run.
      StringBuilder result = new StringBuilder(fieldName.length() * 2);
      int resultLength = 0;
      boolean wasPrevTranslated = false;
      for (int i = 0; i < fieldName.length(); i++) {
        char c = fieldName.charAt(i);
        if (i > 0 || c != '_') {
          if (Character.isUpperCase(c)) {
            if (!wasPrevTranslated && resultLength > 0 && result.charAt(resultLength - 1) != '_') {
              result.append('_');
              resultLength++;
            }
            c = Character.toLowerCase(c);
            wasPrevTranslated = true;
          } else {
            wasPrevTranslated = false;
          }
          result.append(c);
          resultLength++;
        }
      }
      return resultLength > 0 ? result.toString() : fieldName;
    }
  };

  /**
   * The CCD id a field of this class serialises under.
   *
   * @param fieldName the Java field name
   * @return the serialised property name
   */
  abstract String idFor(String fieldName);

  /**
   * The naming strategy a type's {@code @JsonNaming} declares, when it is one this converter can
   * evaluate statically.
   *
   * @param type the parsed model type
   * @return the strategy, or empty when the type carries no {@code @JsonNaming} or names a strategy
   *     whose transformation cannot be evaluated without running it
   */
  static Optional<NamingStrategy> of(ModelSourceIndex.Type type) {
    if (type == null || type.decl == null) {
      return Optional.empty();
    }
    return Annotations.find(type.decl, "JsonNaming").flatMap(NamingStrategy::forAnnotation);
  }

  /**
   * Maps a {@code @JsonNaming} annotation's referenced strategy class to a supported strategy. Both
   * the single-member form ({@code @JsonNaming(X.class)}) and the named form
   * ({@code @JsonNaming(value = X.class)}) are read, and the reference is matched on its trailing
   * name so any import style resolves ({@code UpperCamelCaseStrategy},
   * {@code PropertyNamingStrategies.UpperCamelCaseStrategy}, or the fully-qualified form).
   */
  private static Optional<NamingStrategy> forAnnotation(AnnotationExpr ann) {
    return strategyClassName(ann).flatMap(NamingStrategy::forClassName);
  }

  private static Optional<String> strategyClassName(AnnotationExpr ann) {
    Expression value = null;
    if (ann instanceof SingleMemberAnnotationExpr single) {
      value = single.getMemberValue();
    } else if (ann instanceof NormalAnnotationExpr normal) {
      value = normal.getPairs().stream()
          .filter(p -> p.getNameAsString().equals("value"))
          .findFirst()
          .map(p -> p.getValue())
          .orElse(null);
    }
    if (!(value instanceof ClassExpr classExpr)) {
      return Optional.empty();
    }
    String name = classExpr.getType().asString();
    int lastDot = name.lastIndexOf('.');
    return Optional.of(lastDot < 0 ? name : name.substring(lastDot + 1));
  }

  /**
   * The strategy for a simple class name, covering both the modern
   * {@code PropertyNamingStrategies.*Strategy} classes and the deprecated
   * {@code PropertyNamingStrategy.*} spellings teams still carry, plus the {@code UPPER_CAMEL_CASE} /
   * {@code SNAKE_CASE} constant aliases. Anything else — including a team's own strategy subclass —
   * is unsupported on purpose.
   */
  private static Optional<NamingStrategy> forClassName(String simpleName) {
    String normalised = simpleName.toUpperCase(Locale.ROOT).replace("_", "");
    if (normalised.equals("UPPERCAMELCASESTRATEGY") || normalised.equals("UPPERCAMELCASE")
        || normalised.equals("PASCALCASESTRATEGY")) {
      return Optional.of(UPPER_CAMEL_CASE);
    }
    if (normalised.equals("SNAKECASESTRATEGY") || normalised.equals("SNAKECASE")) {
      return Optional.of(SNAKE_CASE);
    }
    return Optional.empty();
  }
}
