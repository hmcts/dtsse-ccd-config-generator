package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;

/**
 * Decides, for each model field the patch is about to configure, whether one {@code @CCD} on the
 * field's own declaration says what the definition needs — or whether the declaration is INHERITED
 * and the definition says different things about it per subclass, in which case the subclasses that
 * diverge carry their own class-level {@code @CCD(member = "…")} instead.
 *
 * <p>A field declared once on a shared superclass is one Java member but several CCD members: it
 * emits a row under every complex type that reaches it, and again as a {@code CaseField} row when a
 * subclass is held {@code @JsonUnwrapped}. A hand-written definition configures those rows
 * independently. sscs's abstract {@code Entity} declares {@code identity}/{@code name}/
 * {@code address}/{@code contact}/{@code organisation} for {@code Appellant}, {@code Appointee},
 * {@code OtherParty}, {@code Representative} and {@code JointParty} alike, and the definition:
 * <ul>
 *   <li>puts {@code FieldShowCondition="hasRepresentative=\"Yes\""} on {@code representative}'s five
 *       rows and on no others;</li>
 *   <li>labels {@code otherParty}'s {@code role} "Other Party Role" and {@code appellant}'s
 *       "Appellant Role";</li>
 *   <li>has no field at all for the {@code Party} members that {@code JointParty} — held
 *       {@code @JsonUnwrapped} with no prefix, so they flatten to top-level {@code CaseField} rows —
 *       would otherwise contribute.</li>
 * </ul>
 * One annotation on {@code Entity.name} cannot say that; it says one thing for every subclass at
 * once. Which is why {@code CCD#member()} exists, and why this class exists to aim it.
 *
 * <p>Every claim the emitter would make about a field is routed through here first, so the decision
 * is taken once with all of them in hand rather than per reaching class in walk order (where the
 * last complex type simply overwrote the others, and whichever show condition it happened to carry
 * became every subclass's).
 *
 * <p><b>The base claim stays on the field.</b> Only the divergent reachers are moved to class-level
 * overrides, so a definition that says the same thing everywhere emits exactly the single field
 * annotation it always did, and a re-run of the patch is a no-op. The base is the claim of the
 * reacher that DECLARES the field when there is one (its claim cannot be overridden — an override
 * must name an inherited member), else the most-claimed signature, ties broken by FQN so the choice
 * is deterministic across runs.
 */
final class RetrofitInheritedMembers {

  private final CcdAnnotationRenderer renderer;
  private final Map<String, List<Claim>> byDeclaration = new LinkedHashMap<>();

  RetrofitInheritedMembers(CcdAnnotationRenderer renderer) {
    this.renderer = renderer;
  }

  /**
   * One reaching class's claim about one field: the configuration the definition gives that field's
   * row(s) as reached through {@link #reachedThroughFqn}, or — with a null {@link #field} — that the
   * definition has no row for it there at all.
   */
  record Claim(String ownerFqn, Path ownerFile, String memberName, String reachedThroughFqn,
               FieldModel field, String renameTo) {

    boolean isIgnore() {
      return field == null;
    }
  }

  /**
   * What the emitter should write for one field: the single annotation on its own declaration, plus
   * the class-level overrides for the reachers whose claim differs from it.
   */
  record Decision(Claim base, List<Claim> overrides) {
  }

  /**
   * Records that {@code reachedThrough} needs this field carrying {@code field}'s configuration.
   *
   * @param property the resolved property, whose declaring and reached-through classes decide
   *                 whether the claim can be scoped at all
   * @param field the definition metadata for the row(s) reached through that class
   * @param renameTo the {@code @JsonProperty} id to pin, or null
   */
  void annotate(ResolvedProperty property, FieldModel field, String renameTo) {
    claim(new Claim(property.ownerFqn, property.ownerFile, property.memberName,
        property.reachedThroughFqn, field, renameTo));
  }

  /**
   * Records a claim about a field that was NOT resolved as a property: the declaration a definition-only
   * member was ADOPTED onto, because the class already declares the very field the member describes (see
   * {@code RetrofitPatchEmitter#adoptExistingMember}).
   *
   * <p>Taken as raw pieces rather than a {@link ResolvedProperty} because there is no resolved property
   * to hand — the whole reason the member reached synthesis is that {@code PropertyResolver} matched
   * nothing for its id. Reached-through is the declaring class itself: the identity proof came from the
   * field's own annotations, not from the path its type was reached by.
   *
   * @param declaringType the parsed class declaring the existing field
   * @param memberName the Java field name
   * @param field the definition metadata the field must carry
   * @param renameTo the {@code @JsonProperty} id to pin
   */
  void annotateDeclared(ModelSourceIndex.Type declaringType, String memberName, FieldModel field,
      String renameTo) {
    claim(new Claim(declaringType.fqn, declaringType.file, memberName, declaringType.fqn, field,
        renameTo));
  }

