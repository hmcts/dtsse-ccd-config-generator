package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed, prefix-aware label builder for CCD event fields.
 *
 * <p>Usage:
 * <pre>
 *   TypedLabel.label("Is the property in %s or %s?",
 *       CreateClaimData::getCrossBorderCountry1,
 *       CreateClaimData::getCrossBorderCountry2)
 * </pre>
 *
 * <p>Calling {@code toString()} produces:
 * {@code "Is the property in ${crossBorderCountry1} or ${crossBorderCountry2}?"}
 *
 * <p>The generator's existing prefix rewriting then converts to:
 * {@code "Is the property in ${cpcCrossBorderCountry1} or ${cpcCrossBorderCountry2}?"}
 */
public class TypedLabel {

  private final String resolvedTemplate;

  private TypedLabel(String resolvedTemplate) {
    this.resolvedTemplate = resolvedTemplate;
  }

  /**
   * Creates a typed label with format string and field references.
   * Each {@code %s} in the format string is replaced with a {@code ${fieldName}} reference.
   */
  @SafeVarargs
  public static <T> TypedLabel label(String format, TypedPropertyGetter<T, ?>... fields) {
    List<String> fieldNames = new ArrayList<>();
    for (TypedPropertyGetter<T, ?> getter : fields) {
      fieldNames.add(LambdaUtils.resolvePropertyName(getter));
    }

    // Replace each %s with ${fieldName}
    String result = format;
    for (String fieldName : fieldNames) {
      result = result.replaceFirst(
          Pattern.quote("%s"),
          Matcher.quoteReplacement("${" + fieldName + "}"));
    }
    return new TypedLabel(result);
  }

  /**
   * Returns the label text with bare {@code ${fieldName}} references (no prefix).
   * The generator's existing regex-based prefix rewriting handles prefixing.
   */
  @Override
  public String toString() {
    return resolvedTemplate;
  }
}
