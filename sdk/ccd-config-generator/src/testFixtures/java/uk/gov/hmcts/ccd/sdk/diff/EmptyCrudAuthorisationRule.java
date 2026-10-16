package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMPTY_CRUD_AUTHORISATION — drops an authorisation row whose {@code CRUD} is empty, on the
 * {@code AuthorisationCaseType}/{@code AuthorisationCaseField}/{@code AuthorisationCaseEvent}/
 * {@code AuthorisationCaseState} sheets.
 *
 * <p>Rationale: the definition-store importer marks {@code CRUD} a <em>required</em> column on the
 * authorisation sheets and rejects any row whose {@code CRUD} is blank
 * ({@code DefinitionDataItem.findAttribute} throws a {@code MapperException} for a missing required
 * value before an ACL entity is ever created). An empty-{@code CRUD} row therefore grants nothing
 * and is indistinguishable from the row being absent. The SDK's
 * {@code AuthorisationCaseTypeGenerator} nonetheless emits one row per non-case {@code UserRole}
 * enum constant, defaulting {@code CRUD} to that role's (possibly empty) case-type permission — so
 * a role that is a valid {@code UserRole} but holds no case-type grant surfaces as a spurious
 * {@code CRUD=} row the input never carried. This rule removes an empty-{@code CRUD} row that
 * exists on only one side; a row present on both sides, or a non-empty {@code CRUD}, is compared
 * as normal and still fails on a genuine difference.</p>
 */
public final class EmptyCrudAuthorisationRule implements NormalisationRule {

    private static final Set<String> AUTHORISATION_SHEETS = Set.of(
        "AuthorisationCaseType", "AuthorisationCaseField",
        "AuthorisationCaseEvent", "AuthorisationCaseState");

    @Override
    public String name() {
        return "EMPTY_CRUD_AUTHORISATION";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!AUTHORISATION_SHEETS.contains(sheetName)) {
            return;
        }
        dropEmptyCrudOnlyOnOneSide(sheetName, expectedRows, actualRows, recorder);
        dropEmptyCrudOnlyOnOneSide(sheetName, actualRows, expectedRows, recorder);
    }

    private void dropEmptyCrudOnlyOnOneSide(String sheetName, List<Map<String, Object>> rows,
                                            List<Map<String, Object>> other,
                                            RuleApplications recorder) {
        Iterator<Map<String, Object>> iterator = rows.iterator();
        while (iterator.hasNext()) {
            Map<String, Object> row = iterator.next();
            Object crud = row.get("CRUD");
            if (!(crud instanceof String) || !((String) crud).isEmpty()) {
                continue;
            }
            if (hasCounterpart(row, other)) {
                // The other side carries the same row (same identity columns): compare directly.
                continue;
            }
            iterator.remove();
            recorder.record(this, "removed importer-rejected empty-CRUD row for access profile '"
                + accessProfile(row) + "' on sheet '" + sheetName + "'");
        }
    }

    private boolean hasCounterpart(Map<String, Object> row, List<Map<String, Object>> other) {
        // Match on the row's full identity — every column except CRUD (and the meaningless
        // LiveFrom) — so a counterpart on a DIFFERENT field/event/state with the same role does
        // not spuriously suppress the drop. Whichever role column the row uses is normalised first.
        String profile = accessProfile(row);
        if (profile == null) {
            return false;
        }
        for (Map<String, Object> candidate : other) {
            if (profile.equals(accessProfile(candidate)) && sameIdentity(row, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two rows share the same identity: the same value for every column except the role
     * column (compared separately), {@code CRUD}, and the meaningless {@code LiveFrom}.
     */
    private boolean sameIdentity(Map<String, Object> row, Map<String, Object> candidate) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String column = entry.getKey();
            if ("CRUD".equals(column) || "AccessProfile".equals(column) || "UserRole".equals(column)
                || "LiveFrom".equals(column)) {
                continue;
            }
            if (!java.util.Objects.equals(entry.getValue(), candidate.get(column))) {
                return false;
            }
        }
        return true;
    }

    private String accessProfile(Map<String, Object> row) {
        Object profile = row.get("AccessProfile");
        if (profile == null) {
            profile = row.get("UserRole");
        }
        return profile == null ? null : String.valueOf(profile);
    }
}
