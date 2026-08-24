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
        // An unconditionally-true predicate is not an environment switch at all: the fragment ships
        // in every build and was split out for editorial reasons (finrem's `common`, `newPaperCase`
        // and `manageScannedDocs`). Reporting it as BASE is what lets its rows derive as plain Java —
        // a suffix, however inert, otherwise refuses whole CaseEventToComplexTypes groups and forces
        // a verbatim passthrough that gates nothing. Skipped rather than returned so a row carrying
        // both an inert and a real suffix still reports the real one.
        OverlayCondition condition = options.getOverlaySuffixes().get(tag);
        if (condition != null && condition.isUnconditionallyTrue()) {
          continue;
        }
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

  /**
   * Whether a row may contribute to a sheet the SDK emits unconditionally — true for a base row and
   * for a suffixed row whose predicate is active in the convert-time environment.
   *
   * <p>Several sheets have no per-row environment switch in the SDK at all: role-to-access-profile
   * mappings, state authorisations, search-party definitions and per-field event placements are
   * emitted from static configuration, so a definition that splits them across mutually-exclusive
   * overlay fragments (sscs's {@code -nonWA} against {@code -WA-nonprod}) can only be reproduced by
   * admitting the fragment that the build being converted would actually have used. Admitting both
   * halves produces a definition that is wrong in every environment: the two collide and the loser's
   * rows survive as grants and placements a real build never emits.
   *
   * <p>This is the same rule the case-type grant loop and the overlay-only {@code CaseField} path
   * already apply; it is factored here because six further row loops need it identically.
   *
   * @param overlayTags the row's overlay tags
   * @param options the conversion configuration carrying the suffix-to-predicate map
   * @return true when the row contributes, false when its overlay predicate is inactive
   */
  static boolean isActiveRow(Set<String> overlayTags, ConversionOptions options) {
    String suffix = suffixFor(overlayTags, options);
    if (suffix == null) {
      return true;
    }
    OverlayCondition condition = conditionFor(suffix, options);
    return condition == null || condition.isActive();
  }
}
