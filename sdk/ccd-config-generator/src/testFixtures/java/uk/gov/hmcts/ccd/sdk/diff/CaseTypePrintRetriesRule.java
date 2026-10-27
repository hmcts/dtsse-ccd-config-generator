package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * CASE_TYPE_PRINT_RETRIES — drops the vestigial print-event retry column from the {@code CaseType}
 * sheet.
 *
 * <p>Rationale: the definition-store importer never reads it. {@code CaseTypeParser
 * .parsePrintWebhook} builds its {@code WebhookEntity} from {@code PrintableDocumentsUrl} alone and
 * attaches no timeouts — contrast {@code parseGetCaseWebhook}, which goes through
 * {@code WebhookParser.parseWebhook(..., CALLBACK_GET_CASE_URL, RETRIES_GET_CASE_URL)}. There is no
 * {@code ColumnName} constant for a print-event retry policy and no occurrence of the name anywhere
 * in the definition store, and {@code CaseTypeParserTest} pins the behaviour by asserting the print
 * webhook has zero timeouts against the get-case webhook's four. The column survives in
 * {@code ccd-template.xlsx}'s CaseType header row, so a hand-written definition (probate carries
 * {@code 5,10,15}) round-trips it into the spreadsheet where the importer then discards it.
 *
 * <p>The SDK correspondingly has no way to emit it: {@code ConfigBuilder} exposes
 * {@code printableDocumentsUrl(String)} and no print-retries method, and
 * {@code JSONConfigGenerator.generateCaseType} writes no such column. Adding one would mean new API
 * surface carrying data CCD throws away, so the column is dropped from both sides instead. This
 * rule touches only the CaseType sheet and only that one column; every other {@code Retries*}
 * column belongs to a callback the importer really does read and is compared exactly (see
 * {@link CaseEventRetriesRule} for the same treatment of the equally vestigial mid-event retries).
 */
public final class CaseTypePrintRetriesRule implements NormalisationRule {

    private static final String SHEET = "CaseType";
    private static final String COLUMN = "RetriesTimeoutURLPrintEvent";

    @Override
    public String name() {
        return "CASE_TYPE_PRINT_RETRIES";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        drop("expected", expectedRows, recorder);
        drop("actual", actualRows, recorder);
    }

    private void drop(String side, List<Map<String, Object>> rows, RuleApplications recorder) {
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            if (row.remove(COLUMN) != null) {
                dropped++;
            }
        }
        if (dropped > 0) {
            recorder.record(this, "dropped " + dropped + " vestigial " + COLUMN
                + " column(s) from the CaseType sheet (" + side + ")");
        }
    }
}
