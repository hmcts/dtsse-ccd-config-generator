package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACCESS_CONTROL_EXPANSION — expands the array-shaped authorisation rows the definition-processor
 * flattens at build time into the per-role flat rows the generator emits.
 *
 * <p>Rationale: on the {@code Authorisation*} sheets a hand-written definition may encode a grant
 * to many roles in a single row using one of two array shorthands that
 * {@code ccd-definition-processor}'s {@code access-control-transformer.js} expands before import:
 * <ul>
 *   <li>a {@code UserRoles} array — {@code {CaseFieldID, UserRoles: [[A],[B],…], CRUD}} — expands
 *       to one {@code {CaseFieldID, UserRole: X, CRUD}} row per role; and</li>
 *   <li>an {@code AccessControl} array — {@code {CaseEventID, AccessControl: [{UserRoles:[…],
 *       CRUD}, …]}} — expands to one row per (role, CRUD) pair, dropping the {@code AccessControl}
 *       column.</li>
 * </ul>
 * The transformer then renames {@code UserRole} to {@code AccessProfile}. The SDK's generators only
 * ever emit the already-flat per-role rows, so this rule performs the identical expansion on both
 * sides before matching — reproducing the canonical import semantics rather than masking a
 * difference. It runs before {@link KeyAliasRule} in the rule order, and (like the transformer)
 * emits the flattened role into the {@code UserRole} column, which {@code KeyAliasRule} then
 * canonicalises to {@code AccessProfile}.</p>
 */
public final class AccessControlExpansionRule implements NormalisationRule {

    private static final String ACCESS_CONTROL = "AccessControl";
    private static final String USER_ROLES = "UserRoles";
    private static final String USER_ROLE = "UserRole";
    private static final String CRUD = "CRUD";

    @Override
    public String name() {
        return "ACCESS_CONTROL_EXPANSION";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        // Runs as a whole-definition rule (before any normaliseSheets and before other rules'
        // normaliseDefinition hooks, being first in the rule order) so that later whole-definition
        // rules — notably IMMUTABLE_FIELD_CR, which reads AuthorisationCaseEvent role grants — see
        // the already-flattened per-role rows rather than the array shorthands.
        for (Map.Entry<String, List<Map<String, Object>>> sheet : expected.entrySet()) {
            maybeExpand(sheet.getKey(), "expected", sheet.getValue(), recorder);
        }
        for (Map.Entry<String, List<Map<String, Object>>> sheet : actual.entrySet()) {
            maybeExpand(sheet.getKey(), "actual", sheet.getValue(), recorder);
        }
    }

    private void maybeExpand(String sheetName, String side, List<Map<String, Object>> rows,
                             RuleApplications recorder) {
        // Every Authorisation* sheet — including AuthorisationComplexType — carries the same array
        // shorthands the processor flattens at build time. The converter now emits flat per-role
        // grantComplexType rows for AuthorisationComplexType (see CoreConfigEmitter), so this sheet
        // is expanded here too: the input's UserRoles[]/AccessControl[] shapes flatten to the flat
        // per-role rows the generator produces, and both sides then match on the sheet's primary key.
        if (!sheetName.startsWith("Authorisation")) {
            return;
        }
        expand(sheetName, side, rows, recorder);
    }

    private void expand(String sheetName, String side, List<Map<String, Object>> rows,
                        RuleApplications recorder) {
        List<Map<String, Object>> result = new ArrayList<>();
        int expanded = 0;
        for (Map<String, Object> row : rows) {
            Object accessControl = row.get(ACCESS_CONTROL);
            Object userRoles = row.get(USER_ROLES);
            if (accessControl instanceof List && !((List<?>) accessControl).isEmpty()) {
                for (Object element : (List<?>) accessControl) {
                    if (!(element instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ac = (Map<String, Object>) element;
                    Object roles = ac.get(USER_ROLES);
                    Object crud = ac.get(CRUD);
                    if (roles instanceof List) {
                        for (Object role : (List<?>) roles) {
                            Map<String, Object> flat = new LinkedHashMap<>(row);
                            flat.remove(ACCESS_CONTROL);
                            flat.put(USER_ROLE, role);
                            flat.put(CRUD, crud);
                            result.add(flat);
                        }
                    }
                }
                expanded++;
            } else if (userRoles instanceof List && !((List<?>) userRoles).isEmpty()) {
                Object crud = row.get(CRUD);
                for (Object role : (List<?>) userRoles) {
                    Map<String, Object> flat = new LinkedHashMap<>(row);
                    flat.remove(USER_ROLES);
                    flat.put(USER_ROLE, role);
                    flat.put(CRUD, crud);
                    result.add(flat);
                }
                expanded++;
            } else {
                result.add(row);
            }
        }
        if (expanded > 0) {
            rows.clear();
            rows.addAll(result);
            recorder.record(this, "expanded " + expanded + " array-shaped authorisation row(s) on"
                + " sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
