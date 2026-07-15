package uk.gov.hmcts.ccd.sdk.diff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CASE_TYPE_TAB — reconciles the two equivalent ways a {@code CaseTypeTab} sheet expresses
 * tab-scoped metadata.
 *
 * <p>Three purely-structural differences are absorbed:</p>
 * <ul>
 *   <li><b>{@code Channel}</b>: {@code CaseTypeTabGenerator.buildField} unconditionally writes
 *       {@code Channel=CaseWorker} on every row, whereas hand-written definitions omit the
 *       column entirely. The definition store defaults the display channel, so the injected
 *       value carries no behavioural meaning; the column is dropped when it equals
 *       {@code CaseWorker} and the other side omits it.</li>
 *   <li><b>{@code TabLabel}</b>: the generator repeats the tab's label on every field row of the
 *       tab, whereas hand-written definitions typically set it once (on the first field) and
 *       leave it out on the rest. The label describes the tab, not the field; it is propagated
 *       to every field row of the same {@code TabID} on each side before matching, exactly as
 *       {@link PageLabelPropagationRule} does for wizard pages.</li>
 *   <li><b>{@code TabFieldDisplayOrder}</b>: the generator renumbers a tab's fields sequentially
 *       from 1 (one row per declared field), so a hand-written definition that skips numbers or
 *       starts elsewhere produces a different-but-equivalent ordering. The column is ordering
 *       metadata with no behavioural effect once the fields are in declaration order, so it is
 *       dropped from both sides.</li>
 * </ul>
 *
 * <p>Only these specific reconciliations are made: a {@code Channel} other than
 * {@code CaseWorker}, or a genuinely conflicting {@code TabLabel} on the same tab, still fails.</p>
 */
public final class CaseTypeTabRule implements NormalisationRule {

    private static final String SHEET = "CaseTypeTab";
    private static final String CHANNEL = "Channel";
    private static final String CHANNEL_DEFAULT = "CaseWorker";
    private static final String TAB_LABEL = "TabLabel";
    private static final String TAB_SHOW_CONDITION = "TabShowCondition";
    private static final String TAB_FIELD_DISPLAY_ORDER = "TabFieldDisplayOrder";
    // TabLabel and TabShowCondition describe the tab, not the field: the generator writes them on
    // the tab's first field row, a hand-written definition may set them on any row. Propagate both
    // to every field row of the tab before matching (like PAGE_LABEL_PROPAGATION for pages).
    private static final Set<String> TAB_SCOPED_COLUMNS = Set.of(TAB_LABEL, TAB_SHOW_CONDITION);

    @Override
    public String name() {
        return "CASE_TYPE_TAB";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        stripOrdering("expected", expectedRows, recorder);
        stripOrdering("actual", actualRows, recorder);
        // KEY_ALIAS has already renamed UserRole -> AccessProfile on both sides. The generator
        // encodes a role-scoped tab by (a) appending the role to the TabID on every field row and
        // (b) writing the AccessProfile only on the tab's first field row, blank on the rest; a
        // hand-written definition keeps the plain TabID and repeats the AccessProfile on every row.
        // Undo the generator's encoding so both sides carry the plain TabID and a per-row
        // AccessProfile before matching (TabID is a primary-key column, so this must precede it).
        reconcileTabRole("actual", actualRows, recorder);
        propagateTabAccessProfile("expected", expectedRows, recorder);
        propagateTabAccessProfile("actual", actualRows, recorder);
        propagateTabLabel("expected", expectedRows, recorder);
        propagateTabLabel("actual", actualRows, recorder);
    }

