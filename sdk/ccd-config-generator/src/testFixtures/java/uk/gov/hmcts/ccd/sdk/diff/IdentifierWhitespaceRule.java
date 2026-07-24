package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IDENTIFIER_WHITESPACE — trims leading/trailing whitespace on identifier-role columns.
 *
 * <p>Rationale: the definition-store importer trims surrounding whitespace from the identifier
 * cells it uses for cross-sheet lookups. The proof is a resolving reference across the trim: ia's
 * {@code ComplexTypes} member declares {@code FieldTypeParameter="imageRenderingLocation "} (a
 * trailing space) referencing the {@code FixedLists} entry whose {@code ID} is
 * {@code "imageRenderingLocation"} (no space) — the field only resolves to its FixedList after the
 * importer trims, so a whitespace-only difference on an identifier cell imports identically. The
 * SDK derives its member/type names from the trimmed value (a trailing space is not a legal Java
 * identifier char, so {@code IdentifierSanitiser} and {@code @JsonProperty} carry the trimmed id),
 * so the generator emits the trimmed form. This rule trims both sides of the recognised identifier
 * columns before rows are matched, so an otherwise identical id/code matches. It trims only —
 * an id differing by anything other than surrounding whitespace still fails — and touches only
 * identifier columns, never label/value/prose columns where interior whitespace is significant.</p>
 */
public final class IdentifierWhitespaceRule implements NormalisationRule {

    // Columns whose value is an identifier the importer trims: sheet IDs, cross-sheet field/event/
    // state/tab references, and the ListElementCode/FieldTypeParameter that name a FixedList or
    // ComplexType. Deliberately excludes prose columns (Label/ElementLabel/Value/*ShowCondition).
    private static final Set<String> IDENTIFIER_COLUMNS = Set.of(
        "ID", "ListElementCode", "FieldTypeParameter", "CaseFieldID", "CaseEventID",
        "CaseStateID", "TabID");

    @Override
    public String name() {
        return "IDENTIFIER_WHITESPACE";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        trim(sheetName, expectedRows, recorder);
        trim(sheetName, actualRows, recorder);
    }

    private void trim(String sheetName, List<Map<String, Object>> rows,
                      RuleApplications recorder) {
        for (Map<String, Object> row : rows) {
            for (String column : IDENTIFIER_COLUMNS) {
                Object value = row.get(column);
                if (value instanceof String) {
                    String trimmed = ((String) value).trim();
                    if (!trimmed.equals(value)) {
                        row.put(column, trimmed);
                        recorder.record(this, "trimmed whitespace on identifier '" + column
                            + "' on sheet '" + sheetName + "'");
                    }
                }
            }
        }
    }
}
