package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FIELD_TYPE_COMPLEX — reconciles the two equally-valid spellings of a complex-type reference
 * on the {@code CaseField} and {@code ComplexTypes} sheets.
 *
 * <p>Rationale: hand-written definitions spell a complex-type reference as
 * {@code FieldType=Complex, FieldTypeParameter=<TypeId>}. The config generator instead infers
 * the Java field's declared type and emits {@code FieldType=<TypeId>} directly, with no
 * {@code FieldTypeParameter} — this is a documented structural difference in how the generator
 * represents complex references, not a data loss. Both spellings import identically. When one
 * side names a base CCD field type (see {@link #BASE_FIELD_TYPES}) in the {@code FieldType}
 * column, it can never be a complex-type reference, so the rule does not touch it — a genuine
 * type mismatch on a primitive field still fails.</p>
 */
public final class FieldTypeComplexRule implements NormalisationRule {

    private static final Set<String> COMPLEX_REFERENCE_SHEETS = Set.of("CaseField", "ComplexTypes");

    /**
     * FieldType values the CCD importer treats as base (non-complex) types: every
     * {@code uk.gov.hmcts.ccd.sdk.type.FieldType} enum constant, plus a handful of importer
     * primitives the SDK's enum does not itself model (they are only ever seen on the raw JSON
     * side).
     */
    private static final Set<String> BASE_FIELD_TYPES = Set.of(
        "Unspecified", "Email", "PhoneUK", "Date", "DateTime", "Document", "Schedule",
        "TextArea", "Text", "TextMax150", "FixedList", "FixedRadioList", "YesOrNo", "Address",
        "CaseLink", "CaseLocation", "OrderSummary", "MultiSelectList", "Collection", "Label",
        "CasePaymentHistoryViewer", "DynamicList", "DynamicRadioList", "DynamicMultiSelectList",
        "Flags", "FlagLauncher", "FlagType", "FlagDetail", "ComponentLauncher", "SearchCriteria",
        "TTL", "MoneyGBP", "Number", "Complex"
    );

    @Override
    public String name() {
        return "FIELD_TYPE_COMPLEX";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!COMPLEX_REFERENCE_SHEETS.contains(sheetName)) {
            return;
        }
        canonicaliseToComplex(sheetName, expectedRow, actualRow, recorder);
        canonicaliseToComplex(sheetName, actualRow, expectedRow, recorder);
    }

    private void canonicaliseToComplex(String sheetName, Map<String, Object> complexSide,
                                       Map<String, Object> inferredSide, RuleApplications recorder) {
        if (!"Complex".equals(complexSide.get("FieldType"))) {
            return;
        }
        Object typeId = complexSide.get("FieldTypeParameter");
        if (!(typeId instanceof String) || ((String) typeId).isEmpty()) {
            return;
        }
        boolean inferredMatches = Objects.equals(inferredSide.get("FieldType"), typeId)
            && !inferredSide.containsKey("FieldTypeParameter");
        if (!inferredMatches || BASE_FIELD_TYPES.contains(typeId)) {
            return;
        }
        inferredSide.put("FieldType", "Complex");
        inferredSide.put("FieldTypeParameter", typeId);
        recorder.record(this, "canonicalised inferred complex-type reference '" + typeId
            + "' to 'FieldType=Complex, FieldTypeParameter=" + typeId
            + "' on sheet '" + sheetName + "'");
    }
}
