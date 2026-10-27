package uk.gov.hmcts.rt.model.common;

/**
 * A model-side call site that constructs {@link BoundParty} POSITIONALLY, mirroring prl's own
 * {@code new WithoutNoticeOrderDetails(YesOrNo.Yes)}. The retrofit patch widens {@code BoundParty}'s
 * {@code @JsonCreator} constructor to take the synthesised member, so this one-argument call only keeps
 * compiling because the patch also adds a narrow delegating overload. The round-trip compiles the
 * patched model, making that overload a hard gate rather than an assertion about text.
 *
 * <p>It also exercises {@code builder()} on the widened class, proving Lombok still binds the builder to
 * the widened {@code @JsonCreator} constructor rather than the narrow overload.
 */
public final class BoundPartyCaller {

  private BoundPartyCaller() {
  }

  public static BoundParty positional() {
    return new BoundParty("positional");
  }

  public static BoundParty built() {
    return BoundParty.builder().boundName("built").build();
  }
}
