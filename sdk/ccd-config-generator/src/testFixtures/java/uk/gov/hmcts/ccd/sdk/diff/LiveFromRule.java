package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * LIVE_FROM — removes the {@code LiveFrom} column from every row on both sides.
 *
 * <p>Rationale: {@code LiveFrom} is a mandatory importer column whose value carries no
 * behavioural meaning once the definition is live; the generator stamps a fixed default while
 * hand-written definitions use arbitrary historical dates. {@code LiveTo} is deliberately
 * <em>not</em> normalised here — an end-of-life date is behavioural, so a mismatch must fail.
 * The one narrow exception is {@link LiveToVestigialRule}, which forgives a uniform, sheet-wide
 * vestigial {@code LiveTo} on {@code AuthorisationCaseState} (probate's dead
 * {@code LiveTo=01/01/2020} on every row); a per-row divergent {@code LiveTo} still fails.</p>
 */
public final class LiveFromRule implements NormalisationRule {

    @Override
    public String name() {
        return "LIVE_FROM";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        removeLiveFrom(sheetName, "expected", expectedRows, recorder);
        removeLiveFrom(sheetName, "actual", actualRows, recorder);
    }

    private void removeLiveFrom(String sheetName, String side, List<Map<String, Object>> rows,
                                RuleApplications recorder) {
        int removed = 0;
        for (Map<String, Object> row : rows) {
            if (row.containsKey("LiveFrom")) {
                row.remove("LiveFrom");
                removed++;
            }
        }
        if (removed > 0) {
            recorder.record(this, "removed 'LiveFrom' from " + removed
                + " row(s) of sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
