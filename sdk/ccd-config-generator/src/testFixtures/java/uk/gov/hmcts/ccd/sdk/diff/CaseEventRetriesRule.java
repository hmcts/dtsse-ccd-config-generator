package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * CASE_EVENT_RETRIES — drops the {@code RetriesTimeout*} values on the {@code CaseEvent} sheet that
 * the definition store never reads, on both sides.
 *
 * <p>{@code EventParser} reads each hook's retries through {@code WebhookParser}, from one column per
 * hook: {@code RetriesTimeoutAboutToStartEvent}, {@code RetriesTimeoutURLAboutToSubmitEvent} and
 * {@code RetriesTimeoutURLSubmittedEvent}. Two kinds of value are therefore inert:</p>
 * <ul>
 *   <li><b>Other spellings</b>: {@code RetriesTimeoutURLAboutToStartEvent} (the {@code URL} form the
 *       other hooks use), {@code RetriesTimeoutAboutToSubmitEvent} and
 *       {@code RetriesTimeoutSubmittedEvent} name no column the importer reads, so a value under them
 *       never takes effect. Definitions do carry the first; the converter drops it and the generator
 *       never writes it.</li>
 *   <li><b>Retries without a callback</b>: {@code WebhookParser} reads a hook's retries only when its
 *       {@code CallBackURL*} is present, so a retry value on a row with no URL for that hook is never
 *       read either.</li>
 * </ul>
 *
 * <p>Only these values are dropped. When a callback URL is present, its read retries column still
 * compares exactly, so genuine retry-policy differences are caught.</p>
 */
public final class CaseEventRetriesRule implements NormalisationRule {

    private static final String SHEET = "CaseEvent";

    /** Per hook: {callbackUrlColumn, readRetriesColumn}. */
    private static final String[][] HOOKS = {
        {"CallBackURLAboutToStartEvent", "RetriesTimeoutAboutToStartEvent"},
        {"CallBackURLAboutToSubmitEvent", "RetriesTimeoutURLAboutToSubmitEvent"},
        {"CallBackURLSubmittedEvent", "RetriesTimeoutURLSubmittedEvent"},
    };

    private static final List<String> UNREAD_COLUMNS = List.of(
        "RetriesTimeoutURLAboutToStartEvent",
        "RetriesTimeoutAboutToSubmitEvent",
        "RetriesTimeoutSubmittedEvent");

    @Override
    public String name() {
        return "CASE_EVENT_RETRIES";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        reconcile("expected", expectedRows, recorder);
        reconcile("actual", actualRows, recorder);
    }

    private void reconcile(String side, List<Map<String, Object>> rows, RuleApplications recorder) {
        int unread = 0;
        int callbackless = 0;
        for (Map<String, Object> row : rows) {
            for (String column : UNREAD_COLUMNS) {
                if (row.remove(column) != null) {
                    unread++;
                }
            }
            for (String[] hook : HOOKS) {
                boolean callbackPresent = row.get(hook[0]) != null
                    && !(row.get(hook[0]) instanceof String s && s.isBlank());
                if (!callbackPresent && row.remove(hook[1]) != null) {
                    callbackless++;
                }
            }
        }
        if (unread > 0 || callbackless > 0) {
            recorder.record(this, "dropped " + unread + " retry value(s) under a column the importer"
                + " does not read and " + callbackless + " callback-less retry value(s) (" + side + ")");
        }
    }
}
