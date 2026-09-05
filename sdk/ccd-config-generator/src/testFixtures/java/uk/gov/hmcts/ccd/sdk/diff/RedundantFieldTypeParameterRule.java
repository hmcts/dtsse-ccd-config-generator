package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Set;

/**
 * REDUNDANT_FIELD_TYPE_PARAMETER — drops a {@code FieldTypeParameter} the definition-store
 * importer ignores, on the {@code CaseField} and {@code ComplexTypes} sheets.
 *
 * <p>Rationale: the importer only consults {@code FieldTypeParameter} for the list base types
 * ({@code FixedList}/{@code FixedRadioList}/{@code MultiSelectList}) and for {@code Collection}
 * (see {@code FieldTypeParser.parse} in ccd-definition-store-api, which resolves the actual type
 * via {@code isList(baseType) ? listReference(baseType, param) : baseType} and reads the parameter
 * separately only for {@code Collection}). For every other {@code FieldType} — {@code YesOrNo},
 * {@code TextArea}, {@code Label}, {@code Text}, a complex type named directly in the
 * {@code FieldType} column, etc. — the parameter is dead metadata that changes nothing on import.
 * Real hand-written definitions frequently leave a stale {@code FieldTypeParameter} on such rows
 * (ia sets it equal to the field ID on many {@code YesOrNo}/{@code TextArea} fields); the SDK,
 * which derives the field type from the Java declaration, never emits one. This rule removes a
 * {@code FieldTypeParameter} present on one side but absent on the other whenever the row's
 * {@code FieldType} is one the importer does not parameterise. It never fires when both sides
 * carry the column (a genuine value mismatch on a parameterised type still fails), and never
 * touches a list/collection type where the parameter is behavioural.</p>
 */
public final class RedundantFieldTypeParameterRule implements NormalisationRule {

    private static final Set<String> PARAMETER_REFERENCE_SHEETS = Set.of("CaseField", "ComplexTypes");

    /**
     * The only {@code FieldType} values whose {@code FieldTypeParameter} the importer actually
     * reads. For every other type the parameter is ignored, so a stray one is superficial.
     */
    private static final Set<String> PARAMETERISED_FIELD_TYPES = Set.of(
        "FixedList", "FixedRadioList", "MultiSelectList", "Collection");

    @Override
    public String name() {
        return "REDUNDANT_FIELD_TYPE_PARAMETER";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!PARAMETER_REFERENCE_SHEETS.contains(sheetName)) {
            return;
        }
        dropRedundantParameter(sheetName, expectedRow, actualRow, recorder);
        dropRedundantParameter(sheetName, actualRow, expectedRow, recorder);
    }

    private void dropRedundantParameter(String sheetName, Map<String, Object> withParam,
                                        Map<String, Object> other, RuleApplications recorder) {
        Object param = withParam.get("FieldTypeParameter");
        if (!(param instanceof String) || ((String) param).isEmpty()) {
            return;
        }
        if (other.containsKey("FieldTypeParameter")) {
            // Both sides carry the column: any disagreement is compared directly, never masked.
            return;
        }
        Object fieldType = withParam.get("FieldType");
        if (!(fieldType instanceof String) || PARAMETERISED_FIELD_TYPES.contains(fieldType)) {
            return;
        }
        withParam.remove("FieldTypeParameter");
        recorder.record(this, "dropped importer-ignored FieldTypeParameter '" + param
            + "' on non-parameterised FieldType '" + fieldType + "' on sheet '" + sheetName + "'");
    }
}
