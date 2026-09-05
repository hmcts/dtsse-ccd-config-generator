package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code @CCD(hint = …)} values {@link RetrofitPatchEmitter} will pin onto existing complex-type
 * members, keyed by owning class FQN then Java member name.
 *
 * <p>Read by {@link RetrofitEventComplexTypeGraph} for the same reason
 * {@link RetrofitPlannedSynthesis} and {@link RetrofitPlannedRetypes} are: the graph must see the model
 * as the APPLIED PATCH will leave it, not as the source currently reads. A member's {@code @CCD(hint)}
 * does not stay on its {@code ComplexTypes} row — {@code CaseEventToComplexTypesGenerator} cascades it
 * onto every {@code CaseEventToComplexTypes} row that places the member, unless the placement overrides
 * it with {@code .hintText(value)}/{@code .noHintText()}. The linker picks between those three by
 * comparing the event row's own {@code HintText} against the member's DECLARED hint.
 *
 * <p>So reading the parsed hint is a read-before-write. sscs's {@code rip1Document} is the case: the
 * team's field carries no hint, and the event rows carry none either, so the linker saw "equal, leave
 * the cascade unset" — and then the patch pinned the {@code ComplexTypes} sheet's
 * {@code Document must be PDF formatted} onto the member, which cascaded onto three event rows the
 * definition has no {@code HintText} on at all. Deciding against the hint the patch WILL pin yields
 * {@code .noHintText()} on those placements instead, which is what the definition says.
 *
 * <p>Absent entries mean "the patch pins no hint here", so the parsed declaration stands — that is the
 * whole reason this is a plan and not a re-derivation: a member whose hint the patch declines to pin
 * must not be treated as though it had one.
 */
final class RetrofitPlannedHints {

  private final Map<String, String> byOwnerAndMember = new LinkedHashMap<>();

  /** An empty plan, for generate mode and for tests that exercise no hint pin. */
  static RetrofitPlannedHints empty() {
    return new RetrofitPlannedHints();
  }

  /**
   * Records the hint the patch will pin on one complex-type member. First write wins, matching the
   * emitter's own per-member claim: a member reached through two definition types is annotated once.
   *
   * @param ownerFqn the FQN of the class declaring the member
   * @param memberName the Java field name
   * @param hint the hint the {@code @CCD} will carry, or null/empty when it will carry none
   */
  void record(String ownerFqn, String memberName, String hint) {
    if (hint == null || hint.isEmpty()) {
      return;
    }
    byOwnerAndMember.putIfAbsent(key(ownerFqn, memberName), hint);
  }

  /**
   * The hint the patch will pin on a member, empty when it pins none and the parsed declaration stands.
   *
   * @param ownerFqn the owning model class FQN
   * @param memberName the Java field name
   * @return the planned hint, or empty
   */
  Optional<String> forMember(String ownerFqn, String memberName) {
    return Optional.ofNullable(byOwnerAndMember.get(key(ownerFqn, memberName)));
  }

  private static String key(String ownerFqn, String memberName) {
    return ownerFqn + "#" + memberName;
  }
}
