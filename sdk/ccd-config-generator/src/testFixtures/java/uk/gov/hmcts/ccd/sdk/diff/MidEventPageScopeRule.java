package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MID_EVENT_PAGE_SCOPE — compares a wizard page's mid-event callback once per page rather than once
 * per {@code CaseEventToFields} row.
 *
 * <p>The definition store reads a page's mid-event callback in {@code AbstractDisplayGroupParser}:
 * of the page's rows (grouped by {@code CaseEventID}+{@code PageID}) it takes the first that carries
 * a {@code CallBackURLMidEvent}, and reads the URL and {@code RetriesTimeoutURLMidEvent} from that row
 * alone. Hand-written definitions put the URL on one row of the page or repeat it on several; the
 * generator writes it on the page's first row only. This rule runs pre-match and, on each side
 * independently, replaces both columns on every row of a page with the values the importer would
 * read for that page (or removes them when the page has no URL), so a page with the same effective
 * callback compares equal wherever its rows carried it. A page whose effective URL or retries differ
 * still fails on each of its rows.</p>
 */
public final class MidEventPageScopeRule implements NormalisationRule {

    private static final String SHEET = "CaseEventToFields";
    private static final String URL = "CallBackURLMidEvent";
    private static final String RETRIES = "RetriesTimeoutURLMidEvent";

    @Override
    public String name() {
        return "MID_EVENT_PAGE_SCOPE";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        apply("expected", expectedRows, recorder);
        apply("actual", actualRows, recorder);
    }

    private void apply(String side, List<Map<String, Object>> rows, RuleApplications recorder) {
        Map<String, List<Map<String, Object>>> pages = new LinkedHashMap<>();
        boolean any = false;
        for (Map<String, Object> row : rows) {
            pages.computeIfAbsent(row.get("CaseEventID") + "|" + row.get("PageID"),
                key -> new ArrayList<>()).add(row);
            any |= row.containsKey(URL) || row.containsKey(RETRIES);
        }
        if (!any) {
            return;
        }
        int changed = 0;
        for (List<Map<String, Object>> page : pages.values()) {
            Map<String, Object> source = page.stream()
                .filter(row -> present(row.get(URL)))
                .findFirst()
                .orElse(null);
            Object url = source == null ? null : source.get(URL);
            Object retries = source == null || !present(source.get(RETRIES)) ? null : source.get(RETRIES);
            for (Map<String, Object> row : page) {
                changed += set(row, URL, url) + set(row, RETRIES, retries);
            }
        }
        if (changed > 0) {
            recorder.record(this, "scoped " + changed + " mid-event column value(s) to their page ("
                + side + ")");
        }
    }

    private static int set(Map<String, Object> row, String column, Object value) {
        Object before = value == null ? row.remove(column) : row.put(column, value);
        return value == null ? (before == null ? 0 : 1) : (value.equals(before) ? 0 : 1);
    }

    private static boolean present(Object value) {
        return value != null && !(value instanceof String s && s.isBlank());
    }
}
