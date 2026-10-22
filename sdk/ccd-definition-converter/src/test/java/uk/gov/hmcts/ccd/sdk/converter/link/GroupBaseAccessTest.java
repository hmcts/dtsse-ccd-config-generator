package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupBaseAccessTest {

  @Test
  void baseIsTheGrantsEveryMemberHasAndMembersKeepOnlyTheirResidual() {
    // a and b carry judge=R in their residual; c's definition grants judge=R too, but the SDK
    // already injects it (so it is not in c's residual), so the base reaches c harmlessly.
    Map<String, Map<String, String>> desired = Map.of(
        "a", Map.of("judge", "R", "clerk", "CRU"),
        "b", Map.of("judge", "R", "clerk", "R"),
        "c", Map.of("judge", "R"));
    Map<String, Map<String, String>> residuals = Map.of(
        "a", Map.of("judge", "R", "clerk", "CRU"),
        "b", Map.of("judge", "R", "clerk", "R"),
        "c", Map.of());

    GroupBaseAccess.Decision d =
        GroupBaseAccess.decide(List.of("a", "b", "c"), desired, residuals);

    assertThat(d.base()).isEqualTo(Map.of("judge", "R"));
    assertThat(d.memberGrants())
        .containsEntry("a", Map.of("clerk", "CRU"))
        .containsEntry("b", Map.of("clerk", "R"))
        .containsEntry("c", Map.of());
  }

  @Test
  void memberWhoseResidualIsTheBaseListsNothing() {
    Map<String, Map<String, String>> grants = Map.of(
        "a", Map.of("judge", "R", "clerk", "CRU"),
        "b", Map.of("judge", "R", "clerk", "CRU", "citizen", "R"));

    GroupBaseAccess.Decision d = GroupBaseAccess.decide(List.of("a", "b"), grants, grants);

    assertThat(d.base()).isEqualTo(Map.of("judge", "R", "clerk", "CRU"));
    assertThat(d.memberGrants())
        .containsEntry("a", Map.of())
        .containsEntry("b", Map.of("citizen", "R"));
  }

  @Test
  void grantOneMemberHasAtAnotherLevelStaysOffTheBase() {
    Map<String, Map<String, String>> grants = Map.of(
        "a", Map.of("judge", "R"),
        "b", Map.of("judge", "R"),
        "c", Map.of("judge", "CRU"));

    GroupBaseAccess.Decision d = GroupBaseAccess.decide(List.of("a", "b", "c"), grants, grants);

    assertThat(d.base()).isEmpty();
    assertThat(d.memberGrants()).isEqualTo(grants);
  }

  @Test
  void groupWithNoSharedGrantGetsNoBase() {
    Map<String, Map<String, String>> grants = Map.of(
        "a", Map.of("judge", "R"),
        "b", Map.of("clerk", "CRU"),
        "c", Map.of());

    GroupBaseAccess.Decision d = GroupBaseAccess.decide(List.of("a", "b", "c"), grants, grants);

    assertThat(d.base()).isEmpty();
    assertThat(d.memberGrants()).isEqualTo(grants);
  }

  @Test
  void grantOnlyOneMemberWouldListStaysOnThatMember() {
    // b's definition grants judge=R but the SDK injects it; only a would list it, so moving it to
    // the container saves nothing.
    Map<String, Map<String, String>> desired = Map.of(
        "a", Map.of("judge", "R"),
        "b", Map.of("judge", "R"));
    Map<String, Map<String, String>> residuals = Map.of(
        "a", Map.of("judge", "R"),
        "b", Map.of());

    GroupBaseAccess.Decision d = GroupBaseAccess.decide(List.of("a", "b"), desired, residuals);

    assertThat(d.base()).isEmpty();
    assertThat(d.memberGrants()).containsEntry("a", Map.of("judge", "R"));
  }
}
