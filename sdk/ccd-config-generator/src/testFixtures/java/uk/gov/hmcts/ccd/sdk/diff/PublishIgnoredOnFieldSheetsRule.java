package uk.gov.hmcts.ccd.sdk.diff;

import java.util.Map;
import java.util.Set;

/**
 * PUBLISH_IGNORED_ON_FIELD_SHEETS — drops a {@code Publish} column carried on the
 * {@code CaseField} or {@code ComplexTypes} sheet, on whichever side has it.
 *
 * <p>Rationale: the definition-store importer's {@code Publish}/{@code PublishAs} concept exists
 * only on the {@code CaseEventToFields} sheet ({@code EventCaseFieldParser}, which populates
 * {@code EventCaseFieldEntity.publish}) and its {@code EventToComplexTypes} counterpart
 * ({@code EventCaseFieldComplexTypeParser} → {@code EventComplexTypeEntity.publish}) — both scoped
 * to a field's placement on a specific event. Neither {@code CaseFieldParser.parseCaseField} nor
 * {@code ComplexFieldTypeParser.parseComplexField} reads {@code ColumnName.PUBLISH}, and neither
 * {@code CaseFieldEntity} nor {@code ComplexFieldEntity} carries a {@code publish} field, so a
 * {@code Publish} value on the base {@code CaseField}/{@code ComplexTypes} sheet (as opposed to the
 * per-event placement sheets) is silently dropped on import. Real hand-written definitions
 * occasionally carry this dead column (e.g. copied down from a spreadsheet template); the SDK
 * generator never emits one on these two sheets. This rule removes it wherever it appears.</p>
 */
public final class PublishIgnoredOnFieldSheetsRule implements NormalisationRule {

    private static final Set<String> FIELD_SHEETS = Set.of("CaseField", "ComplexTypes");

    @Override
    public String name() {
        return "PUBLISH_IGNORED_ON_FIELD_SHEETS";
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        if (!FIELD_SHEETS.contains(sheetName)) {
            return;
        }
        boolean removedExpected = expectedRow.remove("Publish") != null;
        boolean removedActual = actualRow.remove("Publish") != null;
        if (removedExpected || removedActual) {
            recorder.record(this, "dropped importer-ignored Publish column on sheet '"
                + sheetName + "' row [" + rowKey + "]");
        }
    }
}
