package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * USER_PROFILE_EXCLUDED — drops the {@code UserProfile} sheet from comparison entirely, on both
 * sides.
 *
 * <p><strong>Maintainer decision (2026-07-16)</strong>, following
 * {@code docs/userprofile-investigation.md}: a {@code UserProfile} row maps one IDAM user to a
 * workbasket-filter default, which is per-user, per-environment deployment data, not case-type
 * model — and grepping current {@code ccd-case-ui-toolkit}/{@code rpx-xui-webapp} found no code
 * path that still consumes it (XUI computes the case-list default a different way). Fixture rows
 * also carry real staff/contractor emails that do not belong in a portable Java definition. The
 * SDK still has no API to express per-user workbasket defaults, so the converter continues to
 * hard-fail a definition carrying the sheet via the existing {@code OMITTED_FAIL}/
 * {@code UNSUPPORTED_SHEET} gap (see {@code DefaultDefinitionLinker#failUnsupportedSheet}) unless
 * {@code --allow-gaps} is set — this rule only stops the resulting expected-only
 * {@code UserProfile} rows from recurring as round-trip residuals in every fixture that ships the
 * sheet.</p>
 */
public final class UserProfileExcludedRule implements NormalisationRule {

    private static final String SHEET = "UserProfile";

    @Override
    public String name() {
        return "USER_PROFILE_EXCLUDED";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        int dropped = expectedRows.size() + actualRows.size();
        if (dropped == 0) {
            return;
        }
        expectedRows.clear();
        actualRows.clear();
        recorder.record(this, "excluded " + dropped + " '" + SHEET
            + "' row(s) from comparison (sheet dropped from both sides)");
    }
}
