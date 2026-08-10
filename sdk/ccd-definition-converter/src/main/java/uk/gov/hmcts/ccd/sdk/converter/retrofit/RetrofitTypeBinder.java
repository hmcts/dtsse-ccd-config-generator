package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;

/**
 * Binds a definition type ID (a {@code ComplexTypes} or {@code FixedLists} ID) to the model class the
 * definition's own <em>referencing field</em> is declared as, for the IDs no name-based lookup can
 * reach.
 *
 * <p><b>The problem.</b> {@link ModelSourceIndex#complexTypeClass} matches an ID to a class by simple
 * name — exactly, then case-insensitively. That covers a camelCase ID against a PascalCase class
 * ({@code appeal → Appeal}) but nothing further. Real definitions name their types independently of the
 * Java classes behind them: probate's {@code ExecutorApplying} is modelled by
 * {@code AdditionalExecutorApplying}, ET's {@code ClaimantIndividual} by {@code ClaimantIndType},
 * fpl's {@code CafcassEnglandOffices} by {@code EnglandOffices}, probate's {@code handoffReasonFixedList}
 * by the enum {@code HandoffReasonId}. Each such miss costs twice over:
 * <ul>
 *   <li>the ID lands in the companion set, so a standalone companion class/enum is generated for it —
 *       and, since the retype that would point the referencing field at that companion is refused
 *       whenever the field has callers, the companion is left referenced by nothing. The SDK never
 *       reflects it, so it emits no rows and the definition's own rows have no counterpart;</li>
 *   <li>the real model class is still reflected, under its Java simple name, so it emits a full set of
 *       rows under an ID the definition never mentions.</li>
 * </ul>
 * Measured across the probate/ET/fpl/prl retrofit lanes, that is 2,400 diff lines of mismatched pairs
 * plus 2,000 of orphaned companion — the largest single category of residual by a wide margin.
 *
 * <p><b>The binding.</b> The definition itself says what backs each type: a {@code CaseField} or
 * {@code ComplexTypes} member row whose {@code FieldTypeParameter} (or, for a bare {@code Complex}
 * field, {@code FieldType}) is the ID identifies a field, and that field's declared Java type is the
 * class CCD addresses the type's members on. So instead of guessing from the name, read the declaration:
 * resolve the referencing field on the model, descend its declared type to the token the SDK itself
 * names ({@link RetrofitTypeTokens}), and bind the ID to that class.
 *
 * <p>The result is consumed by {@link RetrofitPatchEmitter}, which pins {@code @ComplexType(name = <id>,
 * generate = true)} onto the bound class so the SDK emits the type under the definition's ID — the same
 * machinery, and the same refusals, that already handle the case-divergence bindings. No field
 * declaration is rewritten, so unlike the retype this cannot break a caller in a published jar.
 *
 * <p><b>Refusals.</b> A binding is only offered when it is unambiguous and safe:
 * <ul>
 *   <li><b>no name-based binding exists.</b> This is strictly a fallback: when
 *       {@code complexTypeClass} already resolves the ID, that answer wins, because every other part of
 *       the converter (the member walk, the companion filter, the reserved-name sets) is keyed on it;</li>
 *   <li><b>every referencing field agrees.</b> An ID referenced by two fields of different declared
 *       types has no single backing class, so it is left to the companion path rather than bound to
 *       whichever field was read first;</li>
 *   <li><b>the class is not already claimed by an exactly-named definition type.</b> If the definition
 *       also declares a type whose ID IS the class's simple name, that row owns the class and pinning a
 *       second ID onto it would rename the type out from under it;</li>
 *   <li><b>one class, one ID.</b> Two definition IDs binding to a single class can pin only one name, so
 *       neither is bound here — the ambiguity is reported by the existing collision gap rather than
 *       silently resolved;</li>
 *   <li><b>kind must match.</b> A {@code FixedLists} ID binds only to an enum and a {@code ComplexTypes}
 *       ID only to a class: {@code FixedListGenerator} and {@code ComplexTypeGenerator} select on
 *       exactly that, so a cross-kind pin would name a type the other generator emits;</li>
 *   <li><b>a fixed list's enum must not declare more constants than the list has codes.</b>
 *       {@code FixedListGenerator} emits one row per constant and offers no way to exclude one, so
 *       pinning an enum that merely CONTAINS the definition's codes emits every extra constant as a row
 *       CCD does not have. sscs's {@code EventType} — 261 constants of internal event catalogue against
 *       a 15-row {@code eventType} picklist of the same name — cost 255 diff lines that way, over half
 *       that lane's residual. Such an ID goes to the companion path instead, which generates an enum of
 *       exactly the definition's codes and points each referencing field at it with
 *       {@code @CCD(typeParameterClass)}; the list is still emitted in full and the team's own enum is
 *       left alone. See {@link RetrofitFixedListLabels#reproducesTheListExactly}.</li>
 * </ul>
 */