  /**
   * Records that the definition has no row for this field as reached through
   * {@code property.reachedThroughFqn} — {@code @CCD(ignore = true)} on the declaration when that
   * holds everywhere, and a scoped {@code @CCD(member, ignore = true)} when it does not.
   *
   * @param property the resolved property
   */
  void ignore(ResolvedProperty property) {
    claim(new Claim(property.ownerFqn, property.ownerFile, property.memberName,
        property.reachedThroughFqn, null, null));
  }

  private void claim(Claim claim) {
    List<Claim> claims =
        byDeclaration.computeIfAbsent(key(claim.ownerFqn(), claim.memberName()),
            k -> new ArrayList<>());
    for (int i = 0; i < claims.size(); i++) {
      if (!claims.get(i).reachedThroughFqn().equals(claim.reachedThroughFqn())) {
        continue;
      }
      // One class claiming the same field twice — it was reached both as a complex-type member and as
      // a case field, or two definition complex types bind to it. Resolved exactly as the per-file
      // edits always have: a row that exists beats one that does not ("annotate wins"), and between
      // two rows the later claim replaces the earlier (a map put).
      if (!claim.isIgnore()) {
        claims.set(i, claim);
      }
      return;
    }
    claims.add(claim);
  }

  /**
   * The decisions for every field claimed, in claim order.
   *
   * @return one decision per declaration
   */
  List<Decision> decisions() {
    List<Decision> decisions = new ArrayList<>();
    for (List<Claim> claims : byDeclaration.values()) {
      decisions.add(decide(claims));
    }
    return decisions;
  }

  private Decision decide(List<Claim> claims) {
    if (claims.size() == 1) {
      return new Decision(claims.get(0), List.of());
    }
    Claim base = baseOf(claims);
    String baseSignature = signature(base);
    List<Claim> overrides = new ArrayList<>();
    for (Claim claim : claims) {
      if (claim != base && !signature(claim).equals(baseSignature)) {
        overrides.add(claim);
      }
    }
    return new Decision(base, overrides);
  }

  /**
   * The claim that stays on the field's own declaration: the declaring class's own claim when it is
   * among the reachers (an override may only name an INHERITED member, so that claim cannot be moved
   * off the field), else the most-claimed configuration — leaving the fewest overrides to write.
   */
  private Claim baseOf(List<Claim> claims) {
    for (Claim claim : claims) {
      if (claim.reachedThroughFqn().equals(claim.ownerFqn())) {
        return claim;
      }
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Claim claim : claims) {
      counts.merge(signature(claim), 1, Integer::sum);
    }
    String winner = counts.entrySet().stream()
        .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
        .map(Map.Entry::getKey)
        .orElseThrow();
    return claims.stream()
        .filter(c -> signature(c).equals(winner))
        .min(Comparator.comparing(Claim::reachedThroughFqn))
        .orElseThrow();
  }

  /**
   * What a claim actually asks the patch to write, as a comparable string: two claims with the same
   * signature need no override between them however they were reached. Built from the RENDERED
   * annotation members rather than the {@link FieldModel}, because that is what lands in the source
   * — two models differing only in a field the renderer ignores are the same claim.
   */
  private String signature(Claim claim) {
    if (claim.isIgnore()) {
      return "ignore";
    }
    return "ccd(" + String.join(",", renderer.renderMembers(claim.field())) + ")"
        + "@" + claim.renameTo();
  }

  /**
   * The {@code @CCD(...)} members for a class-level override: the field's own rendered configuration
   * with {@code member} named first, or {@code member} + {@code ignore = true} when the definition
   * has no row for the member as reached through that class.
   *
   * <p>The override REPLACES the field's own annotation for this class, so it must carry the whole
   * configuration — which is exactly the same member list the field-level form would have.
   *
   * @param claim the diverging claim
   * @return the annotation members, {@code member} first
   */
  List<String> overrideMembers(Claim claim) {
    List<String> members = new ArrayList<>();
    members.add("member = " + CcdAnnotationRenderer.quote(claim.memberName()));
    if (claim.isIgnore()) {
      members.add("ignore = true");
      return members;
    }
    members.addAll(renderer.renderMembers(claim.field()));
    return members;
  }

  /**
   * Whether a claim's {@code @CCD} would reference {@code FieldType} (so the patch adds its import).
   *
   * @param claim the claim
   * @return true when the claim carries a {@code typeOverride}
   */
  boolean usesFieldType(Claim claim) {
    return !claim.isIgnore() && renderer.usesFieldType(claim.field());
  }

  /**
   * The access classes a claim's {@code @CCD} references by simple name, for the imports the patch
   * must add alongside it.
   *
   * @param claim the claim
   * @return the access class simple names, empty when none
   */
  Set<String> accessClasses(Claim claim) {
    if (claim.isIgnore() || claim.field().getAccessClassNames() == null) {
      return Set.of();
    }
    return new LinkedHashSet<>(claim.field().getAccessClassNames());
  }

  private static String key(String ownerFqn, String memberName) {
    return ownerFqn + "#" + memberName;
  }
}
