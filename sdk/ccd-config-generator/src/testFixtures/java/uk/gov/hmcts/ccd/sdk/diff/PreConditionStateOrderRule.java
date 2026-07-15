package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PRE_CONDITION_STATE_ORDER — treats a CaseEvent's {@code PreConditionState(s)} as an unordered
 * set of states.
 *
 * <p>Rationale: {@code PreConditionState(s)} is a {@code ;}-delimited list of the states an event
 * is available from. The definition store treats it as a set — the event is offered in each named
 * state regardless of the order they are listed. The config generator emits the states sorted
 * alphabetically ({@code CaseEventGenerator.getPreStateString} calls {@code .sorted()}), whereas a
 * hand-written definition lists them in whatever order the author chose (often the workflow
 * order). To compare the two as sets, both sides' token lists are sorted alphabetically before
 * matching.</p>
 *
 * <p>The wildcard {@code *} (every state) is left untouched, and the value is only re-ordered,
 * never changed: an event available from a genuinely different set of states still fails because
 * sorting two different sets yields two different strings.</p>
 */
public final class PreConditionStateOrderRule implements NormalisationRule {

    private static final String SHEET = "CaseEvent";
    private static final String COLUMN = "PreConditionState(s)";

    @Override
    public String name() {
        return "PRE_CONDITION_STATE_ORDER";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        sort("expected", expectedRows, recorder);
        sort("actual", actualRows, recorder);
    }

    private void sort(String side, List<Map<String, Object>> rows, RuleApplications recorder) {
        int sorted = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(COLUMN);
            if (!(value instanceof String s) || s.isBlank() || "*".equals(s.trim())) {
                continue;
            }
            String canonical = Arrays.stream(s.split(";"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .sorted()
                .collect(Collectors.joining(";"));
            if (!canonical.equals(s)) {
                row.put(COLUMN, canonical);
                sorted++;
            }
        }
        if (sorted > 0) {
            recorder.record(this, "sorted " + sorted + " PreConditionState(s) value(s) (" + side
                + ")");
        }
    }
}
