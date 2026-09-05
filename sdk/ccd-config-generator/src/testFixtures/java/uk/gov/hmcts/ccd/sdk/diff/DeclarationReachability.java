package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes, from a definition's own sheets, which {@code ComplexTypes} and {@code FixedLists}
 * declarations something reachable from a {@code CaseData} field actually references — mirroring the
 * reachability the converter's {@code DefaultDefinitionLinker} uses to decide which declarations the
 * SDK generator emits. The {@code ORPHAN_COMPLEX_TYPE} and {@code ORPHAN_FIXED_LIST} rules use this
 * so each is self-contained: it drops only a declaration that is genuinely unreachable in the input,
 * never one that IS reachable (which would be real drift and must still fail).
 *
 * <p>A complex type is reachable when some {@code CaseField} names it (via {@code FieldType} or
 * {@code FieldTypeParameter}), transitively following each reachable type's members'
 * {@code FieldType}/{@code FieldTypeParameter}. A fixed list is reachable when a {@code CaseField}
 * or a member of a reachable complex type references it, or when its ID also names a complex type
 * (the collision case the converter still passes through, so it is not an orphan).
 */
final class DeclarationReachability {

    private static final String CASE_FIELD_SHEET = "CaseField";
    private static final String COMPLEX_TYPES_SHEET = "ComplexTypes";
    private static final String ID = "ID";
    private static final String FIELD_TYPE = "FieldType";
    private static final String FIELD_TYPE_PARAMETER = "FieldTypeParameter";

    private final Set<String> reachableComplexTypes;
    private final Set<String> referencedTypeParameters;
    private final Set<String> complexTypeIds;

    private DeclarationReachability(Set<String> reachableComplexTypes,
                                    Set<String> referencedTypeParameters,
                                    Set<String> complexTypeIds) {
        this.reachableComplexTypes = reachableComplexTypes;
        this.referencedTypeParameters = referencedTypeParameters;
        this.complexTypeIds = complexTypeIds;
    }

    /**
     * Analyses one definition (the sheet map for the side whose declarations are being checked).
     *
     * @param definition sheetName → rows for the side under analysis
     * @return the reachability facts for its complex-type and fixed-list declarations
     */
    static DeclarationReachability analyse(Map<String, List<Map<String, Object>>> definition) {
        List<Map<String, Object>> caseFields =
            definition.getOrDefault(CASE_FIELD_SHEET, List.of());
        List<Map<String, Object>> complexRows =
            definition.getOrDefault(COMPLEX_TYPES_SHEET, List.of());

        Set<String> complexTypeIds = new LinkedHashSet<>();
        for (Map<String, Object> row : complexRows) {
            String id = str(row.get(ID));
            if (id != null) {
                complexTypeIds.add(id);
            }
        }

        Set<String> reachable = reachableComplexTypes(caseFields, complexRows, complexTypeIds);
        Set<String> referenced = referencedTypeParameters(caseFields, complexRows, reachable);
        return new DeclarationReachability(reachable, referenced, complexTypeIds);
    }

    /**
     * Whether the complex-type ID is reachable from a CaseData field (directly or transitively).
     *
     * @param id the ComplexTypes sheet ID
     * @return true when something reachable references it
     */
    boolean isComplexTypeReachable(String id) {
        return reachableComplexTypes.contains(id);
    }

    /**
     * Whether the fixed-list ID is reachable: referenced by a field or reachable complex member, or
     * colliding with a complex-type ID (the case the converter still passes through, not an orphan).
     *
     * @param id the FixedLists sheet ID
     * @return true when the list is not an orphan
     */
    boolean isFixedListReachable(String id) {
        return referencedTypeParameters.contains(id) || complexTypeIds.contains(id);
    }

    private static Set<String> reachableComplexTypes(List<Map<String, Object>> caseFields,
                                                     List<Map<String, Object>> complexRows,
                                                     Set<String> complexTypeIds) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map<String, Object> row : caseFields) {
            enqueueIfComplex(str(row.get(FIELD_TYPE)), complexTypeIds, queue);
            enqueueIfComplex(str(row.get(FIELD_TYPE_PARAMETER)), complexTypeIds, queue);
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!reachable.add(id)) {
                continue;
            }
            for (Map<String, Object> row : complexRows) {
                if (!id.equals(str(row.get(ID)))) {
                    continue;
                }
                enqueueIfComplex(str(row.get(FIELD_TYPE)), complexTypeIds, queue);
                enqueueIfComplex(str(row.get(FIELD_TYPE_PARAMETER)), complexTypeIds, queue);
            }
        }
        return reachable;
    }

    private static void enqueueIfComplex(String ref, Set<String> complexTypeIds,
                                         Deque<String> queue) {
        if (ref != null && complexTypeIds.contains(ref)) {
            queue.add(ref);
        }
    }

    private static Set<String> referencedTypeParameters(List<Map<String, Object>> caseFields,
                                                        List<Map<String, Object>> complexRows,
                                                        Set<String> reachableComplex) {
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> row : caseFields) {
            add(referenced, str(row.get(FIELD_TYPE_PARAMETER)));
            add(referenced, str(row.get(FIELD_TYPE)));
        }
        // Only members of a reachable complex type count: a reference from an unreachable type is
        // itself dropped, so a fixed list it alone names is an orphan (matching the converter).
        for (Map<String, Object> row : complexRows) {
            String owner = str(row.get(ID));
            if (owner == null || !reachableComplex.contains(owner)) {
                continue;
            }
            add(referenced, str(row.get(FIELD_TYPE_PARAMETER)));
            add(referenced, str(row.get(FIELD_TYPE)));
        }
        return referenced;
    }

    private static void add(Set<String> set, String value) {
        if (value != null) {
            set.add(value);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }
}
