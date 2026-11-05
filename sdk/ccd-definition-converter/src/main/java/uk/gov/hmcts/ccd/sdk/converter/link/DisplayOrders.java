package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Puts pages, tabs, their fields and search fields into the sequence CCD presents them in, so the
 * SDK's declaration-order renumbering reproduces it.
 *
 * <p>CCD sorts each of these by its display-order column. Where two items share a value the
 * definition store does not always decide between them: it groups pages and tabs through a
 * {@code HashMap} ({@code AbstractDisplayGroupParser}), reads pages back with an SQL
 * {@code ORDER BY display_order} that PostgreSQL resolves with an unstable sort, and returns a
 * page's fields as a {@code HashSet} ({@code DisplayGroupAdapterService}). The converter breaks
 * every tie by position in the definition and records an advisory gap for it, because the
 * generated definition numbers each item uniquely and so fixes an order the source left open.</p>
 */
final class DisplayOrders {

  private DisplayOrders() {
  }

  /**
   * A copy of {@code items} sorted by display order, ties kept in their input order, items with no
   * display order last (as both PostgreSQL's ascending sort and XUI's {@code OrderService} place
   * them).
   */
  static <T> List<T> sorted(List<T> items, Function<T, Integer> order) {
    List<T> copy = new ArrayList<>(items);
    copy.sort(Comparator.comparing(order, Comparator.nullsLast(Comparator.naturalOrder())));
    return copy;
  }

  /**
   * Records one advisory gap per display-order value that more than one item shares.
   *
   * @param items  the items in definition order
   * @param order  each item's display order (null when the definition gives none)
   * @param id     each item's identifier, as the gap names it
   * @param sheet  the sheet the items come from
   * @param column the display-order column that collides
   * @param scope  what the items are ordered within, e.g. {@code "Bail event editBailApplication"}
   * @param noun   what the items are, e.g. {@code "pages"}
   * @param cause  how CCD itself orders the tied items
   */
  static <T> void reportCollisions(List<T> items, Function<T, Integer> order,
                                   Function<T, String> id, String sheet, String column,
                                   String scope, String noun, String cause,
                                   GapCollector gaps) {
    Map<Integer, List<String>> byOrder = new LinkedHashMap<>();
    for (T item : items) {
      Integer value = order.apply(item);
      if (value != null) {
        byOrder.computeIfAbsent(value, k -> new ArrayList<>()).add(id.apply(item));
      }
    }
    for (Map.Entry<Integer, List<String>> entry : byOrder.entrySet()) {
      List<String> ids = entry.getValue();
      if (ids.size() < 2) {
        continue;
      }
      String listed = ids.stream().collect(Collectors.joining(", "));
      gaps.add(GapEntry.builder()
          .sheet(sheet)
          .rowKey(scope + ": " + listed)
          .column(column)
          .value(String.valueOf(entry.getKey()))
          .category(GapCategory.UNSUPPORTED_VALUE)
          .action(GapAction.ADVISORY)
          .detail("In " + scope + ", " + noun + " " + listed + " share " + column + " "
              + entry.getKey() + ". " + cause + " The converted config fixes them in definition"
              + " order (" + listed + "); confirm that is the order the service wants.")
          .build());
    }
  }
}
