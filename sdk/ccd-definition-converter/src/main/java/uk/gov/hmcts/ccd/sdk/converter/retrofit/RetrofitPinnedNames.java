package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The member names the {@code CaseEventToComplexTypes} member walk resolved by an id the SDK cannot
 * re-derive, which the retrofit patch must therefore pin with an explicit {@code @JsonProperty}.
 * Keyed by owning class FQN, mapping each Java field name to the id to pin on it.
 *
 * <p>This is the mirror image of {@link RetrofitPlannedSynthesis}. There, the patch decides and the
 * graph follows; here, the graph decides and the patch follows — but the invariant is the same: the
 * two halves are one decision recorded once, never two derivations that could disagree.
 *
 * <p>The <em>id</em> is recorded alongside the name for exactly that reason. It was once left implicit,
 * the patch re-deriving it from the class's {@code @JsonNaming} strategy — which silently dropped every
 * pin whose id came from anywhere else, and dropping a pin lands in the trap below rather than merely
 * losing a row. The recorded id is the definition's own segment, so there is nothing left to re-derive.
 *
 * <p>Why it must be one decision. Both the SDK and this converter read a member's CCD id narrowly:
 * {@code PropertyUtils.getPropertyName} resolves it from {@code @JsonGetter}/{@code @JsonProperty} on
 * the read method or field, else the bean/declared field name, and {@code FieldCollection} derives
 * every {@code ListElementCode} segment through it. Two Jackson idioms name a property outside that
 * window: a class-level {@code @JsonNaming} (Civil's {@code UpperCamelCaseStrategy Address} declares
 * {@code addressLine1} but serialises as {@code AddressLine1}), and a {@code @JsonProperty} on a
 * {@code @JsonCreator} constructor parameter (fpl's immutable {@code Address}, same effect). Either way
 * the field really does appear in the definition under a name neither the SDK nor this converter would
 * derive, which leaves exactly two self-consistent outcomes, and one trap:
 *
 * <ul>
 *   <li>Resolve nothing: the row falls back to a verbatim passthrough. Correct, but 286 of Civil's
 *       326 member-not-found rows are these.</li>
 *   <li>Resolve AND pin: the walk emits {@code Address::getAddressLine1} and the patch adds
 *       {@code @JsonProperty("AddressLine1")}, so the SDK regenerates the definition's own id.</li>
 *   <li><b>The trap</b> — resolve WITHOUT pinning: the config would emit the getter and the SDK would
 *       regenerate {@code addressLine1}, silently changing the CCD field id. That is a fidelity
 *       regression strictly worse than the passthrough it replaces, and it would round-trip-diff
 *       rather than fail loudly at compile time.</li>
 * </ul>
 *
 * <p>So the graph records a name and its id here at the moment it resolves the member, and the patch
 * pins exactly what is recorded — nothing pinned that was not relied on, nothing relied on that is not
 * pinned.
 *
 * <p><b>Runtime neutrality.</b> The pin is a no-op for Jackson: a field-level {@code @JsonProperty}
 * takes precedence over both the class strategy and the creator parameter's, and the value pinned is by
 * construction the one that idiom already produces — the naming strategy's own answer
 * ({@link NamingStrategy}) or the parameter's own literal. Serialised and deserialised payloads are
 * byte-identical before and after the patch; only what the SDK generator derives changes.
 */
final class RetrofitPinnedNames {

  private final Map<String, Map<String, String>> idsByJavaNameByOwnerFqn = new LinkedHashMap<>();

  /** An empty set of pins, for generate mode and for tests that exercise no renaming idiom. */
  static RetrofitPinnedNames empty() {
    return new RetrofitPinnedNames();
  }

  /**
   * Records that the member walk resolved {@code javaName} on {@code ownerFqn} under a CCD id the SDK
   * would not derive, so the patch must pin that id.
   *
   * @param ownerFqn the FQN of the model class declaring the field
   * @param javaName the Java field name
   * @param id the id the field serialises under, i.e. the definition's own {@code ListElementCode}
   *     segment
   */
  void record(String ownerFqn, String javaName, String id) {
    idsByJavaNameByOwnerFqn
        .computeIfAbsent(ownerFqn, k -> new LinkedHashMap<>())
        .putIfAbsent(javaName, id);
  }

  /**
   * The ids to pin on a class, by Java field name.
   *
   * @param ownerFqn the owning model class FQN
   * @return field name → id to pin, empty when the walk relied on none there
   */
  Map<String, String> idsFor(String ownerFqn) {
    return idsByJavaNameByOwnerFqn.getOrDefault(ownerFqn, Map.of());
  }

  /**
   * The Java field names to pin on a class.
   *
   * @param ownerFqn the owning model class FQN
   * @return the field names, empty when the walk relied on none there
   */
  Set<String> javaNamesFor(String ownerFqn) {
    return idsFor(ownerFqn).keySet();
  }

  /**
   * Every class FQN with at least one name to pin.
   *
   * @return the owner FQNs, in the order first recorded
   */
  Set<String> ownerFqns() {
    return Collections.unmodifiableSet(idsByJavaNameByOwnerFqn.keySet());
  }

  /** Whether the walk relied on no un-derivable id at all. */
  boolean isEmpty() {
    return idsByJavaNameByOwnerFqn.isEmpty();
  }
}