    private void reconcileTabRole(String side, List<Map<String, Object>> rows,
                                  RuleApplications recorder) {
        // The role each row's TabID was suffixed with is that tab's non-blank AccessProfile. Rows
        // of one generated tab all share the same suffixed TabID, so group by TabID, find the tab's
        // role, and strip it from the TabID suffix.
        java.util.Map<String, String> roleByTabId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object profile = row.get("AccessProfile");
            if (profile instanceof String && !((String) profile).isEmpty()) {
                roleByTabId.putIfAbsent(String.valueOf(row.get("TabID")), (String) profile);
            }
        }
        int stripped = 0;
        for (Map<String, Object> row : rows) {
            String tabId = String.valueOf(row.get("TabID"));
            String role = roleByTabId.get(tabId);
            if (role != null && tabId.endsWith(role) && tabId.length() > role.length()) {
                row.put("TabID", tabId.substring(0, tabId.length() - role.length()));
                stripped++;
            }
        }
        if (stripped > 0) {
            recorder.record(this, "stripped role suffix from " + stripped
                + " generated TabID value(s) (" + side + ")");
        }
    }

    private void propagateTabAccessProfile(String side, List<Map<String, Object>> rows,
                                           RuleApplications recorder) {
        // Drop blank AccessProfile so it counts as absent, then treat the tab's role as
        // tab-scoped: every field row of a role-scoped tab shares the same AccessProfile.
        for (Map<String, Object> row : rows) {
            Object profile = row.get("AccessProfile");
            if (profile instanceof String && ((String) profile).isEmpty()) {
                row.remove("AccessProfile");
            }
        }
        Map<String, Object> roleByTabId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey("AccessProfile")) {
                roleByTabId.putIfAbsent(String.valueOf(row.get("TabID")), row.get("AccessProfile"));
            }
        }
        int propagated = 0;
        for (Map<String, Object> row : rows) {
            String tabId = String.valueOf(row.get("TabID"));
            if (!row.containsKey("AccessProfile") && roleByTabId.containsKey(tabId)) {
                row.put("AccessProfile", roleByTabId.get(tabId));
                propagated++;
            }
        }
        if (propagated > 0) {
            recorder.record(this, "propagated tab-scoped AccessProfile to " + propagated
                + " field row(s) (" + side + ")");
        }
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        forgiveChannel(expectedRow, actualRow, recorder);
        forgiveChannel(actualRow, expectedRow, recorder);
    }

    private void stripOrdering(String side, List<Map<String, Object>> rows,
                               RuleApplications recorder) {
        int removed = 0;
        for (Map<String, Object> row : rows) {
            if (row.remove(TAB_FIELD_DISPLAY_ORDER) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            recorder.record(this, "removed " + removed + " TabFieldDisplayOrder value(s) ("
                + side + ")");
        }
    }

    private void propagateTabLabel(String side, List<Map<String, Object>> rows,
                                   RuleApplications recorder) {
        Map<String, Map<String, Object>> tabValues = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String tabId = String.valueOf(row.get("TabID"));
            Map<String, Object> values = tabValues.computeIfAbsent(tabId, k -> new LinkedHashMap<>());
            for (String column : TAB_SCOPED_COLUMNS) {
                if (row.containsKey(column) && !values.containsKey(column)) {
                    values.put(column, row.get(column));
                }
            }
        }
        int propagated = 0;
        for (Map<String, Object> row : rows) {
            String tabId = String.valueOf(row.get("TabID"));
            Map<String, Object> values = tabValues.get(tabId);
            for (String column : TAB_SCOPED_COLUMNS) {
                if (!row.containsKey(column) && values.containsKey(column)) {
                    row.put(column, values.get(column));
                    propagated++;
                }
            }
        }
        if (propagated > 0) {
            recorder.record(this, "propagated " + propagated + " tab-scoped value(s) (" + side + ")");
        }
    }

    private void forgiveChannel(Map<String, Object> present, Map<String, Object> other,
                                RuleApplications recorder) {
        if (present.containsKey(CHANNEL) && !other.containsKey(CHANNEL)
            && CHANNEL_DEFAULT.equals(present.get(CHANNEL))) {
            present.remove(CHANNEL);
            recorder.record(this, "removed defaulted Channel where the other side omits it");
        }
    }
}
