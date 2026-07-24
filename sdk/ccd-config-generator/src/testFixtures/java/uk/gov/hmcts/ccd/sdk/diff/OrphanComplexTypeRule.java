package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ORPHAN_COMPLEX_TYPE — drops input-side {@code ComplexTypes} rows whose ID nothing reachable
 * references, as an accepted semantic difference.
 *
 * <p><strong>Maintainer decision:</strong> a complex type declared on the {@code ComplexTypes}
 * sheet that no {@code CaseField} (directly or transitively through another reachable type's
 * members) references is dead weight — the SDK's {@code ConfigResolver}/{@code ComplexTypeGenerator}
 * never emit a class or any rows for it (it is not in {@code config.getTypes()}). The converter no
 * longer passes such an orphan through; it drops it with an advisory gap ("orphan declaration, safe
 * to delete"). So the generated definition legitimately omits the orphan's rows, and this rule
 * forgives their expected-side-only presence.</p>
 *
 * <p>The rule is self-contained: it recomputes reachability from the expected definition's own
 * sheets (see {@link DeclarationReachability}, mirroring the converter's linker) and drops a row
 * only when its ID is genuinely unreachable AND the actual side emitted no row for that ID. A
 * reachable complex type the generator failed to emit is real drift and still fails (the row is not
 * dropped); a generated declaration under the same ID (a conflict) is likewise never masked.</p>
 */
public final class OrphanComplexTypeRule implements NormalisationRule {

    private static final String SHEET = "ComplexTypes";
    private static final String ID = "ID";

    @Override
    public String name() {
        return "ORPHAN_COMPLEX_TYPE";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        List<Map<String, Object>> expectedRows = expected.getOrDefault(SHEET, List.of());
        if (expectedRows.isEmpty()) {
            return;
        }
        DeclarationReachability reachability = DeclarationReachability.analyse(expected);
        Set<String> actualIds = idsIn(actual.getOrDefault(SHEET, List.of()));

        Set<String> dropped = new LinkedHashSet<>();
        List<Map<String, Object>> kept = new ArrayList<>(expectedRows.size());
        for (Map<String, Object> row : expectedRows) {
            String id = str(row.get(ID));
            // Drop only a genuinely-unreachable ID the actual side did not emit. A reachable type,
            // or one the generated side also declares (a conflict), is left in place so it still
            // compares — and fails on any real difference.
            if (id != null && !reachability.isComplexTypeReachable(id) && !actualIds.contains(id)) {
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
        recorder.record(this, "dropped orphan (unreferenced) ComplexTypes declaration(s) "
            + dropped + " from the expected side (no generated counterpart)");
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
