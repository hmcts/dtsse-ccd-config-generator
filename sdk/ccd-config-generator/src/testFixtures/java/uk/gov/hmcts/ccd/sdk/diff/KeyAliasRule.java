package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * KEY_ALIAS — canonicalises column names that CCD treats as synonyms.
 *
 * <p>Rationale: the definition-store importer accepts {@code UserRole} as a legacy alias of
 * {@code AccessProfile} on the Authorisation*, RoleToAccessProfiles and the search/workbasket
 * sheets (SearchInputFields, SearchResultFields, WorkBasketInputFields, WorkBasketResultFields),
 * and older hand-written definitions use {@code Name} where the generator emits {@code Label} on
 * the CaseField sheet. These are pure spelling differences with identical runtime semantics, so
 * both sides are canonicalised: {@code UserRole} is renamed to {@code AccessProfile}, and on
 * CaseField rows a {@code Name} value is copied to {@code Label} when {@code Label} is absent
 * (or dropped when it duplicates {@code Label}). A {@code Name} that contradicts
 * {@code Label} is left alone so the mismatch still fails. The SDK's role-scoped search-field
 * builder ({@code SearchBuilder.field(id, label, role)}) emits the column as {@code UserRole};
 * hand-written definitions spell the same scoping as {@code AccessProfile}.</p>
 *
 * <p>It also canonicalises the {@code CaseTypeId}/{@code CaseTypeID} column-header casing on every
 * sheet. The importer reads the case-type column case-insensitively — a single real definition
 * ships both spellings across its sheets (ET's {@code AuthorisationCaseField} uses
 * {@code CaseTypeId} while its {@code CaseField} uses {@code CaseTypeID}, yet both import into the
 * same case type). The generator always emits {@code CaseTypeID}, so the lower-case-d spelling is
 * renamed to the canonical {@code CaseTypeID} on both sides.</p>
 */
public final class KeyAliasRule implements NormalisationRule {

    private static final String USER_ROLE = "UserRole";
    private static final String ACCESS_PROFILE = "AccessProfile";
    private static final String CASE_TYPE_ID_LOWER = "CaseTypeId";
    private static final String CASE_TYPE_ID = "CaseTypeID";

    @Override
    public String name() {
        return "KEY_ALIAS";
    }

    private static boolean isRoleAliasSheet(String sheetName) {
        return sheetName.startsWith("Authorisation")
            || "RoleToAccessProfiles".equals(sheetName)
            || "SearchInputFields".equals(sheetName)
            || "SearchResultFields".equals(sheetName)
            || "WorkBasketInputFields".equals(sheetName)
            || "WorkBasketResultFields".equals(sheetName)
            || "CaseTypeTab".equals(sheetName);
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        canonicaliseCaseTypeId(sheetName, "expected", expectedRows, recorder);
        canonicaliseCaseTypeId(sheetName, "actual", actualRows, recorder);
        if (isRoleAliasSheet(sheetName)) {
            canonicaliseUserRole(sheetName, "expected", expectedRows, recorder);
            canonicaliseUserRole(sheetName, "actual", actualRows, recorder);
        }
        if ("CaseField".equals(sheetName)) {
            canonicaliseNameToLabel(sheetName, "expected", expectedRows, recorder);
            canonicaliseNameToLabel(sheetName, "actual", actualRows, recorder);
        }
    }

    private void canonicaliseCaseTypeId(String sheetName, String side,
                                        List<Map<String, Object>> rows, RuleApplications recorder) {
        int renamed = 0;
        for (Map<String, Object> row : rows) {
            if (row.containsKey(CASE_TYPE_ID_LOWER) && !row.containsKey(CASE_TYPE_ID)) {
                row.put(CASE_TYPE_ID, row.remove(CASE_TYPE_ID_LOWER));
                renamed++;
            }
        }
        if (renamed > 0) {
            recorder.record(this, "renamed 'CaseTypeId' to 'CaseTypeID' on " + renamed
                + " row(s) of sheet '" + sheetName + "' (" + side + ")");
        }
    }

    private void canonicaliseUserRole(String sheetName, String side, List<Map<String, Object>> rows,
                                      RuleApplications recorder) {
        int renamed = 0;
        for (Map<String, Object> row : rows) {
            if (row.containsKey(USER_ROLE) && !row.containsKey(ACCESS_PROFILE)) {
                row.put(ACCESS_PROFILE, row.remove(USER_ROLE));
                renamed++;
            }
        }
        if (renamed > 0) {
            recorder.record(this, "renamed 'UserRole' to 'AccessProfile' on " + renamed
                + " row(s) of sheet '" + sheetName + "' (" + side + ")");
        }
    }

    private void canonicaliseNameToLabel(String sheetName, String side, List<Map<String, Object>> rows,
                                         RuleApplications recorder) {
        int canonicalised = 0;
        for (Map<String, Object> row : rows) {
            if (!row.containsKey("Name")) {
                continue;
            }
            if (!row.containsKey("Label")) {
                row.put("Label", row.remove("Name"));
                canonicalised++;
            } else if (Objects.equals(row.get("Name"), row.get("Label"))) {
                row.remove("Name");
                canonicalised++;
            }
        }
        if (canonicalised > 0) {
            recorder.record(this, "canonicalised 'Name' to 'Label' on " + canonicalised
                + " row(s) of sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
