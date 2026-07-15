package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * LIVE_TO_VESTIGIAL — forgives a uniform, vestigial {@code LiveTo} the expected side carries on
 * every {@code AuthorisationCaseState} row where the generated side omits it.
 *
 * <p><strong>Semantic, accepted</strong> (maintainer decision 2026-07-15). Probate's definition
 * stamps an identical past-dated {@code LiveTo=01/01/2020} on <em>every</em>
 * {@code AuthorisationCaseState} row — a definition-wide vestige the SDK has no API to reproduce
 * and never emits. Because the value is uniform across the whole sheet it is not a genuine
 * staggered end-of-life; it is dead metadata. The rule strips that column from the expected side
 * so the rows match.</p>
 *
 * <p>The scope is deliberately narrow, so a real end-of-life date still fails:</p>
 * <ul>
 *   <li>Only the {@code AuthorisationCaseState} sheet.</li>
 *   <li>Only the expected-carries / actual-omits shape — if any actual row carries a
 *       {@code LiveTo}, the rule does not fire (the reverse shape, or a genuine round-trip of the
 *       value, is compared normally).</li>
 *   <li>Only when <em>every</em> expected row of the sheet carries the <em>identical</em>
 *       {@code LiveTo} value (the uniform-vestigial shape). A per-row divergent {@code LiveTo}
 *       (a real staggered end-of-life) leaves the column in place and still fails.</li>
 * </ul>
 */
public final class LiveToVestigialRule implements NormalisationRule {

    private static final String SHEET = "AuthorisationCaseState";
    private static final String COLUMN = "LiveTo";

    @Override
    public String name() {
        return "LIVE_TO_VESTIGIAL";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName) || expectedRows.isEmpty()) {
            return;
        }
        // Narrow to the expected-carries / actual-omits shape: if any actual row carries a LiveTo,
        // the value is round-tripped (or a genuine reverse difference) and must be compared.
        for (Map<String, Object> row : actualRows) {
            if (hasValue(row.get(COLUMN))) {
                return;
            }
        }
        // Require the uniform-vestigial shape: every expected row carries the identical LiveTo. A
        // missing LiveTo on any row, or two different values, is not the uniform vestige and fails.
        String uniform = null;
        for (Map<String, Object> row : expectedRows) {
            Object value = row.get(COLUMN);
            if (!hasValue(value)) {
                return;
            }
            String text = String.valueOf(value);
            if (uniform == null) {
                uniform = text;
            } else if (!uniform.equals(text)) {
                return;
            }
        }
        int removed = 0;
        for (Map<String, Object> row : expectedRows) {
            if (row.remove(COLUMN) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            recorder.record(this, "removed uniform vestigial 'LiveTo=" + uniform + "' from "
                + removed + " expected '" + SHEET + "' row(s) the SDK does not emit");
        }
    }

    private static boolean hasValue(Object value) {
        return value != null && !(value instanceof String && ((String) value).isEmpty());
    }
}
