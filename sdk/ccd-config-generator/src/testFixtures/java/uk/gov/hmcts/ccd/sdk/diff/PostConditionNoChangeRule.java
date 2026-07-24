package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Objects;

/**
 * POST_CONDITION_NO_CHANGE — treats {@code PostConditionState=*} as equivalent to the event's
 * single pre-state on the {@code CaseEvent} sheet.
 *
 * <p>Rationale: {@code *} in {@code PostConditionState} means "no state change" at runtime. For
 * an event with exactly one {@code PreConditionState(s)} value, "no change" and "ends in that
 * same single pre-state" are the same runtime behaviour. The SDK's generator (see
 * {@code CaseEventGenerator#getPostStateString}) always writes the concrete post-state — it
 * never emits {@code *} for a single-state event, because the SDK's
 * {@code EventTypeBuilder#forState(S)} models "stay in this state" as pre-state == post-state
 * rather than as a wildcard. Both representations import identically, so this rule only fires
 * when the two states genuinely agree with one of the sides using the wildcard spelling; a
 * post-state that disagrees with the single pre-state still fails.</p>
 */
public final class PostConditionNoChangeRule implements NormalisationRule {

    private static final String CASE_EVENT_SHEET = "CaseEvent";
    private static final String WILDCARD = "*";
    private static final String PRE_CONDITION_COLUMN = "PreConditionState(s)";
    private static final String POST_CONDITION_COLUMN = "PostConditionState";

    @Override
    public String name() {
        return "POST_CONDITION_NO_CHANGE";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!CASE_EVENT_SHEET.equals(sheetName)) {
            return;
        }
        canonicaliseWildcard(sheetName, expectedRow, actualRow, recorder);
        canonicaliseWildcard(sheetName, actualRow, expectedRow, recorder);
    }

    private void canonicaliseWildcard(String sheetName, Map<String, Object> wildcardSide,
                                      Map<String, Object> concreteSide, RuleApplications recorder) {
        if (!WILDCARD.equals(wildcardSide.get(POST_CONDITION_COLUMN))) {
            return;
        }
        Object preState = wildcardSide.get(PRE_CONDITION_COLUMN);
        if (!(preState instanceof String) || ((String) preState).isEmpty()
            || WILDCARD.equals(preState) || ((String) preState).contains(";")) {
            return;
        }
        Object concretePost = concreteSide.get(POST_CONDITION_COLUMN);
        if (!Objects.equals(preState, concretePost)) {
            return;
        }
        wildcardSide.put(POST_CONDITION_COLUMN, concretePost);
        recorder.record(this, "canonicalised 'PostConditionState=*' to the single pre-state '"
            + concretePost + "' on sheet '" + sheetName + "'");
    }
}
