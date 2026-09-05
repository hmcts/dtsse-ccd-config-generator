package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * CASE_EVENT_RETRIES — reconciles the {@code RetriesTimeout*} columns on the {@code CaseEvent}
 * sheet with how the config generator emits callback retries.
 *
 * <p>Two purely-structural differences are absorbed, both on the {@code CaseEvent} sheet only:</p>
 * <ul>
 *   <li><b>About-to-start column name</b>: real definitions carry the about-to-start retry under
 *       {@code RetriesTimeoutAboutToStartEvent}, whereas {@code CaseEventGenerator} hard-codes
 *       the {@code URL}-form name {@code RetriesTimeoutURLAboutToStartEvent} (the about-to-submit
 *       and submitted phases already agree on the {@code URL} form). Both name the same phase's
 *       retry, so the non-URL spelling is renamed to the URL form on both sides before matching.
 *       The retry <em>value</em> is left untouched and still compares exactly.</li>
 *   <li><b>Retries without a callback</b>: the generator only writes a {@code RetriesTimeout*}
 *       column when the phase's callback URL is present ({@code CaseEventGenerator} guards the
 *       retry on the callback being enabled), because a retry policy for a callback that is not
 *       configured is inert — the store never invokes a callback, so the retry count never
 *       applies. A retry value on a row that carries no callback URL for that phase is therefore
 *       dropped on both sides.</li>
 * </ul>
 *
 * <p>Only these two reconciliations are made. When a callback URL <em>is</em> present for the
 * phase, a differing retry value still fails, so genuine retry-policy differences are caught.</p>
 */
public final class CaseEventRetriesRule implements NormalisationRule {

    private static final String SHEET = "CaseEvent";

    /** Per phase: {callbackUrlColumn, canonicalRetriesColumn, aliasRetriesColumn}. */
    private static final String[][] PHASES = {
        {"CallBackURLAboutToStartEvent", "RetriesTimeoutURLAboutToStartEvent",
            "RetriesTimeoutAboutToStartEvent"},
        {"CallBackURLAboutToSubmitEvent", "RetriesTimeoutURLAboutToSubmitEvent",
            "RetriesTimeoutAboutToSubmitEvent"},
        {"CallBackURLSubmittedEvent", "RetriesTimeoutURLSubmittedEvent",
            "RetriesTimeoutSubmittedEvent"},
    };

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
        int renamed = 0;
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            for (String[] phase : PHASES) {
                String callbackColumn = phase[0];
                String canonical = phase[1];
                String alias = phase[2];
                if (row.containsKey(alias) && !row.containsKey(canonical)) {
                    row.put(canonical, row.remove(alias));
                    renamed++;
                }
                boolean callbackPresent = row.get(callbackColumn) != null
                    && !(row.get(callbackColumn) instanceof String s && s.isBlank());
                if (!callbackPresent && row.remove(canonical) != null) {
                    dropped++;
                }
            }
        }
        if (renamed > 0 || dropped > 0) {
            recorder.record(this, "renamed " + renamed + " about-to-start retry column(s) and"
                + " dropped " + dropped + " callback-less retry value(s) (" + side + ")");
        }
    }
}