final class RetrofitTypeBinder {

  /** A definition ID bound to the model type its referencing field is declared as. */
  record Binding(String definitionId, ModelSourceIndex.Type type) {
  }

  private final ModelSourceIndex index;
  private final String modelPackage;

  RetrofitTypeBinder(ModelSourceIndex index, String modelPackage) {
    this.index = index;
    this.modelPackage = modelPackage;
  }

  /**
   * The bindings for a case type's definition IDs, derived from the declarations of the fields that
   * reference them.
   *
   * @param ir the parsed definition
   * @param caseTypeId the case type being converted
   * @param rootProperties the root model class's resolved properties (CCD field id → declaration)
   * @return definition ID → bound model type, for the IDs that bind unambiguously
   */
  Map<String, ModelSourceIndex.Type> bind(DefinitionIr ir, String caseTypeId,
      Map<String, ResolvedProperty> rootProperties) {
    Set<String> complexTypeIds = sheetIds(ir, caseTypeId, SheetName.COMPLEX_TYPES);
    Set<String> fixedListIds = sheetIds(ir, caseTypeId, SheetName.FIXED_LISTS);
    // Only the IDs no name-based lookup reaches: the name-based answer is what the rest of the
    // converter is keyed on, so it must keep winning wherever it exists.
    Set<String> unbound = new LinkedHashSet<>();
    for (String id : complexTypeIds) {
      if (index.complexTypeClass(id, modelPackage).isEmpty()) {
        unbound.add(id);
      }
    }
    for (String id : fixedListIds) {
      // A FixedList whose ID names a top-level model type is already bound by the rebinder's own
      // hasTopLevelType drop, which is the FixedLists equivalent of the name-based lookup.
      if (!index.hasTopLevelType(id)) {
        unbound.add(id);
      }
    }
    if (unbound.isEmpty()) {
      return Map.of();
    }

    // Resolved to a FIXPOINT, because a member row can only be read once its OWNING type has a class:
    // a nested chain of divergently-named types (ET's et3ResponseSection → its own composite members)
    // binds one level per pass, so a single pass would stop at the first miss and leave the rest of the
    // chain to the companion path. Passes are bounded by the number of unbound IDs — each pass either
    // binds at least one or stops — so the loop terminates on any definition.
    Map<String, Integer> listCodeCounts = codeCountsByListId(ir, caseTypeId);
    Map<String, ModelSourceIndex.Type> bound = new LinkedHashMap<>();
    while (!unbound.isEmpty()) {
      Map<String, List<ModelSourceIndex.Type>> candidates =
          collectReferences(ir, caseTypeId, unbound, rootProperties, bound);
      Map<String, ModelSourceIndex.Type> pass =
          decide(candidates, complexTypeIds, fixedListIds, listCodeCounts);
      if (pass.isEmpty()) {
        break;
      }
      bound.putAll(pass);
      unbound.removeAll(pass.keySet());
      // Applied per pass, not just at the end, so the next pass never reads a member off a class whose
      // ownership is contested (the one-class-many-IDs collision) — and the IDs it drops stay dropped.
      Map<String, ModelSourceIndex.Type> unique = dropClassesClaimedTwice(bound);
      bound.keySet().retainAll(unique.keySet());
    }
    return bound;
  }

