package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;

/**
 * Resolves a row's overlay suffix tags against the configured suffix-to-predicate map.
 *
 * <p>A row can carry several overlay tags (its source filename may combine suffixes). The
 * settled rule: a row is treated as base when it carries no tags; otherwise the first tag that
 * matches a configured suffix, in the row's tag iteration order, selects the overlay predicate.
 * A tag with no configured predicate is reported by the caller as an unexpressible overlay.
 */
final class OverlayResolver {

  private OverlayResolver() {
  }

  /**
   * The overlay suffix that governs a row, or null when the row is base.
   *
   * @param overlayTags the row's overlay tags
   * @param options the conversion configuration carrying the suffix-to-predicate map
   * @return the governing suffix, or null when the row is base or no tag is configured
   */
  static String suffixFor(Set<String> overlayTags, ConversionOptions options) {
    if (overlayTags == null || overlayTags.isEmpty()) {
      return null;
    }
    if (options.getOverlaySuffixes() == null) {
      return null;
    }
    for (String tag : overlayTags) {
      if (options.getOverlaySuffixes().containsKey(tag)) {
        return tag;
      }
    }
    return null;
  }

  /**
   * The predicate for an overlay suffix, or null when the suffix is unknown or the row is base.
   *
   * @param suffix the overlay suffix, possibly null
   * @param options the conversion configuration
   * @return the overlay predicate, or null
   */
  static OverlayCondition conditionFor(String suffix, ConversionOptions options) {
    if (suffix == null || options.getOverlaySuffixes() == null) {
      return null;
    }
    return options.getOverlaySuffixes().get(suffix);
  }
}
