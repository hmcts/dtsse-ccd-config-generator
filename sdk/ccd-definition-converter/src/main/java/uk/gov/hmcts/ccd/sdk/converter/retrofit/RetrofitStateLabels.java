package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.model.StateModel;

/**
 * Decides the {@code @CCD(label = …, hint = …, description = …)} to pin on each constant of the team's
 * reused {@code State} enum, so the SDK emits the definition's own {@code State} sheet
 * {@code Name}/{@code TitleDisplay}/{@code Description} rather than the state ID three times over.
 *
 * <p><b>The divergence.</b> {@code StateGenerator} resolves all three columns off {@code @CCD} on the
 * constant — {@code label()} → {@code Name} (falling back to the state ID), {@code description()} →
 * {@code Description} (falling back to the resolved {@code Name}), {@code hint()} →
 * {@code TitleDisplay} (omitted entirely when empty). A team's own State enum carries none of those:
 * sscs spells its display names in a separate lookup, civil's are only in the definition. So every
 * reused state emitted {@code Name == Description == <the state ID>} and no {@code TitleDisplay} at
 * all — 186 residual lines across the four lanes with a reusable enum (civil 57, sscs 63, prl 44,
 * fpl 22), almost exactly 62 states × the three columns.
 *
 * <p><b>Why the values come from the definition.</b> Same reasoning as
 * {@link RetrofitFixedListLabels}: the definition's own {@code State} row IS the string the round-trip
 * must reproduce, so copying it is correct by construction, where inferring a display name from
 * whatever accessor the team happens to carry would be a guess. And as there, no SDK change is needed —
 * {@code @CCD} declares no {@code @Target} and {@code StateGenerator.enumToJsonMap} already reads the
 * annotation off {@code enumType.getField(constant.name())}.
 *
 * <p><b>Constant name vs state ID.</b> The definition keys states by CCD ID, which is not the Java
 * constant name when the enum carries its ID separately — sscs writes {@code APPEAL_CREATED("appealCreated")}
 * behind a {@code @JsonValue toString()}. The mapping is {@link StateEnumAnalyser#stateIdToConstant},
 * the same derivation the emitted config references its constants through, so the pin and the config
 * can never disagree about which constant a definition state is.
 *
 * <p><b>Refusals.</b> A constant is left alone when
 * <ul>
 *   <li>it already carries a {@code @CCD} annotation — team-written, or this patch already applied
 *       ({@code @CCD} is not {@code @Repeatable}, so a second one would not compile);</li>
 *   <li>the definition has no state row for it, which is a constant-set divergence and not a label
 *       problem — {@link #ignorePins} pins {@code ignore = true} on those instead (sscs's
 *       {@code unknown}, civil's 18 unused states);</li>
 *   <li>every column already matches the fallback the generator produces unaided — a {@code Name} equal
 *       to the state ID, a {@code Description} equal to the resolved {@code Name}, an absent
 *       {@code TitleDisplay} — in which case the annotation would be pure noise.</li>
 * </ul>
 * Pins are computed only for a state enum the run actually REUSES: when the team's enum conflicts on
 * any definition state the converter generates a fresh {@code State} enum which carries these labels
 * itself ({@code EnumEmitter}), and the team's enum is then just another model type whose constants
 * must not be touched.
 *
 * <p><b>Sharing a constant with the fixed-list pin.</b> A State enum is frequently ALSO reachable as a
 * declared field type (sscs declares {@code private State state}, so reflection emits a
 * {@code FixedLists/State} the definition never asked for), and both passes would want the same
 * constant. Only one {@code @CCD} per constant compiles, so they share a single per-constant claim and
 * this pass takes it: the {@code State} sheet's three columns are always compared, whereas a
 * definition-less fixed list's {@code ListElement} is an unexpected row whichever value it carries.
 */
final class RetrofitStateLabels {

  private RetrofitStateLabels() {
  }