  /**
   * The class FQNs to prefer when a definition ID's simple name is shared by several top-level model
   * classes and nothing else separates them — read, like every binding here, from the declared type of
   * the definition's OWN referencing field.
   *
   * <p>Computed and installed BEFORE {@link #bind}, because {@code complexTypeClass} is the lookup bind
   * itself asks whether an ID is already reached: an arbitrary tie-break there both hides the ID from the
   * binder and gives the rest of the converter the wrong class. prl's {@code OtherDocuments} is the case
   * — one in {@code models.complextypes} that {@code CaseData} reaches, one in {@code models.dto.cafcass}
   * that nothing reaches — and source-scan order chose the latter.
   *
   * <p>Root {@code CaseField} rows only. A member-row reference would need the owning type resolved
   * first, which is the fixpoint {@code bind} runs — and running it against a lookup this is still
   * deciding would be circular. An ambiguous ID reached only from member rows therefore keeps whatever
   * the existing tie-break gives it, unchanged.
   *
   * @param ir the parsed definition
   * @param caseTypeId the case type being converted
   * @param rootProperties the root model class's resolved properties
   * @return the FQNs to prefer on a tie
   */
  Set<String> declaredClassPreferences(DefinitionIr ir, String caseTypeId,
      Map<String, ResolvedProperty> rootProperties) {
    Map<String, Set<String>> byId = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId)) {
      String typeId = referencedTypeId(row);
      if (typeId == null || !index.isAmbiguousTopLevelClassName(typeId)) {
        continue;
      }
      row.getString(Columns.ID)
          .map(rootProperties::get)
          .flatMap(this::declaredType)
          .ifPresent(type ->
              byId.computeIfAbsent(typeId, k -> new LinkedHashSet<>()).add(type.fqn));
    }
    // Only where every referencing field agrees, for the same reason decide() insists on unanimity: two
    // fields declared differently give the ID no single backing, and picking one would be the arbitrary
    // choice this exists to remove.
    Set<String> preferred = new LinkedHashSet<>();
    byId.values().stream().filter(fqns -> fqns.size() == 1).forEach(preferred::addAll);
    return preferred;
  }

  /**
   * The candidate declarations per unbound ID, gathered from every row that references it: a root
   * {@code CaseField} row's field resolved on the root model class, and a {@code ComplexTypes} member
   * row's resolved on the class its OWNING definition type binds to.
   *
   * <p>Member rows are where the bulk of the misses live — ET references 127 of its type IDs only from
   * member rows, probate 36 — because a nested type is rarely a root case field. The owning type is
   * resolved by the same {@code complexTypeClass} + value-class unwrap the member walk and the patch's
   * member annotation both use, so a member is read off exactly the class those two agree on, plus the
   * bindings already established by earlier passes.
   */
  private Map<String, List<ModelSourceIndex.Type>> collectReferences(DefinitionIr ir,
      String caseTypeId, Set<String> unbound, Map<String, ResolvedProperty> rootProperties,
      Map<String, ModelSourceIndex.Type> bound) {
    Map<String, List<ModelSourceIndex.Type>> candidates = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId)) {
      String typeId = referencedTypeId(row);
      if (typeId == null || !unbound.contains(typeId)) {
        continue;
      }
      row.getString(Columns.ID)
          .map(rootProperties::get)
          .flatMap(this::declaredType)
          .ifPresent(type -> candidates.computeIfAbsent(typeId, k -> new ArrayList<>()).add(type));
    }
    ValueWrapperUnwrapper unwrapper = new ValueWrapperUnwrapper(index);
    // Member resolutions are cached per owning class: a large definition references the same owner from
    // dozens of member rows, and resolving a class's properties walks its whole superclass chain.
    Map<String, Map<String, ResolvedProperty>> memberProperties = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.COMPLEX_TYPES, caseTypeId)) {
      String typeId = referencedTypeId(row);
      if (typeId == null || !unbound.contains(typeId)) {
        continue;
      }
      String ownerId = row.getString(Columns.ID).orElse(null);
      String memberId = row.getString(Columns.LIST_ELEMENT_CODE).orElse(null);
      if (ownerId == null || memberId == null) {
        continue;
      }
      Map<String, ResolvedProperty> properties = memberProperties.computeIfAbsent(ownerId, id ->
          owningClass(id, bound)
              .map(owner -> new PropertyResolver(index).resolve(unwrapper.unwrap(owner)).properties)
              .orElse(Map.of()));
      Optional.ofNullable(properties.get(memberId))
          .flatMap(this::declaredType)
          .ifPresent(type -> candidates.computeIfAbsent(typeId, k -> new ArrayList<>()).add(type));
    }
    return candidates;
  }

  /**
   * The class a {@code ComplexTypes} row's own ID is addressed on: its name-based class, else the class
   * an earlier pass bound it to. An enum is never an owner — it has no members for a row to describe.
   */
  private Optional<ModelSourceIndex.Type> owningClass(String ownerId,
      Map<String, ModelSourceIndex.Type> bound) {
    Optional<ModelSourceIndex.Type> named = index.complexTypeClass(ownerId, modelPackage);
    if (named.isPresent()) {
      return named;
    }
    return Optional.ofNullable(bound.get(ownerId)).filter(type -> !type.isEnum());
  }

  /**
   * The bindings the gathered candidates justify, applying the per-ID refusals: unanimity across every
   * referencing field, the class's own name not being a definition ID in its own right, and the kind
   * matching the generator that will emit the type.
   */
  private Map<String, ModelSourceIndex.Type> decide(
      Map<String, List<ModelSourceIndex.Type>> candidates, Set<String> complexTypeIds,
      Set<String> fixedListIds, Map<String, Integer> listCodeCounts) {
    Map<String, ModelSourceIndex.Type> bound = new LinkedHashMap<>();
    for (Map.Entry<String, List<ModelSourceIndex.Type>> entry : candidates.entrySet()) {
      final String definitionId = entry.getKey();
      // Every referencing field must agree: an ID declared two different ways has no single backing.
      Set<String> distinct = new LinkedHashSet<>();
      entry.getValue().forEach(type -> distinct.add(type.fqn));
      if (distinct.size() != 1) {
        continue;
      }
      ModelSourceIndex.Type type = entry.getValue().get(0);
      // The class's own simple name is a definition ID in its own right: that row owns the class.
      if (complexTypeIds.contains(type.simpleName) || fixedListIds.contains(type.simpleName)) {
        continue;
      }
      // Kind must match what the name-based lookup would have accepted, which is what the generator that
      // emits the type selects on: an enum for a FixedLists ID, and a CLASS — not an interface, and not a
      // RECORD — for a ComplexTypes ID. A record is the sharpest case: it has no mutable fields, so the
      // patch's member synthesis would emit instance fields into it and the model would not compile
      // (Civil's HomeDetails, "field declaration must be static").
      if (fixedListIds.contains(definitionId) ? !type.isEnum() : !type.isClass()) {
        continue;
      }
      // A FixedList pin must reproduce the list EXACTLY, because it decides which enum IS the list and
      // FixedListGenerator then emits one row per constant with no filter available. An enum whose
      // constant set merely CONTAINS the definition's codes emits the extras as rows CCD never had —
      // sscs's 261-constant EventType against the 15-row eventType picklist it shares a name with,
      // 246 spurious rows. Refusing here routes the ID to the companion path, which generates an enum
      // of exactly the definition's codes that each referencing field is pointed at with
      // @CCD(typeParameterClass) — so the list is still emitted, correctly, and the team's enum is
      // untouched. Counted on the definition's own rows, since the linker has not run yet.
      if (fixedListIds.contains(definitionId)
          && !reproducesExactly(type, listCodeCounts.get(definitionId))) {
        continue;
      }
      bound.put(definitionId, type);
    }
    return bound;
  }

  /**
   * Whether pinning this enum to a list of {@code codeCount} codes emits no row the definition lacks.
   *
   * <p>Only the count is compared, and only in one direction. {@code FixedListGenerator} emits exactly
   * one row per enum constant, so an enum declaring MORE constants than the list has codes necessarily
   * emits rows the definition does not have, whatever those constants are named — that is the whole
   * failure this refuses, and it needs no name matching to establish. Declaring the same number or fewer
   * is left to pass: the codes are then reconciled name-by-name downstream by
   * {@link RetrofitFixedListLabels}, which pins the spellings that differ, adds a constant for a code
   * genuinely absent, and refuses the enum outright where neither works.
   *
   * <p>Counted from the definition's rows rather than the linked {@code FixedListModel} because the
   * bindings are derived before the linker runs — {@link RetrofitConverter} computes them once from the
   * root resolution and hands the same answer to every consumer.
   *
   * @param type the candidate model enum
   * @param codeCount the number of distinct {@code ListElementCode}s the definition's list declares, or
   *     null when the definition has no rows for the ID at all
   * @return true when the enum declares no more constants than the list has codes
   */
  private boolean reproducesExactly(ModelSourceIndex.Type type, Integer codeCount) {
    if (codeCount == null || codeCount == 0) {
      return false; // no rows to reproduce: pinning could only add spurious ones
    }
    return type.decl.asEnumDeclaration().getEntries().size() <= codeCount;
  }

  /**
   * The number of distinct {@code ListElementCode}s each {@code FixedLists} ID declares.
   *
   * @param ir the parsed definition
   * @param caseTypeId the case type being converted
   * @return list ID → distinct code count
   */
  private Map<String, Integer> codeCountsByListId(DefinitionIr ir, String caseTypeId) {
    Map<String, Set<String>> codes = new LinkedHashMap<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.FIXED_LISTS, caseTypeId)) {
      String id = row.getString(Columns.ID).orElse(null);
      String code = row.getString(Columns.LIST_ELEMENT_CODE).orElse(null);
      if (id != null && code != null) {
        codes.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(code);
      }
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    codes.forEach((id, set) -> counts.put(id, set.size()));
    return counts;
  }

  /**
   * Drops every binding whose class a second definition ID also binds to. A class carries only one
   * {@code @ComplexType(name)}, so picking a winner here would be arbitrary; leaving both unbound keeps
   * the existing behaviour (and, for a genuinely one-class-many-IDs model, the existing collision gap
   * on the name-based path is the report that says so).
   */
  private Map<String, ModelSourceIndex.Type> dropClassesClaimedTwice(
      Map<String, ModelSourceIndex.Type> bound) {
    Map<String, Integer> claims = new LinkedHashMap<>();
    bound.values().forEach(type -> claims.merge(type.fqn, 1, Integer::sum));
    Map<String, ModelSourceIndex.Type> unique = new LinkedHashMap<>();
    bound.forEach((id, type) -> {
      if (claims.getOrDefault(type.fqn, 0) == 1) {
        unique.put(id, type);
      }
    });
    return unique;
  }

  /**
   * The model type a resolved property's declaration names, descended to the token CCD addresses the
   * definition type on. Empty when the declaration names no single token, or names something outside the
   * parsed model (a {@code String}, an SDK platform type) — in which case the definition and the model
   * disagree about more than a name and there is nothing to pin.
   */
  private Optional<ModelSourceIndex.Type> declaredType(ResolvedProperty property) {
    if (property == null) {
      return Optional.empty();
    }
    com.github.javaparser.ast.type.Type token =
        RetrofitTypeTokens.elementToken(property.declaredType);
    if (!(token instanceof ClassOrInterfaceType cit)) {
      return Optional.empty();
    }
    return index.resolve(property.context, cit).filter(ModelSourceIndex.Type::isTopLevel);
  }

  /**
   * The definition type ID a row references, read the way CCD itself reads it: the
   * {@code FieldTypeParameter} only when the {@code FieldType} is one that PARAMETERISES — a
   * {@code Collection} or one of the list types — and otherwise the {@code FieldType}, which for a
   * complex field carries the type ID directly.
   *
   * <p>The kind check is not cosmetic: {@code FieldTypeParser} (the definition-store importer) computes
   * {@code isList(baseType) ? listReference(baseType, parameter) : baseType} and reads the parameter
   * again only for {@code Collection}, so a {@code FieldTypeParameter} sitting on a row whose
   * {@code FieldType} is a complex type is a column CCD never looks at. Real definitions carry such
   * vestigial values, and taking one at face value here is worse than useless — it fabricates a
   * reference. probate's {@code originalDocuments} row is {@code FieldType: OriginalDocuments} with a
   * leftover {@code FieldTypeParameter: ProbateDocument}: read literally, {@code ProbateDocument}
   * appeared to be referenced by a field declared {@code OriginalDocuments} as well as by the four
   * {@code Collection<ProbateDocument>} fields declared {@code Document}, so the unanimity refusal
   * discarded a binding that was in fact unanimous, and the whole {@code Document} class emitted its
   * members under the wrong ID.
   */
  private String referencedTypeId(SheetRow row) {
    String fieldType = row.getString(Columns.FIELD_TYPE).orElse(null);
    if (parameterises(fieldType)) {
      String parameter = row.getString(Columns.FIELD_TYPE_PARAMETER).orElse(null);
      if (parameter != null && !parameter.isEmpty()) {
        return parameter;
      }
    }
    return fieldType;
  }

  /**
   * Whether a {@code FieldType} is one whose {@code FieldTypeParameter} names another type — the CCD
   * base types {@code FieldTypeParser} reads the column for.
   */
  private static boolean parameterises(String fieldType) {
    return "Collection".equals(fieldType)
        || "FixedList".equals(fieldType)
        || "FixedRadioList".equals(fieldType)
        || "MultiSelectList".equals(fieldType);
  }

  /**
   * The distinct {@code ID} column values on a sheet for this case type.
   */
  private Set<String> sheetIds(DefinitionIr ir, String caseTypeId, SheetName sheet) {
    Set<String> ids = new LinkedHashSet<>();
    for (SheetRow row : ir.rowsForCaseType(sheet, caseTypeId)) {
      row.getString(Columns.ID).ifPresent(ids::add);
    }
    return ids;
  }
}
