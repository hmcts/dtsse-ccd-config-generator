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
 *       exactly that, so a cross-kind pin would name a type the other generator emits.</li>
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
    Map<String, ModelSourceIndex.Type> bound = new LinkedHashMap<>();
    while (!unbound.isEmpty()) {
      Map<String, List<ModelSourceIndex.Type>> candidates =
          collectReferences(ir, caseTypeId, unbound, rootProperties, bound);
      Map<String, ModelSourceIndex.Type> pass =
          decide(candidates, complexTypeIds, fixedListIds);
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
      Set<String> fixedListIds) {
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
      bound.put(definitionId, type);
    }
    return bound;
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
   * The definition type ID a row references: its {@code FieldTypeParameter}, or its {@code FieldType}
   * when that is itself a declared complex type rather than a CCD base type (a {@code Complex} field
   * carries the type ID in {@code FieldType} in some definition layouts).
   */
  private String referencedTypeId(SheetRow row) {
    String parameter = row.getString(Columns.FIELD_TYPE_PARAMETER).orElse(null);
    if (parameter != null && !parameter.isEmpty()) {
      return parameter;
    }
    return row.getString(Columns.FIELD_TYPE).orElse(null);
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
