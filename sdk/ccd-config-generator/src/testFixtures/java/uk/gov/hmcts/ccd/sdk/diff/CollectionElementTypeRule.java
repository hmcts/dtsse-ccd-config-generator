package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Objects;

/**
 * COLLECTION_ELEMENT_TYPE — reconciles the {@code Text} vs {@code String} spelling of a text
 * collection's element type on the {@code CaseField}/{@code ComplexTypes} sheets.
 *
 * <p>Rationale: for a {@code FieldType=Collection} of text, a hand-written definition names the
 * element type {@code Text} (the CCD base type), whereas the SDK derives it from the Java element
 * type and emits its simple class name {@code String} (see
 * {@code CaseFieldGenerator.resolveCollectionType}, {@code elementClass.getSimpleName()}). Every
 * config-generator-based service (nfdiv, adoption, …) deploys this {@code String} form and imports
 * successfully, so the definition store treats a {@code Collection} of {@code String} and of
 * {@code Text} identically. This rule canonicalises {@code FieldTypeParameter=String} to
 * {@code Text} on both sides when {@code FieldType=Collection}, so the two spellings match. It
 * fires only for the collection element parameter; any other {@code String}/{@code Text}
 * difference is left untouched.</p>
 */
public final class CollectionElementTypeRule implements NormalisationRule {

    @Override
    public String name() {
        return "COLLECTION_ELEMENT_TYPE";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!"CaseField".equals(sheetName) && !"ComplexTypes".equals(sheetName)) {
            return;
        }
        canonicalise(sheetName, expectedRow, recorder);
        canonicalise(sheetName, actualRow, recorder);
    }

    private void canonicalise(String sheetName, Map<String, Object> row,
                              RuleApplications recorder) {
        if (!"Collection".equals(row.get("FieldType"))) {
            return;
        }
        if (Objects.equals("String", row.get("FieldTypeParameter"))) {
            row.put("FieldTypeParameter", "Text");
            recorder.record(this, "canonicalised Collection element type 'String' to 'Text' on"
                + " sheet '" + sheetName + "'");
        }
    }
}
