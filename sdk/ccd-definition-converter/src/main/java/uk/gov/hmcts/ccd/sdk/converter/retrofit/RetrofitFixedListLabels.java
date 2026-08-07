package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;

/**
 * Decides the {@code @CCD(label = …)} to pin on each constant of a model enum that backs a definition
 * {@code FixedLists} ID, so the SDK emits the definition's own {@code ListElement} rather than the
 * constant name.
 *
 * <p><b>The divergence.</b> {@code FixedListGenerator} resolves a constant's {@code ListElement}
 * through one contract — {@code HasLabel.getLabel()}, then {@code @CCD(label)}, then {@code @CCD(hint)},
 * then the constant itself. Not one enum across the six retrofit lanes implements {@code HasLabel},
 * yet 430 of them carry a display label by some other means: prl's {@code getDisplayedValue()} behind
 * a {@code @JsonValue}, fpl's {@code getLabel(Language)} (arity 1, so not the interface method), a bare
 * {@code label}/{@code value}/{@code description} constructor field. The generator sees none of those
 * and falls through to the constant name, so every such list emits
 * {@code ListElement == ListElementCode} — the single largest bucket in the retrofit residual
 * (≈2,205 lines: prl 949, civil 546, fpl 482, sscs 210, probate 18).
 *
 * <p><b>Why the label is read off the definition, not the enum.</b> The obvious fix — teach the
 * generator (or the patch) to call whichever accessor the team happens to carry — has to GUESS which
 * member is the CCD label among several plausible ones, and guessing wrong writes a wrong label into
 * the definition silently. The converter already holds the answer: the definition's own
 * {@code ListElement} for that {@code (ID, ListElementCode)} is exactly the string the round-trip must
 * reproduce. So the label is copied from the definition and pinned per constant, and the team's
 * accessor is never consulted. That makes the pin correct by construction rather than by inference,
 * and needs no SDK change at all: {@code @CCD} carries no {@code @Target}, and
 * {@code FixedListGenerator} already reads {@code c.getField(name).getAnnotation(CCD.class)}.
 *
 * <p><b>Refusals.</b> A constant is left alone when
 * <ul>
 *   <li>it already carries a {@code @CCD} annotation — team-written, or this patch already applied;</li>
 *   <li>the definition's label equals the constant name, so the generator's fallback already emits
 *       the right value and the annotation would be noise;</li>
 *   <li>the enum implements {@code HasLabel} — the generator reads that FIRST, so a pinned
 *       {@code @CCD(label)} would be shadowed and the patch would claim a fix it did not make;</li>
 *   <li>the definition has no row for the constant, which is a genuine constant-set divergence
 *       (reported elsewhere) and not a label problem.</li>
 * </ul>
 */
final class RetrofitFixedListLabels {

  private RetrofitFixedListLabels() {
  }

  /**
   * The constant → definition label pins for one bound enum.
   *
   * @param type the model enum backing {@code list}
   * @param list the definition's rows for the ID this enum is pinned to
   * @return enum constant name → the {@code ListElement} to pin, empty when nothing needs pinning
   */
  static Map<String, String> pins(ModelSourceIndex.Type type, FixedListModel list) {
    if (type == null || list == null || list.getItems() == null || !type.isEnum()) {
      return Map.of();
    }
    // HasLabel wins in the generator's own resolution order, so a pin here would never be read.
    if (implementsHasLabel(type.decl.asEnumDeclaration())) {
      return Map.of();
    }
    // The definition's label, keyed by every name the team's constant could plausibly carry. The
    // definition's ListElementCode is the constant the SDK emits, but a team writes that constant in
    // its own house style: prl spells it verbatim (`nonMolestationOrderFL401A`), civil upper-snakes it
    // (`PERSONAL_INJURY`) — which is also what the converter's own generate-mode sanitiser produces.
    // Both the raw code and the sanitised javaConstant are therefore indexed; matching only the latter
    // silently missed every camelCase-constant enum, which is most of prl.
    Map<String, String> labelByConstant = new LinkedHashMap<>();
    for (FixedListModel.Item item : list.getItems()) {
      if (item.getLabel() == null) {
        continue;
      }
      if (item.getCode() != null) {
        labelByConstant.putIfAbsent(item.getCode(), item.getLabel());
      }
      if (item.getJavaConstant() != null) {
        labelByConstant.putIfAbsent(item.getJavaConstant(), item.getLabel());
      }
    }
    Map<String, String> pins = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      String name = constant.getNameAsString();
      String label = labelByConstant.get(name);
      if (label == null || label.equals(name)) {
        continue; // no definition row, or the generator's constant-name fallback already matches
      }
      if (Annotations.has(constant, "CCD")) {
        continue; // team-written, or this patch already applied
      }
      pins.put(name, label);
    }
    return pins;
  }

  /**
   * Whether the enum declares {@code HasLabel} among its implemented types. Matched on the simple name
   * so an import or a fully-qualified reference both count.
   */
  private static boolean implementsHasLabel(EnumDeclaration decl) {
    return decl.getImplementedTypes().stream()
        .map(t -> t.getNameAsString())
        .anyMatch("HasLabel"::equals);
  }

  /**
   * The definition's fixed list for an ID, by exact ID match.
   *
   * @param lists the linked model's fixed lists
   * @param id the {@code FixedLists} sheet ID
   * @return the list, or empty when the definition declares no such ID
   */
  static Optional<FixedListModel> byId(Iterable<FixedListModel> lists, String id) {
    if (lists == null || id == null) {
      return Optional.empty();
    }
    for (FixedListModel list : lists) {
      if (id.equals(list.getId())) {
        return Optional.of(list);
      }
    }
    return Optional.empty();
  }
}
