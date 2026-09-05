package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * TAB_READ_INJECTION — forgives the read-only {@code AuthorisationCaseField} rows the SDK's
 * {@code AuthorisationCaseFieldGenerator} unconditionally injects for fields on unrestricted
 * tabs.
 *
 * <p>Rationale: for every role that has been granted any permission at all (via an event field
 * grant), the generator's tab loop additionally injects {@code Permission.R} on every field
 * that appears in a {@code CaseTypeTab} not restricted to a specific role — see
 * {@code AuthorisationCaseFieldGenerator#write}, which iterates
 * {@code fieldRolePermissions.columnKeySet()} (roles already known to have grants) and adds
 * read for any tab field the role does not already hold a permission on. This is unconditional
 * SDK behaviour, identical whether the case type is authored via the converter or directly as
 * hand-written {@code CCDConfig} Java, and cannot be suppressed by definition data — the
 * {@code AccessClassComputer} that derives access classes explicitly records it as an
 * {@code AUTH_NOT_DERIVABLE} gap because no access-class annotation can remove a permission the
 * SDK adds automatically.</p>
 *
 * <p>The rule forgives two shapes of this injection:</p>
 * <ul>
 *   <li>an <b>unmatched actual-only row</b> whose {@code CRUD} is exactly {@code R} (the sole
 *       permission the tab loop injects) for a role that already holds at least one other grant in
 *       the actual sheet (mirroring the generator's precondition that the role already appears in
 *       {@code fieldRolePermissions}); and</li>
 *   <li>a <b>matched row carrying a surplus {@code R}</b> — the generator's tab loop runs
 *       <em>before</em> {@code addPermissionsFromFields} grafts the {@code @CCD(access)} grants, so
 *       for a tab field the role holds an access-class grant on but no event places, the loop sees
 *       no existing permission and adds {@code R}, which then merges with the access-class CRUD
 *       (e.g. input {@code CUD} → generated {@code CRUD}). This is forgiven only when the field
 *       appears on a {@code CaseTypeTab} not restricted to another role and the surplus is exactly
 *       {@code R}.</li>
 * </ul>
 * <p>An extra row with any other CRUD, a surplus containing anything but {@code R}, or a field on
 * no unrestricted tab, still fails — that is not explained by tab-read injection.</p>
 */
public final class TabReadInjectionRule implements NormalisationRule {

    private static final String SHEET = "AuthorisationCaseField";
    private static final String CASE_TYPE_TAB = "CaseTypeTab";
    private static final String READ_ONLY = "R";

    @Override
    public String name() {
        return "TAB_READ_INJECTION";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        // Forgive a matched-row surplus R for fields on an unrestricted tab. Derived here (a
        // whole-definition hook) because it needs the CaseTypeTab sheet as well as the auth sheet.
        Set<String> tabFields = unrestrictedTabFields(expected);
        if (tabFields.isEmpty()) {
            return;
        }
        List<Map<String, Object>> expectedRows = expected.get(SHEET);
        List<Map<String, Object>> actualRows = actual.get(SHEET);
        if (expectedRows == null || actualRows == null) {
            return;
        }
        Map<String, Map<String, Object>> actualByKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : actualRows) {
            actualByKey.put(authKey(row), row);
        }
        int aligned = 0;
        for (Map<String, Object> expectedRow : expectedRows) {
            if (!tabFields.contains(str(expectedRow.get("CaseFieldID")))) {
                continue;
            }
            Map<String, Object> actualRow = actualByKey.get(authKey(expectedRow));
            if (actualRow == null) {
                continue;
            }
            Set<Character> exp = perms(expectedRow.get("CRUD"));
            Set<Character> act = perms(actualRow.get("CRUD"));
            Set<Character> surplus = new LinkedHashSet<>(act);
            surplus.removeAll(exp);
            if (act.containsAll(exp) && surplus.equals(Set.of('R'))) {
                expectedRow.put("CRUD", actualRow.get("CRUD"));
                aligned++;
            }
        }
        if (aligned > 0) {
            recorder.record(this, "forgave surplus tab-read R on " + aligned
                + " matched AuthorisationCaseField grant(s)");
        }
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        int removed = 0;
        for (Iterator<Map<String, Object>> iterator = actualRows.iterator(); iterator.hasNext(); ) {
            Map<String, Object> row = iterator.next();
            if (!READ_ONLY.equals(row.get("CRUD"))) {
                continue;
            }
            if (hasMatch(expectedRows, row)) {
                continue;
            }
            if (!hasOtherGrantForRole(actualRows, row)) {
                continue;
            }
            iterator.remove();
            removed++;
        }
        if (removed > 0) {
            recorder.record(this, "removed " + removed
                + " generator-injected read-only tab grant row(s) from sheet '" + sheetName + "'");
        }
    }

    /**
     * Fields that appear on a {@code CaseTypeTab} not restricted to a specific role (the SDK's tab
     * loop injects R for these for every already-granted role). A tab is role-restricted when any
     * of its rows carries a non-empty {@code AccessProfile}/{@code UserRole}; such a tab's fields
     * only receive R for that one role, so treating them as unrestricted would over-forgive — they
     * are excluded here (the whole-tab role having been propagated by CASE_TYPE_TAB already).
     */
    private Set<String> unrestrictedTabFields(Map<String, List<Map<String, Object>>> expected) {
        List<Map<String, Object>> tabRows = expected.get(CASE_TYPE_TAB);
        if (tabRows == null) {
            return Set.of();
        }
        Set<String> restrictedTabs = new LinkedHashSet<>();
        for (Map<String, Object> row : tabRows) {
            Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
            if (role != null && !str(role).isEmpty()) {
                restrictedTabs.add(str(row.get("TabID")));
            }
        }
        Set<String> fields = new LinkedHashSet<>();
        for (Map<String, Object> row : tabRows) {
            if (!restrictedTabs.contains(str(row.get("TabID")))) {
                fields.add(str(row.get("CaseFieldID")));
            }
        }
        return fields;
    }

    private static String authKey(Map<String, Object> row) {
        Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
        return str(row.get("CaseFieldID")) + " " + str(role);
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

    private boolean hasMatch(List<Map<String, Object>> expectedRows, Map<String, Object> row) {
        Object fieldId = row.get("CaseFieldID");
        Object role = row.get("AccessProfile");
        for (Map<String, Object> candidate : expectedRows) {
            if (Objects.equals(candidate.get("CaseFieldID"), fieldId)
                && Objects.equals(candidate.get("AccessProfile"), role)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOtherGrantForRole(List<Map<String, Object>> actualRows, Map<String, Object> row) {
        Object fieldId = row.get("CaseFieldID");
        Object role = row.get("AccessProfile");
        for (Map<String, Object> candidate : actualRows) {
            if (candidate == row) {
                continue;
            }
            if (Objects.equals(candidate.get("AccessProfile"), role)
                && !Objects.equals(candidate.get("CaseFieldID"), fieldId)) {
                return true;
            }
        }
        return false;
    }
}
