package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;

/**
 * NUMERIC_STRINGS — tolerates numeric values serialised as JSON numbers on one side and as
 * strings on the other.
 *
 * <p>Rationale: spreadsheet-derived definitions and the generator disagree about whether
 * numeric columns (page numbers, retry counts, display orders that survive other rules) are
 * JSON numbers or strings — the importer coerces both identically. When a matched row pair
 * holds a number on one side and the same value as a string on the other, the number is
 * canonicalised to its string form. Genuinely different values still fail.</p>
 */
public final class NumericStringsRule implements NormalisationRule {

    @Override
    public String name() {
        return "NUMERIC_STRINGS";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        canonicalise(sheetName, expectedRow, actualRow, recorder);
        canonicalise(sheetName, actualRow, expectedRow, recorder);
    }

    private void canonicalise(String sheetName, Map<String, Object> numericSide,
                              Map<String, Object> stringSide, RuleApplications recorder) {
        for (Map.Entry<String, Object> entry : numericSide.entrySet()) {
            Object numeric = entry.getValue();
            Object other = stringSide.get(entry.getKey());
            if (numeric instanceof Number && other instanceof String
                && String.valueOf(numeric).equals(other)) {
                entry.setValue(String.valueOf(numeric));
                recorder.record(this, "canonicalised numeric '" + entry.getKey()
                    + "' to string on sheet '" + sheetName + "'");
            }
        }
    }
}
