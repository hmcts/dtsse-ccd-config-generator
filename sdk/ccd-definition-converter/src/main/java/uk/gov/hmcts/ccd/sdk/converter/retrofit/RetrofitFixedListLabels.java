package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;

/**
 * Decides what to pin on each constant of a model enum that backs a definition {@code FixedLists} ID so
 * the SDK emits that list's own rows: the {@code @CCD(label = …)} carrying the definition's
 * {@code ListElement}, and the {@code @JsonProperty} carrying its {@code ListElementCode}.
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
 *   <li>the definition's label equals the code the constant emits, so the generator's fallback already
 *       emits the right value and the annotation would be noise;</li>
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
    // The code each constant will actually emit, which is what the generator's label fallback also
    // emits (it puts the CONSTANT into both columns and lets Jackson serialise it), so a code pin moves
    // the fallback with it: a constant pinned to `cherished` needs no label pin when the definition's
    // ListElement is also `cherished`, and does need one when it is `Cherished`.
    Map<String, String> emitted = emittedCodes(type, list);
    Map<String, String> pins = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      String name = constant.getNameAsString();
      String label = labelByConstant.get(name);
      if (label == null || label.equals(emitted.getOrDefault(name, name))) {
        continue; // no definition row, or the generator's own fallback already emits this label
      }
      if (Annotations.has(constant, "CCD")) {
        continue; // team-written, or this patch already applied
      }
      pins.put(name, label);
    }
    return pins;
  }

  /**
   * The constant → {@code ListElementCode} pins for one bound enum, to be emitted as
   * {@code @JsonProperty}: the codes the definition spells differently from the constants the team
   * declares.
   *
   * <p><b>Why this is what moves the code.</b> {@code FixedListGenerator} puts the enum CONSTANT into
   * the row map ({@code value.put("ListElementCode", enumConstant)}) and {@code JsonUtils} writes the
   * rows through an {@code ObjectMapper}, so the emitted code is whatever Jackson serialises the
   * constant as — the constant name by default, and the {@code @JsonProperty} value when it carries one.
   * Nothing in {@code @CCD} can express the code (its {@code hint()} javadoc claims to, but every reader
   * of {@code hint} treats it as a label, hint text or description), so the Jackson pin is the whole
   * mechanism. It needs no SDK change.
   *
   * <p><b>This is not runtime-neutral, and that is deliberate.</b> Unlike the label pin, which only the
   * generator reads, a {@code @JsonProperty} changes how the team's own enum serialises and deserialises
   * everywhere — sscs's {@code ScannedDocumentType} is a published-library type whose callers read
   * {@code .getValue()}. The pin is taken from the definition, which IS the value that type's CCD column
   * carries on the wire, so the redirect aligns the Java type with the data it already models; but it is
   * a change to a published contract and is reported as such, not slipped in as a formatting fix.
   *
   * @param type the model enum backing {@code list}
   * @param list the definition's rows for the ID this enum is pinned to
   * @return enum constant name → the {@code ListElementCode} to pin, empty when nothing needs pinning
   *     or the enum cannot safely carry one
   */
  static Map<String, String> codePins(ModelSourceIndex.Type type, FixedListModel list) {
    Map<String, String> constantByCode = matchCodes(type, list);
    if (constantByCode == null) {
      return Map.of();
    }
    Map<String, String> pins = new LinkedHashMap<>();
    constantByCode.forEach((code, constant) -> {
      if (!code.equals(constant)) {
        pins.put(constant, code);
      }
    });
    return pins;
  }

  /**
   * The code each of an enum's constants will emit once this patch is applied: the definition code it is
   * pinned to where {@link #codePins} makes a pin, else the {@code @JsonProperty} it already carries, else
   * its own name. Used to decide whether a label pin is still needed — the generator's label fallback
   * emits this same value.
   *
   * <p>An EXISTING {@code @JsonProperty} only counts when the enum actually honours it. A
   * {@code @JsonValue} takes precedence over one, so on such an enum the constant still emits its own
   * name and the definition's label is still needed — prl's {@code DocumentPartyEnum} pins
   * {@code @JsonProperty("Court")} on {@code COURT} but serialises through a {@code @JsonValue}
   * {@code getDisplayedValue()}, so the emitted code is {@code COURT} and the label {@code "Court"} must
   * be pinned. Reading the annotation blindly dropped that pin and cost a residual line.
   */
  private static Map<String, String> emittedCodes(ModelSourceIndex.Type type, FixedListModel list) {
    Map<String, String> emitted = new LinkedHashMap<>();
    if (!redirectsItsSerialisedValue(type.decl.asEnumDeclaration())) {
      for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
        Annotations.find(constant, "JsonProperty")
            .flatMap(Annotations::stringValue)
            .ifPresent(pinned -> emitted.put(constant.getNameAsString(), pinned));
      }
    }
    codePins(type, list).forEach((constant, code) -> emitted.put(constant, code));
    return emitted;
  }

  /**
   * Whether an enum can be made to emit the definition's own {@code ListElementCode}s — the test for
   * whether naming it with {@code @CCD(typeParameterClass)} reproduces a list or replaces it with a
   * differently-coded one.
   *
   * @param type the model enum a definition ID would be pinned to
   * @param list the definition's rows for that ID
   * @return true when every one of the definition's codes has a constant that emits it, or can be pinned
   *     to
   */
  static boolean canEmitTheDefinitionsCodes(ModelSourceIndex.Type type, FixedListModel list) {
    return matchCodes(type, list) != null;
  }

  /**
   * Matches every one of a list's {@code ListElementCode}s to the constant that will emit it, or
   * {@code null} to refuse the enum outright.
   *
   * <p><b>Why this has to be resolved at all.</b> {@code FixedListGenerator} derives
   * {@code ListElementCode} from the constant rather than from any annotation the SDK reads — it puts the
   * enum CONSTANT into the row map and lets Jackson serialise it. So an enum whose codes the team spells
   * in its own house style (sscs's {@code ScannedDocumentType} carries the definition's {@code cherished}
   * as a constructor field while the constant is {@code CHERISHED}) emits the wrong codes by default, and
   * naming such an enum used to be refused outright: a list of fifteen WRONG rows is worse than a list of
   * none. The Jackson serialisation is the seam — {@code @JsonProperty} on the constant redirects it —
   * so the codes are now PINNED (see {@link #codePins}) and the enum is refused only where no pin can be
   * made to work.
   *
   * <p><b>The refusals.</b>
   * <ul>
   *   <li>A {@code @JsonValue} anywhere on the enum: it takes precedence over a constant's
   *       {@code @JsonProperty}, so what the enum emits is a method's return value and nothing pinned on
   *       the constants can change it. sscs's {@code DocumentTabChoice} really emits
   *       {@code document}/{@code internalDocument} for {@code REGULAR}/{@code INTERNAL} — a name
   *       comparison would pass and the emitted list would still be wrong.</li>
   *   <li>A code with no constant to carry it. Matched on the constant's own name first, then on the
   *       sanitised {@code javaConstant} the linker derived from the code — the two spellings a team
   *       really uses ({@code cherished} → {@code CHERISHED}). No match means the enum genuinely models a
   *       different constant set, which is a divergence to report, not a code to pin.</li>
   *   <li>Two codes resolving to one constant, or a constant already carrying a {@code @JsonProperty}
   *       for a different value: either way the pin the list needs cannot be made ({@code @JsonProperty}
   *       is not {@code @Repeatable}) without contradicting something already there.</li>
   * </ul>
   *
   * <p>Every code must match, not most: a partial match emits a list that is right about some rows and
   * wrong about the rest, which is the same defect at smaller scale. Constants the definition has NO code
   * for are tolerated — they emit an extra row, which is a reported residual rather than a wrong value,
   * and every candidate enum across the lanes has some.
   *
   * <p><b>Residual assumption.</b> A class-level {@code @JsonSerialize(using = …)} (prl puts one on
   * nearly every enum) could redirect the code to anything, and what a hand-written serialiser emits is
   * not knowable from source. The constant match is still the test there — prl's
   * {@code CustomEnumSerializer} does fall through to the constant name — so this stays sound for the
   * lanes measured but is an assumption rather than a proof.
   *
   * @param type the model enum a definition ID would be pinned to
   * @param list the definition's rows for that ID
   * @return definition code → the constant that emits it, or null when the enum is refused
   */
  private static Map<String, String> matchCodes(ModelSourceIndex.Type type, FixedListModel list) {
    if (type == null || list == null || list.getItems() == null || !type.isEnum()) {
      return null;
    }
    EnumDeclaration decl = type.decl.asEnumDeclaration();
    if (redirectsItsSerialisedValue(decl)) {
      return null;
    }
    Map<String, EnumConstantDeclaration> byName = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : decl.getEntries()) {
      byName.put(constant.getNameAsString(), constant);
    }
    Map<String, String> constantByCode = new LinkedHashMap<>();
    Set<String> claimed = new LinkedHashSet<>();
    for (FixedListModel.Item item : list.getItems()) {
      String code = item.getCode();
      if (code == null || constantByCode.containsKey(code)) {
        continue;
      }
      EnumConstantDeclaration constant = byName.get(code);
      if (constant == null && item.getJavaConstant() != null) {
        constant = byName.get(item.getJavaConstant());
      }
      if (constant == null) {
        return null; // the enum models a different constant set
      }
      String name = constant.getNameAsString();
      if (!claimed.add(name)) {
        return null; // two codes, one constant: only one @JsonProperty can be pinned
      }
      if (!code.equals(name) && pinsADifferentName(constant, code)) {
        return null; // an existing @JsonProperty already says otherwise
      }
      constantByCode.put(code, name);
    }
    return constantByCode.isEmpty() ? null : constantByCode;
  }

  /**
   * Whether a constant already carries a {@code @JsonProperty} naming something other than the code the
   * list needs — the one shape where the pin cannot be added (the annotation is not
   * {@code @Repeatable}) and the existing one governs what Jackson emits. A constant already pinned to
   * this same code is not a conflict, which is what makes re-applying the patch a no-op.
   */
  private static boolean pinsADifferentName(EnumConstantDeclaration constant, String code) {
    return Annotations.find(constant, "JsonProperty")
        .map(ann -> !Annotations.stringValue(ann).filter(code::equals).isPresent())
        .orElse(false);
  }

  /**
   * Whether the enum carries a {@code @JsonValue}, on a method or a field — the annotation that makes
   * Jackson serialise the constant as something other than its name, and so makes the
   * {@code ListElementCode} the generator emits differ from the constant the definition would be
   * compared against.
   */
  private static boolean redirectsItsSerialisedValue(EnumDeclaration decl) {
    return decl.getMembers().stream()
        .anyMatch(member -> Annotations.has(member, "JsonValue"));
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
