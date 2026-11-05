package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Compares the order users actually see, which the row-matching comparator cannot: it strips every
 * {@code *DisplayOrder} column, so a regenerated definition that places the right fields on the
 * right pages but shows the pages in a different sequence still matches.
 *
 * <p>Each side's effective sequence is derived the way CCD presents it: items sorted by their
 * display order, nulls last, ties broken by position in the sheet. A group's order is read from
 * its first row, as the definition store's {@code AbstractDisplayGroupParser} does. Only items
 * present on both sides are compared, so a row that is missing altogether is reported once, by the
 * row comparator, rather than again here.</p>
 *
 * <ul>
 *   <li>{@code CaseEventToFields}: the pages of each event, and the fields of each page.</li>
 *   <li>{@code CaseTypeTab}: the tabs, and the fields of each tab.</li>
 *   <li>Search and work-basket sheets: the fields of each (use case, role) scope.</li>
 * </ul>
 */
final class DisplaySequences {

  private static final List<String> FIELD_LIST_SHEETS = List.of(
      "SearchInputFields", "SearchResultFields", "WorkBasketInputFields",
      "WorkBasketResultFields", "SearchCasesResultFields");

  private DisplaySequences() {
  }

  static List<String> differences(Map<String, List<Map<String, Object>>> expected,
                                  Map<String, List<Map<String, Object>>> actual) {
    List<String> out = new ArrayList<>();
    compareGroups(out, "CaseEventToFields", "event", expected, actual,
        row -> text(row.get("CaseEventID")), "PageID", "PageDisplayOrder",
        "CaseFieldID", "PageFieldDisplayOrder");
    compareGroups(out, "CaseTypeTab", "case type", expected, actual,
        row -> text(row.get("CaseTypeID")), "TabID", "TabDisplayOrder",
        "CaseFieldID", "TabFieldDisplayOrder");
    for (String sheet : FIELD_LIST_SHEETS) {
      compareFieldLists(out, sheet, expected, actual);
    }
    return out;
  }

  private static void compareGroups(List<String> out, String sheet, String scopeName,
                                    Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    Function<Map<String, Object>, String> scopeOf,
                                    String groupColumn, String groupOrderColumn,
                                    String itemColumn, String itemOrderColumn) {
    Map<String, Map<String, List<Map<String, Object>>>> expectedScopes =
        groupBy(expected.getOrDefault(sheet, List.of()), scopeOf, groupColumn);
    Map<String, Map<String, List<Map<String, Object>>>> actualScopes =
        groupBy(actual.getOrDefault(sheet, List.of()), scopeOf, groupColumn);
    for (Map.Entry<String, Map<String, List<Map<String, Object>>>> scope : expectedScopes.entrySet()) {
      Map<String, List<Map<String, Object>>> actualGroups = actualScopes.get(scope.getKey());
      if (actualGroups == null) {
        continue;
      }
      Map<String, List<Map<String, Object>>> expectedGroups = scope.getValue();
      report(out, sheet + " " + scopeName + " " + scope.getKey() + " " + groupColumn + " sequence",
          groupSequence(expectedGroups, groupOrderColumn),
          groupSequence(actualGroups, groupOrderColumn));
      for (Map.Entry<String, List<Map<String, Object>>> group : expectedGroups.entrySet()) {
        List<Map<String, Object>> actualRows = actualGroups.get(group.getKey());
        if (actualRows == null) {
          continue;
        }
        report(out, sheet + " " + scopeName + " " + scope.getKey() + " " + groupColumn + " "
                + group.getKey() + " " + itemColumn + " sequence",
            itemSequence(group.getValue(), row -> text(row.get(itemColumn)), itemOrderColumn),
            itemSequence(actualRows, row -> text(row.get(itemColumn)), itemOrderColumn));
      }
    }
  }

  private static void compareFieldLists(List<String> out, String sheet,
                                        Map<String, List<Map<String, Object>>> expected,
                                        Map<String, List<Map<String, Object>>> actual) {
    Map<String, List<Map<String, Object>>> expectedScopes =
        scopeRows(expected.getOrDefault(sheet, List.of()));
    Map<String, List<Map<String, Object>>> actualScopes =
        scopeRows(actual.getOrDefault(sheet, List.of()));
    for (Map.Entry<String, List<Map<String, Object>>> scope : expectedScopes.entrySet()) {
      List<Map<String, Object>> actualRows = actualScopes.get(scope.getKey());
      if (actualRows == null) {
        continue;
      }
      report(out, sheet + " scope " + scope.getKey() + " CaseFieldID sequence",
          itemSequence(scope.getValue(), DisplaySequences::fieldListItem, "DisplayOrder"),
          itemSequence(actualRows, DisplaySequences::fieldListItem, "DisplayOrder"));
    }
  }

  private static Map<String, List<Map<String, Object>>> scopeRows(List<Map<String, Object>> rows) {
    Map<String, List<Map<String, Object>>> scopes = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
      String key = "[useCase=" + text(row.get("UseCase")) + ", role=" + text(role) + "]";
      scopes.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }
    return scopes;
  }

  private static String fieldListItem(Map<String, Object> row) {
    String code = text(row.get("ListElementCode"));
    String field = text(row.get("CaseFieldID"));
    return code.isEmpty() ? field : field + "." + code;
  }

  private static Map<String, Map<String, List<Map<String, Object>>>> groupBy(
      List<Map<String, Object>> rows, Function<Map<String, Object>, String> scopeOf,
      String groupColumn) {
    Map<String, Map<String, List<Map<String, Object>>>> scopes = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      scopes.computeIfAbsent(scopeOf.apply(row), k -> new LinkedHashMap<>())
          .computeIfAbsent(text(row.get(groupColumn)), k -> new ArrayList<>())
          .add(row);
    }
    return scopes;
  }

  private static List<String> groupSequence(Map<String, List<Map<String, Object>>> groups,
                                            String orderColumn) {
    List<Map.Entry<String, BigDecimal>> keyed = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
      keyed.add(new AbstractMap.SimpleImmutableEntry<>(group.getKey(), order(group.getValue().get(0).get(orderColumn))));
    }
    return sorted(keyed);
  }

  private static List<String> itemSequence(List<Map<String, Object>> rows,
                                           Function<Map<String, Object>, String> itemOf,
                                           String orderColumn) {
    List<Map.Entry<String, BigDecimal>> keyed = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      String item = itemOf.apply(row);
      if (seen.add(item)) {
        keyed.add(new AbstractMap.SimpleImmutableEntry<>(item, order(row.get(orderColumn))));
      }
    }
    return sorted(keyed);
  }

  private static List<String> sorted(List<Map.Entry<String, BigDecimal>> keyed) {
    List<Map.Entry<String, BigDecimal>> copy = new ArrayList<>(keyed);
    copy.sort(Comparator.comparing(Map.Entry::getValue,
        Comparator.nullsLast(Comparator.naturalOrder())));
    return copy.stream().map(Map.Entry::getKey).toList();
  }

  private static void report(List<String> out, String what, List<String> expected,
                             List<String> actual) {
    Set<String> common = new LinkedHashSet<>(expected);
    common.retainAll(new LinkedHashSet<>(actual));
    List<String> expectedCommon = expected.stream().filter(common::contains).toList();
    List<String> actualCommon = actual.stream().filter(common::contains).toList();
    if (!expectedCommon.equals(actualCommon)) {
      out.add("DISPLAY_SEQUENCE " + what + ": expected " + expectedCommon
          + " but was " + actualCommon);
    }
  }

  private static BigDecimal order(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Number number) {
      BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
      return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
    }
    return String.valueOf(value).trim();
  }
}
