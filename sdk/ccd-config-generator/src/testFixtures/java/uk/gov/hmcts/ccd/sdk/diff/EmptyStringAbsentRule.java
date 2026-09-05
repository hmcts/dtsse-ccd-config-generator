package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * EMPTY_STRING_ABSENT — treats an empty/blank string on one side as equivalent to the column
 * being entirely absent on the other.
 *
 * <p>Rationale: the definition-store importer treats a missing column and a column carrying an
 * empty string identically — neither constrains anything. The generator nonetheless emits some
 * columns as {@code ""} where a hand-written definition simply omits them (for example
 * {@code UserRole} on an unrestricted CaseTypeTab row, or {@code PreConditionState(s)} on an
 * initial/create CaseEvent row). This rule drops any column whose value is blank on one side
 * when the same column is entirely absent on the other, on any sheet. It also drops a column both
 * sides carry when both hold a blank string or JSON null (in any combination) — the generator
 * emits some optional columns as an explicit {@code null} while the definition carries {@code ""}
 * ({@code Categories.ParentCategoryID}), and the importer stores neither. A non-blank value on
 * either side still fails.</p>
 */
public final class EmptyStringAbsentRule implements NormalisationRule {

    @Override
    public String name() {
        return "EMPTY_STRING_ABSENT";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        dropBlankWhenAbsent(sheetName, expectedRow, actualRow, recorder);
        dropBlankWhenAbsent(sheetName, actualRow, expectedRow, recorder);
        dropWhenBothBlank(sheetName, expectedRow, actualRow, recorder);
    }

    private void dropWhenBothBlank(String sheetName, Map<String, Object> expectedRow,
                                   Map<String, Object> actualRow, RuleApplications recorder) {
        // When both sides carry the column but each holds a blank string or JSON null (in either
        // combination — e.g. the generator emits Categories.ParentCategoryID as an explicit null
        // where the definition carries an empty string), the values import identically: the
        // importer stores neither. A non-blank value on either side is left untouched and still
        // fails.
        for (String column : List.copyOf(expectedRow.keySet())) {
            if (!actualRow.containsKey(column)) {
                continue;
            }
            if (isBlankOrNull(expectedRow.get(column)) && isBlankOrNull(actualRow.get(column))) {
                expectedRow.remove(column);
                actualRow.remove(column);
                recorder.record(this, "removed mutually blank/null '" + column
                    + "' on sheet '" + sheetName + "'");
            }
        }
    }

    private static boolean isBlankOrNull(Object value) {
        return value == null || (value instanceof String && ((String) value).isBlank());
    }

    private void dropBlankWhenAbsent(String sheetName, Map<String, Object> present,
                                     Map<String, Object> other, RuleApplications recorder) {
        for (String column : List.copyOf(present.keySet())) {
            if (other.containsKey(column)) {
                continue;
            }
            Object value = present.get(column);
            // A JSON null and a blank string are both equivalent to the column being absent — the
            // importer stores neither. The generator emits some optional columns as an explicit
            // null (e.g. Categories.ParentCategoryID) where a hand-written definition omits them.
            if (value == null || (value instanceof String && ((String) value).isBlank())) {
                present.remove(column);
                recorder.record(this, "removed blank/null '" + column
                    + "' where the other side omits it on sheet '" + sheetName + "'");
            }
        }
    }
}
