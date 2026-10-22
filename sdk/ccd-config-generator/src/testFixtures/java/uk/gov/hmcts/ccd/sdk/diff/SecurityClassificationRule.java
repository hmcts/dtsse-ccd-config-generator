package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SECURITY_CLASSIFICATION_CASE — canonicalises the letter case of the
 * {@code SecurityClassification} column.
 *
 * <p>Rationale: the config generator always writes {@code SecurityClassification} with the
 * mixed-case spelling {@code Public} (see {@code CaseFieldGenerator.getField} and the various
 * generators), whereas real hand-written definitions overwhelmingly use the upper-case spelling
 * {@code PUBLIC}. The CCD definition store treats the classification token case-insensitively —
 * {@code PUBLIC}, {@code Public} and {@code public} all import to the same
 * {@code SecurityClassification.PUBLIC} enum — so the case difference is purely cosmetic.</p>
 *
 * <p>This rule upper-cases the value on both sides so a per-column comparison no longer sees a
 * spurious difference. It only touches letter case: a genuinely different classification (e.g.
 * {@code PRIVATE} vs {@code PUBLIC}, or {@code RESTRICTED} vs {@code PUBLIC}) still fails,
 * because upper-casing leaves distinct tokens distinct.</p>
 */
public final class SecurityClassificationRule implements NormalisationRule {

    private static final String COLUMN = "SecurityClassification";

    @Override
    public String name() {
        return "SECURITY_CLASSIFICATION_CASE";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        upperCase(sheetName, "expected", expectedRows, recorder);
        upperCase(sheetName, "actual", actualRows, recorder);
    }

    private void upperCase(String sheetName, String side, List<Map<String, Object>> rows,
                           RuleApplications recorder) {
        int changed = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(COLUMN);
            if (value instanceof String s) {
                String upper = s.toUpperCase(Locale.ROOT);
                if (!upper.equals(s)) {
                    row.put(COLUMN, upper);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            recorder.record(this, "upper-cased " + changed + " SecurityClassification value(s) on"
                + " sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
