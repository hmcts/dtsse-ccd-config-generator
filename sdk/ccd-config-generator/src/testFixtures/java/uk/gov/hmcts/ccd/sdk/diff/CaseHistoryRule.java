package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CASE_HISTORY — forgives the {@code caseHistory} rows the generator always injects.
 *
 * <p>Rationale: the config generator unconditionally adds a {@code caseHistory}
 * CaseHistoryViewer field (with its tab and authorisation rows) to every case type, whereas
 * hand-written definitions frequently leave it out. Two shapes are forgiven:</p>
 * <ul>
 *   <li>On non-authorisation sheets (CaseField, CaseTypeTab, …) and when the expected side has no
 *       {@code caseHistory} row at all on the sheet, every injected {@code caseHistory} row is
 *       removed from the actual side.</li>
 *   <li>On {@code AuthorisationCaseField}, the generator injects {@code CRU} on {@code caseHistory}
 *       for <strong>every</strong> role that holds any field grant (see
 *       {@code AuthorisationCaseFieldGenerator#write}). A hand-written definition typically declares
 *       {@code caseHistory} for only a subset of roles (often at a narrower CRUD such as {@code R}).
 *       Per role, an actual {@code caseHistory} grant whose CRUD is exactly the injected {@code CRU}
 *       and whose expected counterpart is absent or a subset of {@code CRU} is aligned to {@code CRU}
 *       (created if absent); any other CRUD is left to fail as a real difference.</li>
 * </ul>
 */
public final class CaseHistoryRule implements NormalisationRule {

    private static final String CASE_HISTORY_ID = "caseHistory";
    private static final String AUTH_CASE_FIELD = "AuthorisationCaseField";
    private static final String INJECTED_CRUD = "CRU";
    private static final Set<Character> INJECTED = Set.of('C', 'R', 'U');

    @Override
    public String name() {
        return "CASE_HISTORY";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (AUTH_CASE_FIELD.equals(sheetName)) {
            forgiveAuthCaseHistory(sheetName, expectedRows, actualRows, recorder);
            return;
        }
        boolean expectedHasCaseHistory = expectedRows.stream().anyMatch(this::isCaseHistoryRow);
        if (expectedHasCaseHistory) {
            return;
        }
        for (Iterator<Map<String, Object>> iterator = actualRows.iterator(); iterator.hasNext(); ) {
            Map<String, Object> row = iterator.next();
            if (isCaseHistoryRow(row)) {
                iterator.remove();
                recorder.record(this, "removed generator-injected 'caseHistory' row from sheet '"
                    + sheetName + "' (actual): " + row);
            }
        }
    }

    /**
     * Aligns the expected side to the generator's unconditional {@code caseHistory=CRU} injection,
     * per role: for each actual {@code caseHistory} grant whose CRUD is exactly {@code CRU}, if the
     * expected grant is absent or a subset of {@code CRU}, set (or create) it to {@code CRU} so the
     * pair compares equal. A wider or otherwise-differing actual CRUD is left untouched.
     */
    private void forgiveAuthCaseHistory(String sheetName,
                                        List<Map<String, Object>> expectedRows,
                                        List<Map<String, Object>> actualRows,
                                        RuleApplications recorder) {
        Map<String, Map<String, Object>> expectedByRole = new LinkedHashMap<>();
        for (Map<String, Object> row : expectedRows) {
            if (isCaseHistoryRow(row)) {
                expectedByRole.put(role(row), row);
            }
        }
        int forgiven = 0;
        for (Map<String, Object> actualRow : actualRows) {
            if (!isCaseHistoryRow(actualRow) || !INJECTED_CRUD.equals(str(actualRow.get("CRUD")))) {
                continue;
            }
            Map<String, Object> expectedRow = expectedByRole.get(role(actualRow));
            Set<Character> expectedPerms = expectedRow == null
                ? Set.of() : perms(expectedRow.get("CRUD"));
            if (!INJECTED.containsAll(expectedPerms)) {
                // Expected holds a permission the injection never grants (e.g. D) — a real diff.
                continue;
            }
            if (expectedRow == null) {
                Map<String, Object> created = new LinkedHashMap<>(actualRow);
                expectedRows.add(created);
                expectedByRole.put(role(actualRow), created);
            } else {
                expectedRow.put("CRUD", INJECTED_CRUD);
            }
            forgiven++;
        }
        if (forgiven > 0) {
            recorder.record(this, "aligned " + forgiven + " generator-injected 'caseHistory' CRU"
                + " grant(s) on sheet '" + sheetName + "'");
        }
    }

    private static String role(Map<String, Object> row) {
        Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
        return role == null ? "" : String.valueOf(role);
    }

    private static Set<Character> perms(Object crud) {
        Set<Character> set = new LinkedHashSet<>();
        if (crud != null) {
            for (char ch : String.valueOf(crud).toUpperCase().toCharArray()) {
                if (ch == 'C' || ch == 'R' || ch == 'U' || ch == 'D') {
                    set.add(ch);
                }
            }
        }
        return set;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isCaseHistoryRow(Map<String, Object> row) {
        return CASE_HISTORY_ID.equals(row.get("ID")) || CASE_HISTORY_ID.equals(row.get("CaseFieldID"));
    }
}
