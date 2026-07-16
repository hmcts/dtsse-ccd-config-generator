package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PREDEFINED_COMPLEX_TYPE_REDECLARATION — drops input-side {@code ComplexTypes} rows that spell out,
 * member by member, an SDK-predefined platform type, as an accepted semantic difference.
 *
 * <p><strong>Maintainer decision:</strong> the SDK ships types such as {@code Fee}, {@code Address},
 * {@code ChangeOrganisationRequest} as classes annotated {@code @ComplexType(generate = false)}, so
 * {@code ComplexTypeGenerator} emits no rows for them (the definition store learns them from the SDK
 * jar) and referencing fields resolve to the built-in class. A definition that re-declares such a
 * type's members on its own {@code ComplexTypes} sheet (fpl/civil's {@code Fee}, probate's
 * {@code Address}) is a redundant redeclaration of a platform type: the built-in type owns its
 * definition. The converter no longer passes those rows through; it drops them with an advisory gap
 * ("redundant redeclaration of platform type"). So the generated definition omits them, and this
 * rule forgives their expected-side-only presence.</p>
 *
 * <p>The predefined ID set is reflected from {@code uk.gov.hmcts.ccd.sdk.type} (see
 * {@link PredefinedComplexTypes}) — the SDK's own source of truth — rather than a hand-coded list
 * that could drift. The rule is narrow: it drops a row only when its ID is a predefined type AND the
 * actual side emitted no {@code ComplexTypes} row for that ID. If the generated side DID emit a
 * declaration under that ID (a conflict — the SDK type was generated after all), the rows are left
 * in place and any real difference still fails.</p>
 */
public final class PredefinedComplexTypeRedeclarationRule implements NormalisationRule {

    private static final String SHEET = "ComplexTypes";
    private static final String ID = "ID";

    @Override
    public String name() {
        return "PREDEFINED_COMPLEX_TYPE_REDECLARATION";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        List<Map<String, Object>> expectedRows = expected.getOrDefault(SHEET, List.of());
        if (expectedRows.isEmpty()) {
            return;
        }
        Set<String> actualIds = idsIn(actual.getOrDefault(SHEET, List.of()));

        Set<String> dropped = new LinkedHashSet<>();
        List<Map<String, Object>> kept = new ArrayList<>(expectedRows.size());
        for (Map<String, Object> row : expectedRows) {
            String id = str(row.get(ID));
            // Drop only a predefined-type ID the generated side did NOT emit rows for. If the
            // generated side declares the same ID, that is a real generated declaration (conflict)
            // and must still be compared, so the rows are kept.
            if (id != null && PredefinedComplexTypes.isPredefined(id) && !actualIds.contains(id)) {
                dropped.add(id);
            } else {
                kept.add(row);
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        expectedRows.clear();
        expectedRows.addAll(kept);
        recorder.record(this, "dropped redundant redeclaration(s) of SDK-predefined platform "
            + "type(s) " + dropped + " from the expected side (built-in type, no generated rows)");
    }

    private static Set<String> idsIn(List<Map<String, Object>> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String id = str(row.get(ID));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }
}
