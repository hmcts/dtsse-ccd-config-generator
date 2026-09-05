package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ORPHAN_FIXED_LIST — drops input-side {@code FixedLists} rows whose ID nothing reachable
 * references, as an accepted semantic difference.
 *
 * <p><strong>Maintainer decision:</strong> a fixed list no {@code CaseField} and no member of a
 * reachable complex type references (fpl's {@code fl_Annex}, reached only through a
 * SharedStorage-only complex type that is itself an orphan) is not in the SDK generator's type set,
 * so {@code FixedListGenerator} emits no enum file for it. The converter no longer passes such an
 * orphan-path list through; it drops it with an advisory gap. So the generated definition omits the
 * orphan's rows, and this rule forgives their expected-side-only presence.</p>
 *
 * <p>Self-contained and narrow: reachability is recomputed from the expected definition's own sheets
 * (see {@link DeclarationReachability}, mirroring the converter's linker) — a list referenced by a
 * field or a reachable complex member, or one whose ID also names a complex type (the collision case
 * the converter still passes through), is NOT dropped. A row is only dropped when it is genuinely
 * unreferenced AND the actual side emitted no {@code FixedLists} row for that ID, so a reachable
 * list the generator failed to emit is real drift and still fails.</p>
 */
public final class OrphanFixedListRule implements NormalisationRule {

    private static final String SHEET = "FixedLists";
    private static final String ID = "ID";

    @Override
    public String name() {
        return "ORPHAN_FIXED_LIST";
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
            if (id != null && !reachability.isFixedListReachable(id) && !actualIds.contains(id)) {
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
        recorder.record(this, "dropped orphan (unreferenced) FixedLists declaration(s) "
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
