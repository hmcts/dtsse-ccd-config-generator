package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    // The definition row behind each constant, keyed by every name the team's constant could plausibly
    // carry. The definition's ListElementCode is the constant the SDK emits, but a team writes that
    // constant in its own house style: prl spells it verbatim (`nonMolestationOrderFL401A`), civil
    // upper-snakes it (`PERSONAL_INJURY`) — which is also what the converter's own generate-mode
    // sanitiser produces. Both the raw code and the sanitised javaConstant are therefore indexed;
    // matching only the latter silently missed every camelCase-constant enum, which is most of prl.
    Map<String, FixedListModel.Item> rowByConstant = rowByConstant(list);
    // The code each constant will actually emit, which is what the generator's label fallback also
    // emits (it puts the CONSTANT into both columns and lets Jackson serialise it), so a code pin moves
    // the fallback with it: a constant pinned to `cherished` needs no label pin when the definition's
    // ListElement is also `cherished`, and does need one when it is `Cherished`.
    Map<String, String> emitted = emittedCodes(type, list);
    Map<String, String> pins = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : type.decl.asEnumDeclaration().getEntries()) {
      String name = constant.getNameAsString();
      FixedListModel.Item row = rowByConstant.get(name);
      if (row == null && emitted.containsKey(name)) {
        // A constant need not be NAMED after its code to model it: sscs's CommunicationRequestTopic
        // names APPELLANT_PERSONAL_INFORMATION and pins `appellantPersonalInfo`. Such a constant carries
        // the row, and its label diverges exactly as any other's does, so resolving by name alone
        // dropped the pin the list needed.
        row = rowByConstant.get(emitted.get(name));
      }
      String label = row == null ? null : row.getLabel();
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
    Match match = match(type, list);
    if (match == null) {
      return Map.of();
    }
    Set<String> added = new LinkedHashSet<>();
    match.toAdd().forEach(constant -> added.add(constant.name()));
    Map<String, String> pins = new LinkedHashMap<>();
    match.constantByCode().forEach((code, constant) -> {
      // An added constant carries its own pins in the block that declares it — this map is only read
      // against constants the source already declares.
      if (!code.equals(constant) && !added.contains(constant)) {
        pins.put(constant, code);
      }
    });
    return pins;
  }

  /**
   * The constants the definition has codes for that the enum does not declare, to be ADDED to it — with
   * the exact constructor argument list each must carry.
   *
   * <p><b>Why an enum may be extended at all.</b> A list whose codes the enum ALMOST covers used to be
   * refused outright ({@link #match}), because pinning only the codes that do have constants emits a
   * list right about some rows and missing the rest. But the missing row is not a mystery: the definition
   * holds the code and the label, and the SDK derives the whole list from the constant set, so the
   * faithful reproduction of a fifteen-row list by a fourteen-constant enum is a fifteenth constant. The
   * enum is the model of that CCD column; a code the column really carries and the enum cannot name is a
   * gap in the model, and closing it is what makes the list round-trip.
   *
   * <p><b>Why this is safe to compile.</b> Nothing about the constructor is inferred: the new constant
   * copies the argument SHAPE of the constants already there. Every existing constant is required to pass
   * the same number of arguments, all string literals, and the new one passes that same count — so if
   * {@code CHERISHED("cherished", "Cherished")} compiles then so does the constant beside it, whatever
   * the constructor's parameter names or Lombok's role in generating it. What each position MEANS is then
   * decided by unanimous evidence, never by position or name alone (see {@link #synthesisedArguments}).
   *
   * <p><b>What it changes for the team.</b> Adding a constant is additive at the source level, but it is
   * a change to a published type: an exhaustive {@code switch} over the enum stops compiling, and code
   * iterating {@code values()} sees one more. Both were checked for the enum this exists for (sscs's
   * {@code ScannedDocumentType}: every use is {@code getValue()} or {@code values()}, no exhaustive
   * {@code switch}), and it is reported as a model change rather than presented as a formatting fix.
   *
   * @param type the model enum backing {@code list}
   * @param list the definition's rows for the ID this enum is pinned to
   * @return the constants to declare, in the definition's own row order; empty when the enum needs no
   *     new constant or cannot take one
   */
  static List<AddedConstant> constantsToAdd(ModelSourceIndex.Type type, FixedListModel list) {
    Match match = match(type, list);
    return match == null ? List.of() : match.toAdd();
  }

  /**
   * One constant to add to a team's enum so it can name a definition code it has none for.
   *
   * @param name the constant name to declare, sanitised from the code
   * @param code the definition's {@code ListElementCode}, pinned with {@code @JsonProperty} whenever the
   *     constant name would not serialise as it
   * @param label the definition's {@code ListElement}, pinned with {@code @CCD(label)} on the same terms
   *     an existing constant's is
   * @param arguments the constructor arguments to declare it with, already rendered as Java expressions
   *     in parameter order — copied in shape from the constants already there, never inferred
   */
  record AddedConstant(String name, String code, String label, List<String> arguments) {
  }

  /**
   * What a candidate enum can be made to do for one list: which constant emits each of the definition's
   * codes, and which constants must be ADDED for the codes none does.
   */
  private record Match(Map<String, String> constantByCode, List<AddedConstant> toAdd) {
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
   * The {@code @CCD(label = …)} an ADDED constant needs, on the same terms a declared one's is decided:
   * pinned unless the code it will emit already equals the definition's label, in which case the
   * generator's own fallback emits it.
   *
   * @param added the constant being added
   * @return the label to pin, or empty when the fallback already emits it
   */
  static Optional<String> labelFor(AddedConstant added) {
    if (added.label() == null) {
      return Optional.empty();
    }
    String willEmit = added.code() == null ? added.name() : added.code();
    return added.label().equals(willEmit) ? Optional.empty() : Optional.of(added.label());
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
    return match(type, list) != null;
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
   *   <li>Two codes resolving to one constant, or a constant already carrying a {@code @JsonProperty}
   *       for a different value: either way the pin the list needs cannot be made ({@code @JsonProperty}
   *       is not {@code @Repeatable}) without contradicting something already there.</li>
   *   <li>A code with no constant to carry it, where no constant can be ADDED for it — see
   *       {@link #synthesisedArguments} for the shapes a constant can be added to and why. Matching is on
   *       the constant's own name first, then on the sanitised {@code javaConstant} the linker derived
   *       from the code, the two spellings a team really uses ({@code cherished} → {@code CHERISHED}).</li>
   * </ul>
   *
   * <p>Every code must be accounted for, not most: a partial match emits a list that is right about some
   * rows and wrong about the rest, which is the same defect at smaller scale. Constants the definition has
   * NO code for are tolerated — they emit an extra row, which is a reported residual rather than a wrong
   * value, and every candidate enum across the lanes has some.
   *
   * <p><b>Residual assumption.</b> A class-level {@code @JsonSerialize(using = …)} (prl puts one on
   * nearly every enum) could redirect the code to anything, and what a hand-written serialiser emits is
   * not knowable from source. The constant match is still the test there — prl's
   * {@code CustomEnumSerializer} does fall through to the constant name — so this stays sound for the
   * lanes measured but is an assumption rather than a proof.
   *
   * @param type the model enum a definition ID would be pinned to
   * @param list the definition's rows for that ID
   * @return the resolved match, or null when the enum is refused
   */
  private static Match match(ModelSourceIndex.Type type, FixedListModel list) {
    if (type == null || list == null || list.getItems() == null || !type.isEnum()) {
      return null;
    }
    EnumDeclaration decl = type.decl.asEnumDeclaration();
    if (redirectsItsSerialisedValue(decl)) {
      return null;
    }
    Map<String, EnumConstantDeclaration> byName = new LinkedHashMap<>();
    // And by the code each constant ALREADY emits: a team can model a code without naming its constant
    // after it, by pinning it with its own @JsonProperty (sscs's CommunicationRequestTopic names
    // APPELLANT_PERSONAL_INFORMATION and pins `appellantPersonalInfo`). That constant carries the code, so
    // resolving only by name would conclude the code had none and ADD a second constant emitting the same
    // one — two rows for one code, which is worse than the label divergence it was trying to fix.
    Map<String, EnumConstantDeclaration> byPinnedCode = new LinkedHashMap<>();
    for (EnumConstantDeclaration constant : decl.getEntries()) {
      byName.put(constant.getNameAsString(), constant);
      Annotations.find(constant, "JsonProperty")
          .flatMap(Annotations::stringValue)
          .ifPresent(pinned -> byPinnedCode.putIfAbsent(pinned, constant));
    }
    Map<String, String> constantByCode = new LinkedHashMap<>();
    List<AddedConstant> toAdd = new ArrayList<>();
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
        constant = byPinnedCode.get(code);
      }
      if (constant == null) {
        // No constant models this code. Add one where the enum's existing constants show exactly how,
        // else refuse: a code the enum can neither name nor be given a name for is a constant-set
        // divergence to report.
        AddedConstant added = plannedConstant(decl, list, item, claimed);
        if (added == null) {
          return null;
        }
        toAdd.add(added);
        claimed.add(added.name());
        constantByCode.put(code, added.name());
        continue;
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
    return constantByCode.isEmpty() ? null : new Match(constantByCode, toAdd);
  }

  /**
   * The constant to add for a definition code the enum does not model, or null to refuse it.
   *
   * <p>The name is the sanitised {@code javaConstant} the linker already derived from the code — the same
   * spelling the converter's own generate mode produces, and the spelling this matcher looks a team's
   * constants up by, so an added constant is indistinguishable from one the team had written. Refused when
   * that name is not available: it must not collide with a constant already declared (which would not
   * compile) nor with one already claimed by another code in this same list.
   *
   * <p><b>And refused when the enum already says this.</b> A constant is added to close a GAP in the
   * model — a value the CCD column carries that the enum cannot name. If a constant already there passes
   * this row's own label, the value is not missing: the team models it under another name, and the
   * definition merely spells its code differently. civil's {@code HearingLengthFinalOrderList} declares
   * nineteen constants for a six-code list, so adding {@code HOUR_1("1 hour")} beside the existing
   * {@code MINUTES_60("1 hour")} would put two ways to say one thing into a published enum and still leave
   * the seventeen extra constants emitting extra rows. That is a code-spelling divergence to report, or a
   * pin for the team to make deliberately — not a constant to synthesise.
   */
  private static AddedConstant plannedConstant(EnumDeclaration decl, FixedListModel list,
      FixedListModel.Item item, Set<String> claimed) {
    String name = item.getJavaConstant();
    if (name == null || name.isEmpty() || claimed.contains(name)) {
      return null;
    }
    for (EnumConstantDeclaration existing : decl.getEntries()) {
      if (existing.getNameAsString().equals(name)) {
        return null; // matched already if it were this code's; here it belongs to another code
      }
    }
    if (item.getLabel() != null && alreadySaysThis(decl, item.getLabel())) {
      return null;
    }
    List<String> arguments = synthesisedArguments(decl, list, item);
    return arguments == null
        ? null
        : new AddedConstant(name, item.getCode(), item.getLabel(), arguments);
  }

  /**
   * The constructor arguments an added constant must carry, or null when they cannot be established from
   * the enum's own source.
   *
   * <p><b>The shape is copied, not inferred.</b> The one thing that makes an added constant compile is
   * passing what its siblings pass: the same argument COUNT, of the same kind. So every existing constant
   * must pass the same number of arguments and every one of those must be a string literal — the shape
   * that covers the plain {@code CODE("code")} and {@code CODE("code", "Label")} idioms teams write, and
   * whose constructor (hand-written or Lombok-generated, parameters named anything) is guaranteed to
   * accept another all-strings call of that arity. Anything else is refused: a constant with a body, a
   * non-literal argument (an enum reference, a concatenation, another constant), a varargs or mixed-arity
   * call, or a no-argument enum where there is nothing to copy — for those, what a new constant would have
   * to pass is a guess, and a guess that fails to compile breaks the team's build for a residual line.
   *
   * <p><b>What each position means is decided by evidence from the enum's own constants.</b> A position is
   * the CODE when every constant the definition has a row for passes its own code there, and the LABEL
   * when most of them pass their own label (see {@link #unanimousArgument} for why the two bars differ).
   * That is a fact about the whole enum, not an inference from a parameter name or an ordinal — sscs's
   * {@code ScannedDocumentType} passes {@code ("cherished", "Cherished")} for all fourteen, so position 0
   * provably carries the code and position 1 the label. A position no rule claims is refused rather than
   * filled with something invented, unless every constant passes the empty string there, which is a value
   * to copy like any other. Getting a position wrong would not break the round-trip, which reads the
   * definition's own values through the pins — it would put a wrong value in a field of the team's model,
   * which is worse.
   */
  private static List<String> synthesisedArguments(
      EnumDeclaration decl, FixedListModel list, FixedListModel.Item item) {
    List<EnumConstantDeclaration> entries = new ArrayList<>(decl.getEntries());
    if (entries.isEmpty()) {
      return null;
    }
    int arity = entries.get(0).getArguments().size();
    if (arity == 0) {
      return null; // nothing to copy: whether the constructor takes arguments cannot be established
    }
    for (EnumConstantDeclaration entry : entries) {
      if (entry.getArguments().size() != arity
          || entry.getClassBody().isNonEmpty()
          || entry.getArguments().stream().anyMatch(a -> !(a instanceof StringLiteralExpr))) {
        return null;
      }
    }
    // Only constants the definition HAS a row for can testify about what a position means, since the
    // test is whether the constant passes ITS OWN code or label there. An enum with extra constants is
    // normal (every candidate has some) and they simply do not vote.
    Map<String, FixedListModel.Item> rowByConstant = rowByConstant(list);
    List<EnumConstantDeclaration> witnesses = entries.stream()
        .filter(entry -> rowByConstant.containsKey(entry.getNameAsString()))
        .toList();
    if (witnesses.isEmpty()) {
      return null;
    }
    List<String> arguments = new ArrayList<>();
    for (int position = 0; position < arity; position++) {
      String value = unanimousArgument(witnesses, rowByConstant, position, item);
      if (value == null) {
        return null;
      }
      arguments.add(CcdAnnotationRenderer.quote(value));
    }
    return arguments;
  }

  /**
   * Whether some constant already declared passes this label as one of its own arguments — the test for
   * "the enum already models this value, under another name". Compared case-insensitively for the same
   * reason a label vote is: what a team copied into its own source drifts in case.
   */
  private static boolean alreadySaysThis(EnumDeclaration decl, String label) {
    return decl.getEntries().stream()
        .flatMap(entry -> entry.getArguments().stream())
        .filter(StringLiteralExpr.class::isInstance)
        .map(argument -> ((StringLiteralExpr) argument).asString())
        .anyMatch(passed -> passed.equalsIgnoreCase(label));
  }

  /** The definition row each of a list's constants carries, keyed by both spellings a team may use. */
  private static Map<String, FixedListModel.Item> rowByConstant(FixedListModel list) {
    Map<String, FixedListModel.Item> byConstant = new LinkedHashMap<>();
    for (FixedListModel.Item item : list.getItems()) {
      if (item.getCode() != null) {
        byConstant.putIfAbsent(item.getCode(), item);
      }
      if (item.getJavaConstant() != null) {
        byConstant.putIfAbsent(item.getJavaConstant(), item);
      }
    }
    return byConstant;
  }

  /**
   * What one argument position of an added constant must hold: the definition's code when every witness
   * passes its own code there, its label when most witnesses pass their own label, the empty string when
   * every one passes that, else null to refuse.
   *
   * <p><b>Why the code bar is unanimity and the label bar is not.</b> A code is machine-meaningful and
   * exact — one witness passing something else means the position is not the code, so nothing less than
   * unanimity will do. A label is prose a team COPIED, and a copy drifts: sscs's
   * {@code ScannedDocumentType} passes its own label for all fourteen constants but two have drifted from
   * the definition's, one in case ({@code "Set aside Application"} for {@code "Set aside application"})
   * and one in wording ({@code "Statement of Reasons Application"} for {@code "SOR application"}).
   * Demanding unanimity there refuses an enum whose position 1 is plainly the label because one copy went
   * stale, so a majority carries it — and a position no witness fills with its own label (a template name,
   * a CSS class, a classifier) still gets none, which is the case the refusal is for. Case is ignored for
   * the same reason. None of the drift is propagated: what gets written is the DEFINITION's value, which
   * is also what the pins put in the emitted row.
   */
  private static String unanimousArgument(List<EnumConstantDeclaration> witnesses,
      Map<String, FixedListModel.Item> rowByConstant, int position, FixedListModel.Item item) {
    boolean code = true;
    boolean empty = true;
    int labelled = 0;
    for (EnumConstantDeclaration witness : witnesses) {
      Expression argument = witness.getArguments().get(position);
      String passed = ((StringLiteralExpr) argument).asString();
      FixedListModel.Item own = rowByConstant.get(witness.getNameAsString());
      code &= passed.equals(own.getCode());
      empty &= passed.isEmpty();
      if (own.getLabel() != null && passed.equalsIgnoreCase(own.getLabel())) {
        labelled++;
      }
    }
    if (empty) {
      return "";
    }
    if (code && item.getCode() != null) {
      return item.getCode();
    }
    if (labelled * 2 > witnesses.size() && item.getLabel() != null) {
      return item.getLabel();
    }
    return null;
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
