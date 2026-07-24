package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A small ordered set of CRUD permission letters with parse/format helpers, mirroring the
 * SDK's canonical C,R,U,D ordering so generated strings match the generator's output exactly.
 */
final class CrudSet {

  private static final String ORDER = "CRUD";

  private CrudSet() {
  }

  /**
   * Parses a CRUD string into the set of permission letters it contains.
   *
   * @param crud a string such as "CRU" (case-insensitive), or null
   * @return the set of upper-case permission letters present
   */
  static Set<Character> parse(String crud) {
    Set<Character> result = new LinkedHashSet<>();
    if (crud == null) {
      return result;
    }
    for (char ch : ORDER.toCharArray()) {
      if (crud.toUpperCase().indexOf(ch) >= 0) {
        result.add(ch);
      }
    }
    return result;
  }

  /**
   * Formats a permission set back into canonical C,R,U,D order.
   *
   * @param perms the permission letters
   * @return the ordered CRUD string, empty when the set is empty
   */
  static String format(Set<Character> perms) {
    StringBuilder builder = new StringBuilder();
    for (char ch : ORDER.toCharArray()) {
      if (perms.contains(ch)) {
        builder.append(ch);
      }
    }
    return builder.toString();
  }
}
