package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Set;

/**
 * SHOW_CONDITION_WHITESPACE — trims leading/trailing whitespace on show-condition columns.
 *
 * <p>Rationale: a show condition is a boolean expression the definition-store evaluates after
 * tokenising; surrounding whitespace is insignificant to the parser (it is not part of any field
 * name or literal). Hand-written definitions frequently leave a trailing space on these
 * expressions (ia's {@code isAdmin="Yes" }), whereas the SDK emits the trimmed form. This rule
 * trims both sides of the recognised show-condition columns before comparison, so an otherwise
 * identical expression matches. It only trims — an expression that differs by anything other than
 * surrounding whitespace still fails, and non-condition columns are untouched.</p>
 */
public final class ShowConditionWhitespaceRule implements NormalisationRule {

    private static final Set<String> SHOW_CONDITION_COLUMNS = Set.of(
        "FieldShowCondition", "PageShowCondition", "TabShowCondition", "EventEnablingCondition",
        "ShowCondition");

    @Override
    public String name() {
        return "SHOW_CONDITION_WHITESPACE";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        trim(sheetName, expectedRow, recorder);
        trim(sheetName, actualRow, recorder);
    }

    private void trim(String sheetName, Map<String, Object> row, RuleApplications recorder) {
        for (String column : SHOW_CONDITION_COLUMNS) {
            Object value = row.get(column);
            if (value instanceof String) {
                String trimmed = ((String) value).trim();
                if (!trimmed.equals(value)) {
                    row.put(column, trimmed);
                    recorder.record(this, "trimmed whitespace on '" + column + "' on sheet '"
                        + sheetName + "'");
                }
            }
        }
    }
}
