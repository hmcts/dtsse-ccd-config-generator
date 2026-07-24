package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STATE_DESCRIPTION — forgives a {@code Description} that merely repeats the row's {@code Name}
 * on the {@code State} and {@code CaseEvent} sheets.
 *
 * <p>Rationale: the config generator defaults both a state's and an event's {@code Description}
 * column to its {@code Name} when no description is supplied ({@code Event.build} copies
 * {@code name} into {@code description} when the latter is null; the state generator does the
 * same), whereas a hand-written definition simply omits {@code Description} in that case. This
 * is a cosmetic default with no behavioural difference, so a {@code Description} equal to the
 * row's {@code Name} is dropped when the other side omits {@code Description}. A
 * {@code Description} that genuinely differs from {@code Name} still fails as normal.</p>
 */
public final class StateDescriptionRule implements NormalisationRule {

    private static final Set<String> SHEETS = Set.of("State", "CaseEvent");

    @Override
    public String name() {
        return "STATE_DESCRIPTION";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!SHEETS.contains(sheetName)) {
            return;
        }
        forgiveDefaultedDescription(sheetName, expectedRow, actualRow, recorder);
        forgiveDefaultedDescription(sheetName, actualRow, expectedRow, recorder);
    }

    private void forgiveDefaultedDescription(String sheetName, Map<String, Object> present,
                                             Map<String, Object> other, RuleApplications recorder) {
        if (present.containsKey("Description") && !other.containsKey("Description")
            && Objects.equals(present.get("Description"), present.get("Name"))) {
            present.remove("Description");
            recorder.record(this, "removed 'Description' defaulted to 'Name' where the other side"
                + " omits it on sheet '" + sheetName + "'");
        }
    }
}
