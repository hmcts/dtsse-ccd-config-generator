package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
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
    if (type == null || list == null || list.getItems() == null || !type.isEnum()) {
      return List.of();
    }
    if (redirectsItsSerialisedValue(type.decl.asEnumDeclaration())) {
      return constantsToAddToSerialisedEnum(type.decl.asEnumDeclaration(), list);
    }
    Match match = match(type, list);
    return match == null ? List.of() : match.toAdd();
  }

  /**
   * The constants to add to a {@code @JsonValue} enum — the one shape {@link #match} refuses outright and
   * so used to answer this question with nothing.
   *
   * <p><b>Why the refusal must not reach here.</b> {@code match} refuses a {@code @JsonValue} enum
   * because no {@code @JsonProperty} pin can move what such an enum emits, so the CODES it emits are
   * whatever they are. That is a fact about pinning, not about the constant SET: sscs's
   * {@code InterlocReviewState} emits its {@code ccdDefinition} constructor field, which really does carry
   * seven of {@code FL_interlocWorkflow}'s eight codes exactly — and has no constant at all for
   * {@code interlocutoryReview}, so the SDK emits a seven-row list where CCD has eight. Routing the add
   * decision through the pin refusal cost that row, and adding the constant is what recovers it: no pin is
   * needed, because the constant carries its code the same way its siblings do — in the constructor
   * argument the {@code @JsonValue} returns.
   *
   * <p><b>Which codes are already covered is read from the ARGUMENTS, not from constant names.</b> On such
   * an enum the name says nothing about the row — sscs's {@code SendToFirstTierActions} names
   * {@code DECISION_REMADE} for the code {@code remade} — so concluding from the names that a code has no
   * constant would add a second constant emitting a code the enum already emits, two rows for one code.
   * The code-carrying position is resolved the same way {@link #serialisedCodesFromArguments} resolves it,
   * with the one difference that it need not cover EVERY code: a code with no constant is precisely the
   * case here, so full coverage is a bar the enum this exists for can never meet. Refusing when no position
   * qualifies is what keeps this sound — an added constant's code argument is only right if the position it
   * goes in is the one the {@code @JsonValue} returns.
   *
   * <p>Each candidate then goes through {@link #plannedConstant} on the same terms as any other, so the
   * argument shape is copied rather than inferred and a code the enum already models under another name is
   * left alone — with the one difference that no {@code @JsonProperty} is planned.
   */
  private static List<AddedConstant> constantsToAddToSerialisedEnum(
      EnumDeclaration decl, FixedListModel list) {
    Map<String, String> emitted = codesFromArguments(decl, list, false);
    if (emitted.isEmpty()) {
      return List.of(); // no position provably carries the codes: what a new constant would pass is a guess
    }
    Set<String> covered = new LinkedHashSet<>(emitted.values());
    List<AddedConstant> toAdd = new ArrayList<>();
    Set<String> claimed = new LinkedHashSet<>();
    for (FixedListModel.Item item : list.getItems()) {
      if (item.getCode() == null || !covered.add(item.getCode())) {
        continue;
      }
      AddedConstant added = plannedConstant(decl, list, item, claimed, false);
      if (added == null) {
        continue; // this code cannot be named; the missing row stays a reported residual
      }
      toAdd.add(added);
      claimed.add(added.name());
    }
    return toAdd;
  }

  /**
   * One constant to add to a team's enum so it can name a definition code it has none for.
   *
   * @param name the constant name to declare, sanitised from the code
   * @param pinnedCode the definition's {@code ListElementCode} to write as {@code @JsonProperty}, or null
   *     when no pin is needed or possible — the constant name already serialises as the code, or the enum
   *     emits a {@code @JsonValue} that no pin can move (there the code rides in the constructor
   *     argument instead, see {@link #constantsToAddToSerialisedEnum})
   * @param emittedCode the code this constant will actually emit once declared, which is what decides
   *     whether its label needs pinning ({@link #labelFor})
   * @param label the definition's {@code ListElement}, pinned with {@code @CCD(label)} on the same terms
   *     an existing constant's is
   * @param arguments the constructor arguments to declare it with, already rendered as Java expressions
   *     in parameter order — copied in shape from the constants already there, never inferred
   */
  record AddedConstant(String name, String pinnedCode, String emittedCode, String label,
      List<String> arguments) {
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
    EnumDeclaration decl = type.decl.asEnumDeclaration();
    if (redirectsItsSerialisedValue(decl)) {
      emitted.putAll(serialisedCodesFromArguments(decl, list));
    } else {
      for (EnumConstantDeclaration constant : decl.getEntries()) {
        Annotations.find(constant, "JsonProperty")
            .flatMap(Annotations::stringValue)
            .ifPresent(pinned -> emitted.put(constant.getNameAsString(), pinned));
      }
    }
    codePins(type, list).forEach((constant, code) -> emitted.put(constant, code));
    return emitted;
  }

  /**
   * The code each constant of a {@code @JsonValue} enum emits, resolved from the constructor argument
   * position that provably carries the definition's own codes.
   *
   * <p><b>Why this is needed.</b> A {@code @JsonValue} enum emits a constructor field, not the constant,
   * so nothing about the constant's NAME says which row it carries — sscs's
   * {@code SendToFirstTierActions} names {@code DECISION_REMADE} and emits {@code remade} through
   * {@code @JsonValue toString()}. Its codes are therefore already right and it needs no code pin (which
   * is why {@link #match} refuses it), but its LABELS still fall back to the code and the list emits
   * {@code ListElement == ListElementCode}. Resolving the row per constant is the whole of what the label
   * pin needs.
   *
   * <p><b>Resolved by evidence, not by name or ordinal.</b> A position qualifies only when the string
   * literals passed there map constants to the definition's codes ONE-TO-ONE and cover EVERY code — that
   * position is then literally the code column, whatever the field or parameter behind it is called.
   * Extra constants passing something that is not a code are normal (they emit an extra row, a reported
   * residual) and simply carry no row. Two positions that qualify but DISAGREE about which constant emits
   * which code refuse the enum rather than pick one.
   *
   * <p>Nothing wrong can be written even where the {@code @JsonValue} returns some other field: the pin
   * only ever sets {@code ListElement} to the definition's own label for the resolved row, and
   * {@code ListElementCode} is not something a pin can move on such an enum.
   */
  private static Map<String, String> serialisedCodesFromArguments(
      EnumDeclaration decl, FixedListModel list) {
    return codesFromArguments(decl, list, true);
  }

  /**
   * The constant → code mapping the enum's own arguments establish.
   *
   * @param decl the enum
   * @param list the definition's rows for the ID it backs
   * @param mustCoverEveryCode whether a position qualifies only when every one of the definition's codes is
   *     passed by some constant. True for reading what the enum EMITS today ({@link
   *     #serialisedCodesFromArguments}): a position covering only half the list is as likely to be some
   *     other field that happens to collide, and a wrong reading there mis-pins labels. False for deciding
   *     what to ADD ({@link #constantsToAddToSerialisedEnum}), where a code no constant passes is the whole
   *     point and full coverage is unmeetable by construction.
   */
  private static Map<String, String> codesFromArguments(
      EnumDeclaration decl, FixedListModel list, boolean mustCoverEveryCode) {
    List<EnumConstantDeclaration> entries = new ArrayList<>(decl.getEntries());
    if (entries.isEmpty()) {
      return Map.of();
    }
    int arity = entries.get(0).getArguments().size();
    if (arity == 0) {
      return Map.of(); // nothing is passed, so no position can carry the code
    }
    for (EnumConstantDeclaration entry : entries) {
      if (entry.getArguments().size() != arity || entry.getClassBody().isNonEmpty()) {
        return Map.of(); // mixed arity or a constant body: positions are not comparable
      }
    }
    Set<String> codes = new LinkedHashSet<>();
    for (FixedListModel.Item item : list.getItems()) {
      if (item.getCode() != null) {
        codes.add(item.getCode());
      }
    }
    if (codes.isEmpty()) {
      return Map.of();
    }
    Map<String, String> resolved = null;
    for (int position = 0; position < arity; position++) {
      Map<String, String> candidate =
          codeCarryingPosition(entries, position, codes, mustCoverEveryCode);
      if (candidate == null) {
        continue;
      }
      if (resolved != null && !resolved.equals(candidate)) {
        return Map.of();
      }
      resolved = candidate;
    }
    return resolved == null ? Map.of() : resolved;
  }

  /**
   * The constant → code mapping one argument position establishes, or null when that position is not the
   * code column: a code passed by two constants (not one-to-one), or — when {@code mustCoverEveryCode} —
   * a code no constant passes. With that bar relaxed the position must still be claimed by at least one
   * constant, so a position holding nothing from this list at all never qualifies.
   */
  private static Map<String, String> codeCarryingPosition(List<EnumConstantDeclaration> entries,
      int position, Set<String> codes, boolean mustCoverEveryCode) {
    Map<String, String> byConstant = new LinkedHashMap<>();
    Set<String> claimed = new LinkedHashSet<>();
    for (EnumConstantDeclaration entry : entries) {
      String passed = literalToken(entry.getArguments().get(position));
      if (passed == null || !codes.contains(passed)) {
        continue;
      }
      if (!claimed.add(passed)) {
        return null;
      }
      byConstant.put(entry.getNameAsString(), passed);
    }
    if (mustCoverEveryCode) {
      return claimed.containsAll(codes) ? byConstant : null;
    }
    return claimed.isEmpty() ? null : byConstant;
  }

  /**
   * The literal an argument passes, as its SOURCE TOKEN, or null when it is not a literal this can read.
   *
   * <p>Numeric literals count, not only strings. sscs's {@code AdjournCaseDaysOffset} declares
   * {@code TWENTY_EIGHT_DAYS(28, "28 days")} — an {@code Integer ccdDefinition} behind a
   * {@code @JsonValue toString()} returning {@code String.valueOf(ccdDefinition)} — against a list whose
   * codes are {@code 0}/{@code 14}/{@code 21}/{@code 28} and whose labels are {@code Other}/
   * {@code 14 days}/…. Reading only {@code StringLiteralExpr} found no code-carrying position on such an
   * enum, so no constant resolved to a row and every label pin was dropped: eight residual lines across
   * that enum and {@code AdjournCaseNextHearingPeriod}.
   *
   * <p><b>Why the source token rather than the parsed value.</b> The comparison is against the
   * definition's {@code ListElementCode}, which is text, and what a {@code @JsonValue} returning
   * {@code String.valueOf(n)} emits is the token as written. sscs's {@code BenefitCode} is the case that
   * makes this matter: it passes {@code UC(1, …)} against definition codes spelled {@code 001}, and the
   * parsed values would match while the emitted code would be {@code 1}. Comparing tokens refuses that
   * enum, which is right — no pin on it can make the SDK emit {@code 001}.
   */
  private static String literalToken(Expression argument) {
    if (argument instanceof StringLiteralExpr string) {
      return string.asString();
    }
    if (argument instanceof IntegerLiteralExpr integer) {
      return integer.getValue();
    }
    if (argument instanceof LongLiteralExpr number) {
      return number.getValue();
    }
    return null;
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
    String willEmit = added.emittedCode() == null ? added.name() : added.emittedCode();
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
   * Whether an enum reproduces a list EXACTLY — every one of the definition's codes emitted, and no row
   * the definition does not have.
   *
   * <p>{@link #canEmitTheDefinitionsCodes} asks only the first half, because that is the whole question
   * for {@code @CCD(typeParameterClass)}: naming an enum from a field makes a list REACHABLE that had no
   * rows at all, so a superset emits the definition's rows plus some extras — strictly better than the
   * nothing it replaces, and the extras are a reported residual. Pinning
   * {@code @ComplexType(name = <id>)} onto a team enum is a different bargain: the pin DECIDES which enum
   * is the list, so a superset is not an improvement on nothing, it is the definitive answer being wrong.
   *
   * <p><b>Why the constant set has to match, not merely cover.</b> {@code FixedListGenerator} emits one
   * row per enum constant and offers no filter — there is no annotation, and no SDK seam, that says
   * "these five constants are the list and those two hundred are not". So the pin's blast radius is the
   * whole enum. sscs's {@code EventType} is the case that forces this: 261 constants modelling the
   * service's internal event catalogue ({@code SYSTEM_MAINTENANCE}, {@code attachRoboticsJson},
   * {@code cohQuestionDeadlineElapsed}), against a 15-row {@code eventType} picklist it shares a name
   * with and nothing else. Pinning it emitted 246 rows CCD has never contained — 255 diff lines, over
   * half that lane's residual, from one binding.
   *
   * <p><b>Refusing does not lose the list.</b> The ID falls back to the companion path, which generates
   * an enum of exactly the definition's codes and labels, and
   * {@link RetrofitPatchEmitter#withTypeParameterClass} names it from each referencing field with
   * {@code @CCD(typeParameterClass)} — so the 15 rows are emitted, correctly, and the team's own enum is
   * left entirely alone (no {@code @ComplexType}, no serialisation change, no new constants). That is the
   * path probate and fpl already take for most of their lists.
   *
   * @param type the model enum a definition ID would be pinned to
   * @param list the definition's rows for that ID
   * @return true when the enum's constants and the list's codes are the same set
   */
  static boolean reproducesTheListExactly(ModelSourceIndex.Type type, FixedListModel list) {
    Match match = match(type, list);
    if (match == null) {
      return false;
    }
    // Every constant the enum will declare once the patch is applied — those already there plus any the
    // match plans to add — must be one the definition has a code for. Counting is enough, and is what the
    // generator does: it walks the constants, so one row comes out per constant either way.
    long declared = type.decl.asEnumDeclaration().getEntries().size() + match.toAdd().size();
    return declared == match.constantByCode().size();
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
        AddedConstant added = plannedConstant(decl, list, item, claimed, true);
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
      FixedListModel.Item item, Set<String> claimed, boolean pinnable) {
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
    if (arguments == null) {
      return null;
    }
    // The pin is only how a NAME-serialised enum is redirected to a code it is not named after. A
    // @JsonValue enum emits a constructor argument, which synthesisedArguments has already filled with
    // this row's own code, so it emits the code with no annotation at all — and a @JsonProperty there
    // would be dead weight on the team's published type, claiming a redirect Jackson never performs.
    String pinnedCode = pinnable && !name.equals(item.getCode()) ? item.getCode() : null;
    return new AddedConstant(name, pinnedCode, item.getCode(), item.getLabel(), arguments);
  }

  /**
   * The constructor arguments an added constant must carry, or null when they cannot be established from
   * the enum's own source.
   *
   * <p><b>The shape is copied, not inferred.</b> The one thing that makes an added constant compile is
   * passing what its siblings pass: the same argument COUNT, of the same kind. So every existing constant
   * must pass the same number of arguments and every one of those must be a literal this can read — the
   * shape that covers the plain {@code CODE("code")}, {@code CODE("code", "Label")} and
   * {@code DAYS_28(28, "28 days")} idioms teams write, and whose constructor (hand-written or
   * Lombok-generated, parameters named anything) is guaranteed to accept another call of that arity with
   * the same literal kind in each position. Anything else is refused: a constant with a body, a non-literal
   * argument (an enum reference, a concatenation, another constant), a varargs or mixed-arity call, a
   * position whose kind is not consistent across constants, or a no-argument enum where there is nothing to
   * copy — for those, what a new constant would have to pass is a guess, and a guess that fails to compile
   * breaks the team's build for a residual line.
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
      if (entry.getArguments().size() != arity || entry.getClassBody().isNonEmpty()) {
        return null;
      }
      for (int position = 0; position < arity; position++) {
        // Same kind in this position across every constant, so what to write there is copied rather than
        // chosen: a String literal stays quoted, an int literal is written bare.
        if (literalToken(entry.getArguments().get(position)) == null
            || isStringLiteral(entry, position) != isStringLiteral(entries.get(0), position)) {
          return null;
        }
      }
    }
    // Only constants the definition HAS a row for can testify about what a position means, since the
    // test is whether the constant passes ITS OWN code or label there. An enum with extra constants is
    // normal (every candidate has some) and they simply do not vote.
    //
    // The row is found by name where the enum's constants are named after their codes, and by the code the
    // constant PASSES where they are not: a @JsonValue enum names DECISION_REMADE for the code `remade`,
    // so a name-only lookup produced no witnesses at all and nothing could ever be added to such an enum
    // — which is exactly the shape sscs's InterlocReviewState needs a constant added to.
    Map<String, FixedListModel.Item> rowByConstant = rowByConstant(list);
    Map<String, String> byArgument = codesFromArguments(decl, list, false);
    Map<EnumConstantDeclaration, FixedListModel.Item> rowByWitness = new LinkedHashMap<>();
    for (EnumConstantDeclaration entry : entries) {
      FixedListModel.Item row = rowByConstant.get(entry.getNameAsString());
      if (row == null && byArgument.containsKey(entry.getNameAsString())) {
        row = rowByConstant.get(byArgument.get(entry.getNameAsString()));
      }
      if (row != null) {
        rowByWitness.put(entry, row);
      }
    }
    if (rowByWitness.isEmpty()) {
      return null;
    }
    List<String> arguments = new ArrayList<>();
    for (int position = 0; position < arity; position++) {
      String value = unanimousArgument(rowByWitness, position, item);
      if (value == null) {
        return null;
      }
      arguments.add(isStringLiteral(entries.get(0), position)
          ? CcdAnnotationRenderer.quote(value)
          : value);
    }
    return arguments;
  }

  /** Whether a constant passes a String literal (rather than a numeric one) in a given position. */
  private static boolean isStringLiteral(EnumConstantDeclaration entry, int position) {
    return entry.getArguments().get(position) instanceof StringLiteralExpr;
  }

  /**
   * Whether some constant already declared passes this label as one of its own arguments — the test for
   * "the enum already models this value, under another name". Compared case-insensitively for the same
   * reason a label vote is: what a team copied into its own source drifts in case.
   */
  private static boolean alreadySaysThis(EnumDeclaration decl, String label) {
    return decl.getEntries().stream()
        .flatMap(entry -> entry.getArguments().stream())
        .map(RetrofitFixedListLabels::literalToken)
        .filter(java.util.Objects::nonNull)
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
  private static String unanimousArgument(
      Map<EnumConstantDeclaration, FixedListModel.Item> rowByWitness, int position,
      FixedListModel.Item item) {
    boolean code = true;
    boolean empty = true;
    int labelled = 0;
    for (Map.Entry<EnumConstantDeclaration, FixedListModel.Item> witness : rowByWitness.entrySet()) {
      String passed = literalToken(witness.getKey().getArguments().get(position));
      FixedListModel.Item own = witness.getValue();
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
    if (labelled * 2 > rowByWitness.size() && item.getLabel() != null) {
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
