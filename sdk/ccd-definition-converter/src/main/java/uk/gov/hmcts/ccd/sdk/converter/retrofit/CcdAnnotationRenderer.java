package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Renders the {@code @CCD(...)} annotation source text the retrofit patch adds to a model field,
 * from the {@link FieldModel} the linker computed. This mirrors {@code FieldEmitHelper} exactly —
 * same attributes (label, hint, showCondition, regex, categoryID, searchable=false,
 * retainHiddenValue, min/max, typeOverride, typeParameterOverride, gate, access) and the same
 * "only emit @CCD when it carries something" ({@code hasAny}) rule — but as a String for source
 * insertion rather than a JavaPoet {@code AnnotationSpec}, since the patch edits the team's own
 * source rather than generating a fresh class.
 *
 * <p>Access classes are referenced by simple name (e.g. {@code Access.class}); the patch adds the
 * matching {@code import <configPackage>.<Access>;} so the reference resolves.
 */
final class CcdAnnotationRenderer {

  /** The house checkstyle line-length ceiling every retrofitted team enforces (finding: annotation
   * placement). Long enough single-line @CCD forms are wrapped one member per continuation line
   * rather than left to blow past it. */
  static final int MAX_LINE_LENGTH = 120;

  /** Continuation-line indent (relative to the annotation's own indent) for a wrapped @CCD's
   * members — the model-emit golden fixture's shape ({@code CaseData.java}: base 4, members 12). */
  private static final String CONTINUATION_INDENT = "        ";

  private final String configPackage;

  CcdAnnotationRenderer(String configPackage) {
    this.configPackage = configPackage;
  }

  /**
   * The {@code @CCD(...)} source for a matched/conflict field, or null when the SDK would infer
   * every attribute anyway (so no annotation is warranted — {@code FieldEmitHelper}'s hasAny).
   * Wrapped onto its own continuation lines (one member per line) when the single-line form would
   * exceed {@link #MAX_LINE_LENGTH} columns once placed at {@code baseIndentLength}; the first line
   * of the returned text carries NO leading indent (the caller applies its own placement indent
   * uniformly to every line, including continuations).
   *
   * @param field the field model
   * @param baseIndentLength the column the annotation will be placed at (its caller's indent
   *                          width), used only to decide whether the single-line form fits
   * @return the annotation source (possibly multi-line, unindented first line), or null when
   *         nothing to emit
   */
  String render(FieldModel field, int baseIndentLength) {
    List<String> members = renderMembers(field);
    if (members.isEmpty()) {
      return null;
    }
    return renderWrapped("CCD", members, baseIndentLength);
  }

  /**
   * Renders {@code @<simpleName>(m1, m2, ...)} on one line, or one member per continuation line
   * (indented by {@link #CONTINUATION_INDENT} relative to the annotation's own placement indent,
   * closing paren back at that indent) when the single-line form would not fit
   * {@link #MAX_LINE_LENGTH} columns at {@code baseIndentLength}.
   */
  private static String renderWrapped(String simpleName, List<String> members, int baseIndentLength) {
    String singleLine = "@" + simpleName + "(" + String.join(", ", members) + ")";
    if (baseIndentLength + singleLine.length() <= MAX_LINE_LENGTH) {
      return singleLine;
    }
    StringBuilder sb = new StringBuilder("@").append(simpleName).append("(\n");
    for (int i = 0; i < members.size(); i++) {
      sb.append(CONTINUATION_INDENT).append(members.get(i));
      if (i < members.size() - 1) {
        sb.append(',');
      }
      sb.append('\n');
    }
    sb.append(')');
    return sb.toString();
  }

  /**
   * The individual {@code @CCD(...)} member assignments (e.g. {@code label = "x"}) for a
   * matched/conflict field, in emission order, or empty when nothing is warranted. Exposed
   * separately from {@link #render} so a caller that finds the single-line form too long to fit the
   * house style's line limit can lay each member on its own continuation line instead.
   *
   * @param field the field model
   * @return the member assignments, empty when nothing to emit
   */
  List<String> renderMembers(FieldModel field) {
    List<String> members = new ArrayList<>();
    addString(members, "label", field.getLabel());
    addString(members, "hint", field.getHint());
    addString(members, "showCondition", field.getShowCondition());
    addString(members, "regex", field.getRegex());
    addString(members, "categoryID", field.getCategoryId());
    if (Boolean.FALSE.equals(field.getSearchable())) {
      members.add("searchable = false");
    }
    if (Boolean.TRUE.equals(field.getRetainHiddenValue())) {
      members.add("retainHiddenValue = true");
    }
    if (field.getMin() != null) {
      members.add("min = " + field.getMin());
    }
    if (field.getMax() != null) {
      members.add("max = " + field.getMax());
    }
    if (field.getTypeOverride() != null && !field.getTypeOverride().isEmpty()) {
      members.add("typeOverride = FieldType." + field.getTypeOverride());
    }
    if (field.getTypeParameterOverride() != null && !field.getTypeParameterOverride().isEmpty()) {
      members.add("typeParameterOverride = " + quote(field.getTypeParameterOverride()));
    }
    if (field.getGate() != null && !field.getGate().isEmpty()) {
      members.add("gate = " + quote(field.getGate()));
    }
    if (field.getAccessClassNames() != null && !field.getAccessClassNames().isEmpty()) {
      String classes = field.getAccessClassNames().stream()
          .map(cn -> cn + ".class")
          .collect(Collectors.joining(", "));
      members.add("access = {" + classes + "}");
    }
    return members;
  }

  /**
   * Whether the field's annotation would reference {@link uk.gov.hmcts.ccd.sdk.type.FieldType} (so
   * the patch must add its import).
   *
   * @param field the field model
   * @return true when a {@code typeOverride} is present
   */
  boolean usesFieldType(FieldModel field) {
    return field.getTypeOverride() != null && !field.getTypeOverride().isEmpty();
  }

  /**
   * The fully-qualified import for an access class referenced by simple name in a rendered
   * {@code @CCD(access = {...})}: it lives in the config package the companion sources emit into.
   *
   * @param accessClassSimpleName the access class's simple name
   * @return the import statement text
   */
  String accessImport(String accessClassSimpleName) {
    return "import " + configPackage + "." + accessClassSimpleName + ";";
  }

  private void addString(List<String> members, String name, String value) {
    if (value != null && !value.isEmpty()) {
      members.add(name + " = " + quote(value));
    }
  }

  /**
   * Renders a Java string literal for {@code value}, escaping every character that would otherwise
   * make the literal invalid — backslash and double-quote, plus the whitespace control characters
   * (newline, carriage return, tab, form-feed, backspace) that CCD labels/hints legitimately carry
   * (e.g. a multi-line label). JavaPoet's {@code $S} does this for generate mode; the retrofit
   * renderer must match so the emitted {@code @CCD(...)} parses (a raw {@code \n} inside the quotes
   * is a lexical error — the bug this fixes, hit on Civil's multi-line labels).
   */
  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\f' -> out.append("\\f");
        case '\b' -> out.append("\\b");
        default -> out.append(c);
      }
    }
    return out.append('"').toString();
  }
}
