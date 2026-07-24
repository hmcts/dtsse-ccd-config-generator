package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.Optional;

/**
 * Small helpers for reading the Jackson / SDK annotations the retrofit resolver cares about off a
 * JavaParser declaration, in a way that mirrors how the SDK reads them reflectively at generation
 * time (see {@code FieldUtils}/{@code CaseFieldGenerator}).
 */
final class Annotations {

  private Annotations() {
  }

  /**
   * Whether a declaration carries an annotation of the given simple name (matching either the
   * bare {@code @Foo} or a fully-qualified {@code @a.b.Foo} form).
   *
   * @param decl the JavaParser body declaration (field, class or enum constant)
   * @param simpleName the annotation's simple name, e.g. {@code JsonIgnore}
   * @return true when the annotation is present
   */
  static boolean has(BodyDeclaration<?> decl, String simpleName) {
    return find(decl, simpleName).isPresent();
  }

  /**
   * The annotation of the given simple name on a declaration, if present.
   *
   * @param decl the declaration to inspect
   * @param simpleName the annotation's simple name
   * @return the annotation expression, or empty
   */
  static Optional<AnnotationExpr> find(BodyDeclaration<?> decl, String simpleName) {
    for (AnnotationExpr ann : decl.getAnnotations()) {
      if (ann.getNameAsString().equals(simpleName)
          || ann.getNameAsString().endsWith("." + simpleName)) {
        return Optional.of(ann);
      }
    }
    return Optional.empty();
  }

  /**
   * The {@code value} member of an annotation as a string literal, handling both the
   * single-member form ({@code @JsonProperty("x")}) and the normal form
   * ({@code @JsonProperty(value = "x")}). Returns empty for a marker annotation
   * ({@code @JsonProperty}) or a non-literal value.
   *
   * @param ann the annotation expression
   * @return the string value, or empty
   */
  static Optional<String> stringValue(AnnotationExpr ann) {
    return stringMember(ann, "value");
  }

  /**
   * A named string member of an annotation (e.g. {@code prefix} on {@code @JsonUnwrapped}),
   * falling back to the single-member value when the requested member is {@code value}.
   *
   * @param ann the annotation expression
   * @param member the member name
   * @return the string value, or empty when absent or non-literal
   */
  static Optional<String> stringMember(AnnotationExpr ann, String member) {
    if (ann instanceof SingleMemberAnnotationExpr single && "value".equals(member)) {
      if (single.getMemberValue() instanceof StringLiteralExpr lit) {
        return Optional.of(lit.asString());
      }
      return Optional.empty();
    }
    if (ann instanceof NormalAnnotationExpr normal) {
      return normal.getPairs().stream()
          .filter(p -> p.getNameAsString().equals(member))
          .findFirst()
          .flatMap(p -> p.getValue() instanceof StringLiteralExpr lit
              ? Optional.of(lit.asString())
              : Optional.empty());
    }
    return Optional.empty();
  }

  /**
   * Whether an annotation has a boolean member set to {@code true} (e.g. {@code @CCD(ignore = true)}).
   *
   * @param ann the annotation expression
   * @param member the member name
   * @return true when the member is present and literally {@code true}
   */
  static boolean booleanMemberTrue(AnnotationExpr ann, String member) {
    if (ann instanceof NormalAnnotationExpr normal) {
      return normal.getPairs().stream()
          .filter(p -> p.getNameAsString().equals(member))
          .anyMatch(p -> "true".equals(p.getValue().toString()));
    }
    return false;
  }
}
