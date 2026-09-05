package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Objects;

/**
 * CONDITIONAL_POST_STATE — a <b>semantic, accepted</b> concession (not cosmetic): forgives an
 * expected {@code PostConditionState} that carries a conditional or multi-target transition
 * (grammar {@code state(condition):priority}, or {@code ;}-separated alternatives) where the actual
 * generated side carries the SDK's single primary state.
 *
 * <p>The CCD data store DOES honour conditional post-states at runtime: {@code CasePostStateService}
 * sorts the entries by priority and {@code CasePostStateEvaluationService} evaluates each entry's
 * JEXL condition first-match-wins, falling back to the default. The SDK's {@code EventBuilder}, by
 * contrast, models a single static post-state per event, so the converter emits only the primary
 * state (the first token's state ID — see {@code DefaultDefinitionLinker#parsePostState}) and the
 * conditional alternatives are dropped. The maintainer accepts this collapse knowingly; a migrating
 * team that relies on the runtime transition must reimplement it in an {@code aboutToSubmit} callback
 * that returns {@code .state(<computed state>)} (see nfdiv's {@code Applicant1Resubmit} /
 * {@code SubmitConditionalOrder} for the SDK-native pattern).</p>
 *
 * <p>Scoped tightly to mask no regression: it fires <b>only</b> on the {@code CaseEvent} sheet,
 * <b>only</b> when the expected value is genuinely conditional/multi (contains {@code (} or
 * {@code ;}), and <b>only</b> when the actual value equals that expression's primary state. The
 * reverse shape (the generator emitting a conditional the input did not carry) and a primary that
 * disagrees with the actual both still fail. It never touches the actual side, so a generator
 * regression that changed the emitted primary is still caught.</p>
 */
public final class ConditionalPostStateRule implements NormalisationRule {

    private static final String CASE_EVENT_SHEET = "CaseEvent";
    private static final String POST_CONDITION_COLUMN = "PostConditionState";
    private static final String WILDCARD = "*";

    @Override
    public String name() {
        return "CONDITIONAL_POST_STATE";
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
        Object expected = expectedRow.get(POST_CONDITION_COLUMN);
        if (!(expected instanceof String)) {
            return;
        }
        String expectedExpr = ((String) expected).trim();
        if (!isConditionalOrMulti(expectedExpr)) {
            return;
        }
        String primary = primaryState(expectedExpr);
        if (primary == null) {
            return;
        }
        Object actual = actualRow.get(POST_CONDITION_COLUMN);
        if (!(actual instanceof String) || !Objects.equals(primary, ((String) actual).trim())) {
            return;
        }
        // The generated side carries exactly the primary the SDK derives from this conditional
        // expression: canonicalise the expected side to it so the accepted collapse is forgiven.
        expectedRow.put(POST_CONDITION_COLUMN, actual);
        recorder.record(this, "forgave conditional/multi PostConditionState '" + expectedExpr
            + "' collapsing to its SDK primary state '" + primary + "' on sheet '" + sheetName
            + "' row [" + rowKey + "]");
    }

    private boolean isConditionalOrMulti(String value) {
        return value.contains("(") || value.contains(";");
    }

    /**
     * The primary state ID the SDK emits for a conditional/multi post-state: the first
     * {@code ;}-separated token with its {@code (condition)} / {@code :priority} decorations stripped,
     * mirroring {@code DefaultDefinitionLinker#stripStateDecorations} + {@code parsePostState}. Returns
     * null when the first token is empty or the wildcard {@code *} (which has its own rule).
     */
    private String primaryState(String value) {
        String firstToken = value.split(";", -1)[0].trim();
        int paren = firstToken.indexOf('(');
        String state;
        if (paren >= 0) {
            state = firstToken.substring(0, paren).trim();
        } else {
            int colon = firstToken.indexOf(':');
            state = colon >= 0 ? firstToken.substring(0, colon).trim() : firstToken;
        }
        if (state.isEmpty() || WILDCARD.equals(state)) {
            return null;
        }
        return state;
    }
}
