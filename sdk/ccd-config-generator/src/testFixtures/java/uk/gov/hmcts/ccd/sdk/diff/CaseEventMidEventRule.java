package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * CASE_EVENT_MID_EVENT — drops the mid-event callback columns from the {@code CaseEvent} sheet.
 *
 * <p>Rationale: a mid-event callback fires per wizard <em>page</em>, so in CCD it is a
 * {@code CaseEventToFields} property (the {@code CallBackURLMidEvent} on the page's first field),
 * not a {@code CaseEvent} property. The SDK models it exactly that way — {@code CaseEventGenerator}
 * writes only the AboutToStart/AboutToSubmit/Submitted callbacks on a CaseEvent row and never a
 * mid-event one, while {@code CaseEventToFieldsGenerator.applyMidEventCallback} writes it per page.
 * Some hand-written definitions (sscs) additionally carry a {@code CallBackURLMidEvent} column on
 * the CaseEvent sheet; the definition-store importer has no such CaseEvent field, so that copy is
 * vestigial and ignored (the callback is driven by the CaseEventToFields row, which round-trips via
 * {@link PageLabelPropagationRule}). This rule drops {@code CallBackURLMidEvent} and its retry
 * policy from the CaseEvent sheet on both sides so the ignored column does not surface as a diff.
 * It touches only the CaseEvent sheet and only those two columns; the real about-to-start/
 * about-to-submit/submitted callback URL columns are compared exactly like any other column (the
 * converter carries them through verbatim, so both sides hold identical raw values).</p>
 */
public final class CaseEventMidEventRule implements NormalisationRule {

    private static final String SHEET = "CaseEvent";
    private static final List<String> MID_EVENT_COLUMNS =
        List.of("CallBackURLMidEvent", "RetriesTimeoutURLMidEvent");

    @Override
    public String name() {
        return "CASE_EVENT_MID_EVENT";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        drop("expected", expectedRows, recorder);
        drop("actual", actualRows, recorder);
    }

    private void drop(String side, List<Map<String, Object>> rows, RuleApplications recorder) {
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            for (String column : MID_EVENT_COLUMNS) {
                if (row.remove(column) != null) {
                    dropped++;
                }
            }
        }
        if (dropped > 0) {
            recorder.record(this, "dropped " + dropped
                + " vestigial mid-event column(s) from the CaseEvent sheet (" + side + ")");
        }
    }
}
