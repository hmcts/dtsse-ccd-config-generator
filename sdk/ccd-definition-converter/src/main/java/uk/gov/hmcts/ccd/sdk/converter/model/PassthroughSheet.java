package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * Raw JSON to be merged into the generated definition output after generation runs.
 *
 * <p>The SDK generator deletes its output directory before writing, so passthrough content
 * cannot be pre-seeded; the PassthroughMerger applies it afterwards using the SDK's
 * JsonUtils.mergeInto, which adds missing rows and grafts missing columns onto existing rows
 * without overwriting generated values.
 */
@Value
@Builder
public class PassthroughSheet {

  /** The output file the rows belong to, relative to the case type dir, e.g. "Banner.json". */
  String relativePath;

  /** The sheet's primary-key columns, for mergeInto row matching. */
  List<String> primaryKeys;

  /**
   * Overlay suffix this content belongs to, or null for base content. Suffix-tagged sheets
   * are merged only when their overlay predicate evaluates true.
   */
  String overlaySuffix;

  OverlayCondition overlayCondition;

  List<Map<String, Object>> rows;

  /**
   * Columns whose passthrough value should overwrite the generator's value on a matched row,
   * rather than only filling in a column the generator omitted. Empty (the default) preserves the
   * strictly-additive merge. Used for columns the SDK emits with a forced default it cannot vary
   * (e.g. a {@code State}'s {@code Description}, hardcoded to the state {@code Name} by
   * {@code StateGenerator}), so the input's real value is faithfully reproduced rather than
   * forgiven by a comparator rule.
   */
  @Builder.Default
  List<String> overwriteColumns = java.util.List.of();
}
