package uk.gov.hmcts.ccd.sdk.converter.link;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Chooses the base access of a grouped CaseData container ({@code CaseDataGrouper}): the
 * container's {@code @CCD(access)} carries the grants every member has, and each member lists only
 * the rest of its residual.
 *
 * <p>The SDK's AuthorisationCaseFieldGenerator gives each member of an {@code @JsonUnwrapped}
 * container the container's classes plus its own. A base grant {@code role -> crud} is safe on a
 * member whose definition grants that role exactly {@code crud}: if the role is in the member's
 * residual it is the same grant; if not, the SDK already injects a subset of it, so adding it
 * changes nothing. The base therefore takes each residual grant every member's definition grants
 * identically, as long as at least two members would otherwise list it.
 */
final class GroupBaseAccess {

  private GroupBaseAccess() {
  }

  /**
   * One group's base access.
   *
   * @param base the container's grants (role to CRUD); empty when the members share none
   * @param memberGrants member field id to the grants it still lists itself
   */
  record Decision(Map<String, String> base, Map<String, Map<String, String>> memberGrants) {
  }

  /**
   * Decides the base for one group.
   *
   * @param members the members' original CCD field ids
   * @param desired field id to role to definition CRUD
   * @param residuals field id to residual grant map (role to full definition CRUD)
   * @return the base grants and each member's remaining grants
   */
  static Decision decide(
      List<String> members,
      Map<String, Map<String, String>> desired,
      Map<String, Map<String, String>> residuals) {
    // Candidate grant -> how many members carry it in their residual.
    Map<Map.Entry<String, String>, Integer> carriers = new LinkedHashMap<>();
    for (String m : members) {
      new TreeMap<>(residuals.getOrDefault(m, Map.of()))
          .forEach((role, crud) -> carriers.merge(Map.entry(role, crud), 1, Integer::sum));
    }

    Map<String, String> base = new TreeMap<>();
    carriers.forEach((grant, count) -> {
      if (count >= 2 && members.stream().allMatch(m -> grant.getValue().equals(
          normalised(desired.getOrDefault(m, Map.of()).get(grant.getKey()))))) {
        base.put(grant.getKey(), grant.getValue());
      }
    });

    Map<String, Map<String, String>> own = new LinkedHashMap<>();
    for (String m : members) {
      Map<String, String> grants = new TreeMap<>(residuals.getOrDefault(m, Map.of()));
      base.forEach((role, crud) -> grants.remove(role, crud));
      own.put(m, grants);
    }
    return new Decision(base, own);
  }

  private static String normalised(String crud) {
    return crud == null ? null : CrudSet.format(CrudSet.parse(crud));
  }
}
