package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
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
 *
 * <p>The trim runs in {@link #normaliseSheets}, before rows are keyed, because
 * {@code FieldShowCondition} is part of the {@code EventToComplexTypes} primary key: a leading
 * space would otherwise produce two different row keys for the same logical row and neither side
 * would find its match. Trimming earlier is strictly safer than trimming on matched pairs — every
 * row is normalised whether or not it pairs up.</p>
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
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        for (Map<String, Object> row : expectedRows) {
            trim(sheetName, row, recorder);
        }
        for (Map<String, Object> row : actualRows) {
            trim(sheetName, row, recorder);
        }
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
