package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;

/**
 * Outcome of a {@link NormalisingCcdConfigComparator} comparison: whether the two definitions
 * are semantically equivalent, every unexplained difference, and every normalisation rule
 * that fired along the way.
 */
public final class ComparisonResult {

    private final List<String> failures;
    private final List<String> appliedRules;

    ComparisonResult(List<String> failures, List<String> appliedRules) {
        this.failures = List.copyOf(failures);
        this.appliedRules = List.copyOf(appliedRules);
    }

    /**
     * True when the definitions are equivalent after normalisation.
     */
    public boolean matches() {
        return failures.isEmpty();
    }

    /**
     * Human-readable descriptions of every unexplained difference (sheet, row key, column,
     * expected vs actual). Empty when {@link #matches()}.
     */
    public List<String> getFailures() {
        return failures;
    }

    /**
     * Every normalisation-rule application recorded during the comparison, so callers can
     * log which tolerances fired.
     */
    public List<String> getAppliedRules() {
        return appliedRules;
    }

    /**
     * Formatted multi-line report of failures and applied rules.
     */
    public String report() {
        StringBuilder report = new StringBuilder();
        if (matches()) {
            report.append("CCD definitions are semantically equivalent.\n");
        } else {
            report.append("CCD definitions differ: ").append(failures.size())
                .append(" unexplained difference(s).\n");
            for (String failure : failures) {
                report.append("  - ").append(failure).append('\n');
            }
        }
        if (!appliedRules.isEmpty()) {
            report.append("Normalisation rules applied:\n");
            for (String applied : appliedRules) {
                report.append("  * ").append(applied).append('\n');
            }
        }
        return report.toString();
    }

    @Override
    public String toString() {
        return report();
    }
}