  /**
   * The constant → {@code @CCD} member assignments for one reused State enum.
   *
   * @param type the model enum being reused as the case type's State
   * @param states the definition's State sheet rows, as the linker modelled them
   * @param constantByStateId CCD state ID → Java constant name, from {@link StateEnumAnalyser}
   * @return constant name → the {@code @CCD} members to pin, empty when nothing needs pinning
   */
  static Map<String, List<String>> pins(ModelSourceIndex.Type type, List<StateModel> states,
      Map<String, String> constantByStateId) {
    if (type == null || states == null || constantByStateId == null || !type.isEnum()) {
      return Map.of();
    }
    Map<String, StateModel> byConstant = new LinkedHashMap<>();
    for (StateModel state : states) {
      String constant = constantByStateId.get(state.getId());
      if (constant != null) {
        byConstant.putIfAbsent(constant, state);
      }
    }
    Map<String, List<String>> pins = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      String name = constant.getNameAsString();
      StateModel state = byConstant.get(name);
      if (state == null) {
        continue; // no definition row: a constant-set divergence, not a label one
      }
      if (Annotations.has(constant, "CCD")) {
        continue; // team-written, or this patch already applied
      }
      List<String> members = members(state);
      if (!members.isEmpty()) {
        pins.put(name, members);
      }
    }
    return pins;
  }

  /**
   * The {@code @CCD(ignore = true)} pins for constants the definition has NO state row for — the other
   * half of the same divergence {@link #pins} abstains on.
   *
   * <p>{@code StateGenerator} emits one {@code State} row per enum constant, so a team enum carrying a
   * constant no case type declares emits a state the definition never had: sscs's {@code unknown} (an
   * {@code @JsonEnumDefaultValue} sentinel) and {@code withdrawnRevisedStruckOutLapsedState} (a legacy
   * composite), civil's 18. Deleting them is not open to the converter — the team's own code switches on
   * them — and there is no per-case-type filter, so the constant must declare that it contributes nothing
   * to the definition. That is exactly {@code @CCD(ignore = true)}, which
   * {@code StateGenerator.isIgnored} honours (and which also drops the constant's
   * {@code AuthorisationCaseState} rows, since a grant on a non-existent state fails to import).
   *
   * <p>Refused for a constant already carrying a {@code @CCD} — team-written, or this patch already
   * applied, and {@code @CCD} is not {@code @Repeatable} so a second would not compile. Nothing here can
   * collide with {@link #pins}: the two partition the constants on whether the definition has a row.
   *
   * @param type the model enum being reused as the case type's State
   * @param states the definition's State sheet rows, as the linker modelled them
   * @param constantByStateId CCD state ID → Java constant name, from {@link StateEnumAnalyser}
   * @return constant name → the {@code @CCD} members to pin, empty when every constant has a state row
   */
  static Map<String, List<String>> ignorePins(ModelSourceIndex.Type type, List<StateModel> states,
      Map<String, String> constantByStateId) {
    if (type == null || states == null || constantByStateId == null || !type.isEnum()) {
      return Map.of();
    }
    Set<String> declaredConstants = new LinkedHashSet<>();
    for (StateModel state : states) {
      String constant = constantByStateId.get(state.getId());
      if (constant != null) {
        declaredConstants.add(constant);
      }
    }
    // An empty definition State sheet is not evidence that every constant is unused — it means the
    // states were not read (or resolved) at all, and ignoring the whole enum would erase the sheet.
    if (declaredConstants.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> pins = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      String name = constant.getNameAsString();
      if (declaredConstants.contains(name) || Annotations.has(constant, "CCD")) {
        continue;
      }
      pins.put(name, List.of("ignore = true"));
    }
    return pins;
  }

  /**
   * The {@code @CCD} members carrying one state's three columns, in {@code EnumEmitter}'s own order so
   * a retrofitted constant reads identically to a generated one. Each is omitted when the generator's
   * unaided fallback already produces the definition's value.
   */
  private static List<String> members(StateModel state) {
    List<String> members = new ArrayList<>();
    String name = state.getName();
    // Name falls back to the state ID, so a Name equal to it needs no pin.
    boolean hasLabel = name != null && !name.isEmpty() && !name.equals(state.getId());
    if (hasLabel) {
      members.add("label = " + CcdAnnotationRenderer.quote(name));
    }
    // TitleDisplay has no fallback at all — the column is simply absent without a hint.
    String titleDisplay = state.getTitleDisplay();
    if (titleDisplay != null && !titleDisplay.isEmpty()) {
      members.add("hint = " + CcdAnnotationRenderer.quote(titleDisplay));
    }
    // Description falls back to the RESOLVED Name (the pinned label when there is one, else the ID),
    // matching StateGenerator, so the comparison is against that rather than against the raw Name.
    String resolvedName = hasLabel ? name : state.getId();
    String description = state.getDescription();
    if (description != null && !description.isEmpty() && !description.equals(resolvedName)) {
      members.add("description = " + CcdAnnotationRenderer.quote(description));
    }
    return members;
  }
}
