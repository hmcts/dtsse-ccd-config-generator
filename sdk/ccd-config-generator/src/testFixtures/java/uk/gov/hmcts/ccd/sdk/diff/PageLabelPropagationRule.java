package uk.gov.hmcts.ccd.sdk.diff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PAGE_LABEL_PROPAGATION — propagates page-level attributes to every field row of the same
 * page before rows are matched.
 *
 * <p>Rationale: {@code PageLabel} (and the other page-scoped columns below) describe the wizard
 * page a {@code CaseEventToFields} row belongs to, not the individual field. Hand-written
 * definitions typically set it once, on the first field of a page, and leave it out on
 * subsequent fields of the same page; the config generator instead repeats it on every field
 * row of the page. Both are equivalent once the page is assembled — the definition store reads
 * whichever row carries the value. This rule runs pre-match: for every page (grouped by
 * {@code CaseEventID}+{@code PageID}) on each side independently, it fills a page-scoped
 * attribute from whichever row on that page carries it, so a per-row comparison afterwards no
 * longer sees a spurious difference. It never invents a value neither row has, and it never
 * copies across pages or across sides, so a genuinely different label on one page still fails.
 */
public final class PageLabelPropagationRule implements NormalisationRule {

    private static final String SHEET = "CaseEventToFields";
    private static final String PAGE_LABEL = "PageLabel";
    // Page-scoped attributes the config generator writes on the page's first field only, whereas a
    // hand-written definition may carry them on any field of the page — propagating them per page
    // lets the per-row comparison match either placement. The mid-event callback URL and its retry
    // policy are NOT in this set: the converter emits no SDK mid-event wiring, so it carries the
    // input's CallBackURLMidEvent / RetriesTimeoutURLMidEvent through verbatim per field row (the
    // CaseEventToFields column graft keyed by CaseFieldID). Both sides therefore hold the input's
    // exact per-row placement and compare equal directly; propagating them would instead pick a
    // page's "first" mid-event value, which — on the rare page carrying two different mid-event
    // URLs (a fixture inconsistency; only one fires) — differs by side and spuriously mismatches.
    private static final Set<String> PAGE_SCOPED_COLUMNS = Set.of(
        "PageLabel", "PageShowCondition");

    @Override
    public String name() {
        return "PAGE_LABEL_PROPAGATION";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        propagate(sheetName, "expected", expectedRows, recorder);
        propagate(sheetName, "actual", actualRows, recorder);
    }

    private void propagate(String sheetName, String side, List<Map<String, Object>> rows,
                           RuleApplications recorder) {
        Map<String, Map<String, Object>> pageValuesByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String pageKey = row.get("CaseEventID") + "|" + row.get("PageID");
            Map<String, Object> pageValues = pageValuesByKey.computeIfAbsent(
                pageKey, key -> new LinkedHashMap<>());
            for (String column : PAGE_SCOPED_COLUMNS) {
                if (row.containsKey(column) && !pageValues.containsKey(column)) {
                    pageValues.put(column, row.get(column));
                }
            }
        }

        int propagated = 0;
        for (Map<String, Object> row : rows) {
            String pageKey = row.get("CaseEventID") + "|" + row.get("PageID");
            Map<String, Object> pageValues = pageValuesByKey.get(pageKey);
            for (String column : PAGE_SCOPED_COLUMNS) {
                if (!row.containsKey(column) && pageValues.containsKey(column)) {
                    row.put(column, pageValues.get(column));
                    propagated++;
                } else if (PAGE_LABEL.equals(column) && row.containsKey(column)
                    && pageValues.containsKey(column)
                    && !pageValues.get(column).equals(row.get(column))) {
                    // A page may legitimately carry a different PageLabel on each field row, but the
                    // page label is page-scoped: CCD renders one label per wizard page and the SDK's
                    // page model holds a single label (the page's first field's). Collapse every row
                    // of the page to that first-field label on each side so the per-field variation
                    // — which no runtime honours — does not surface as a spurious mismatch. Both
                    // sides collapse to their own page's first value; the converter picks the same
                    // first-field label the generator emits, so the pages then align.
                    row.put(column, pageValues.get(column));
                    propagated++;
                }
            }
        }
        if (propagated > 0) {
            recorder.record(this, "propagated " + propagated
                + " page-scoped column value(s) on sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
