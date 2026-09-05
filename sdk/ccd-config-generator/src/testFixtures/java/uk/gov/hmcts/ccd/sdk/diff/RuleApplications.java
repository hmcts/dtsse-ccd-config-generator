package uk.gov.hmcts.ccd.sdk.diff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects a record of every normalisation-rule application during a comparison so callers
 * can log which tolerances fired. Duplicate messages are recorded once.
 */
public final class RuleApplications {

    private final Set<String> seen = new LinkedHashSet<>();
    private final List<String> applications = new ArrayList<>();

    /**
     * Record one application of a rule.
     *
     * @param rule   the rule that fired
     * @param detail human-readable description of what was normalised
     */
    public void record(NormalisationRule rule, String detail) {
        String message = rule.name() + ": " + detail;
        if (seen.add(message)) {
            applications.add(message);
        }
    }

    /**
     * All recorded applications, in the order they first occurred.
     */
    public List<String> asList() {
        return List.copyOf(applications);
    }
}
