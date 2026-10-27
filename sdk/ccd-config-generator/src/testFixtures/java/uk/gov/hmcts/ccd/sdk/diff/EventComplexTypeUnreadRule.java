package uk.gov.hmcts.ccd.sdk.diff;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EVENT_COMPLEX_TYPE_UNREAD — drops {@code EventToComplexTypes} ({@code CaseEventToComplexTypes})
 * rows the definition store never reads, on both sides.
 *
 * <p>{@code EventParser.parseCaseEventComplexTypes} (ccd-definition-store-api,
 * {@code excel-importer/.../parser/EventParser.java}) groups the sheet's rows by
 * {@code (CaseEventID, CaseFieldID)}, then walks each event's {@code CaseEventToFields} placements and
 * attaches a group only to a placement whose {@code DisplayContext} is {@code COMPLEX}. Rows for a
 * field the event places in any other context, or does not place at all, are never attached to
 * anything, so they have no effect on the imported definition. The converter drops them with an
 * advisory gap rather than emitting them.</p>
 *
 * <p>Each side is judged against its own {@code CaseEventToFields} sheet, so a generated side that
 * lost a {@code COMPLEX} placement still fails on that placement, and on the member rows the input
 * still has. Rows naming no event or no field are left alone so they still compare.</p>
 */
public final class EventComplexTypeUnreadRule implements NormalisationRule {

    private static final String SHEET = "EventToComplexTypes";
    private static final String CASE_EVENT_TO_FIELDS = "CaseEventToFields";
    private static final String CASE_EVENT_ID = "CaseEventID";
    private static final String CASE_FIELD_ID = "CaseFieldID";
    private static final String DISPLAY_CONTEXT = "DisplayContext";

    @Override
    public String name() {
        return "EVENT_COMPLEX_TYPE_UNREAD";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        int droppedExpected = dropUnread(expected);
        int droppedActual = dropUnread(actual);
        if (droppedExpected + droppedActual > 0) {
            recorder.record(this, "dropped " + droppedExpected + " expected / " + droppedActual
                + " actual " + SHEET + " row(s) whose field the event does not place as COMPLEX"
                + " (never read by the definition store)");
        }
    }

    private static int dropUnread(Map<String, List<Map<String, Object>>> sheets) {
        List<Map<String, Object>> rows = sheets.get(SHEET);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Set<String> complexPlacements = new HashSet<>();
        for (Map<String, Object> row : sheets.getOrDefault(CASE_EVENT_TO_FIELDS, List.of())) {
            String context = str(row.get(DISPLAY_CONTEXT));
            if (context != null && "COMPLEX".equals(context.trim().toUpperCase(Locale.ROOT))) {
                complexPlacements.add(key(row));
            }
        }
        int before = rows.size();
        rows.removeIf(row -> str(row.get(CASE_EVENT_ID)) != null
            && str(row.get(CASE_FIELD_ID)) != null
            && !complexPlacements.contains(key(row)));
        return before - rows.size();
    }

    private static String key(Map<String, Object> row) {
        return str(row.get(CASE_EVENT_ID)) + "\u001f" + str(row.get(CASE_FIELD_ID));
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }
}
