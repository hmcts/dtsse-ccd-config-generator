package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * EVENT_COMPLEX_TYPE_INERT_COLUMNS — drops the columns the definition-store importer never reads on
 * the {@code EventToComplexTypes} ({@code CaseEventToComplexTypes}) sheet, on both sides:
 * {@code CaseTypeID} (and its legacy {@code CaseTypeId} spelling) and the wizard-page columns
 * {@code PageLabel}, {@code PageDisplayOrder} and {@code PageFieldDisplayOrder}.
 *
 * <p><b>What the importer actually reads here.</b> {@code EventParser.parseCaseEventComplexTypes}
 * (ccd-definition-store-api, {@code excel-importer/.../parser/EventParser.java}) groups the sheet's
 * rows by {@code (CaseEventID, CaseFieldID)} alone and hands each group to
 * {@code EventCaseFieldComplexTypeParser.parseEventCaseFieldComplexType}, which maps only
 * {@code ListElementCode}, {@code EventElementLabel}, {@code EventHintText},
 * {@code LiveFrom}/{@code LiveTo}, {@code FieldDisplayOrder}, {@code DefaultValue}, the display
 * context, {@code FieldShowCondition}, {@code Publish}/{@code PublishAs} and
 * {@code RetainHiddenValue}. Nothing else on the row reaches the database.
 *
 * <p><b>Why {@code CaseTypeID} is inert.</b> The rows are reached through the already-case-type-scoped
 * {@code EventEntity} list, so the case type is implied by the traversal. {@code ColumnName.isRequired}
 * confirms the asymmetry: {@code CASE_EVENT}, {@code CASE_FIELD} and {@code CASE_EVENT_TO_FIELDS} all
 * require {@code CASE_TYPE_ID}, while {@code CASE_EVENT_TO_COMPLEX_TYPES} has no branch at all, so the
 * column is not even required here.
 *
 * <p><b>Why the page columns are inert.</b> Wizard pages are built by {@code WizardPageParser}, whose
 * constructor pins {@code sheetName = SheetName.CASE_EVENT_TO_FIELDS} alongside
 * {@code displayGroupId = PAGE_ID}, {@code displayGroupLabel = PAGE_LABEL},
 * {@code displayGroupOrder = PAGE_DISPLAY_ORDER} and
 * {@code displayGroupFieldDisplayOrder = PAGE_FIELD_DISPLAY_ORDER}. It is the ONLY reader of those four
 * columns and it reads them from that sheet only — a page's label and ordering come from the event's
 * {@code CaseEventToFields} rows, never from a complex-type member row. A member's own position within
 * the page comes from {@code FieldDisplayOrder}, which this rule leaves alone (and which
 * {@code DEFAULTS} strips as SDK-re-derived). {@code PageID} is likewise left alone: the converter
 * derives it from the member's page placement, so it compares normally.
 *
 * <p><b>What the drop buys.</b> The SDK generator writes none of these columns on this sheet
 * ({@code CaseEventToComplexTypesGenerator} emits {@code CaseEventID}/{@code CaseFieldID}/
 * {@code ListElementCode}/{@code DisplayContext}/labels/order/hint/default-value only), whereas
 * hand-written definitions carry them on scattered rows — evidently copied down from spreadsheet
 * templates. Each such inert column would otherwise force the converter to keep a whole verbatim
 * passthrough file alive purely to carry it. sscs's {@code Benefit} is the worked example: of its 746
 * {@code CaseEventToComplexTypes} rows, one ({@code caseUpdated/appeal}) carried only a stray
 * {@code CaseTypeID} and two ({@code writeFinalDecision/otherPartyAttendedQuestions}) carried only
 * stray page columns, and those three rows were the last of the case type's passthrough on this sheet.
 * With the columns dropped from both sides the rows derive with no carrier, and the converter
 * correspondingly no longer grafts them ({@code DefaultDefinitionLinker.ETOCT_DERIVED_COLUMNS}).
 *
 * <p>Unlike {@code EVENT_COMPLEX_TYPE_ID_IGNORED} the drop is unconditional rather than scoped to
 * derived rows: {@code ID} disambiguates two same-{@code ListElementCode} members declared by different
 * complex types and so must survive on a passed-through row, whereas none of these columns can ever
 * separate two rows of one group — {@code CaseTypeID} holds one constant value across a whole
 * definition, and the page columns describe the page the group's field sits on, not the member. The
 * rule runs in {@code normaliseSheets}, before rows are keyed, and is scoped to exactly this sheet: all
 * five columns are compared normally everywhere the importer reads them, {@code CaseEventToFields}
 * above all.
 */
public final class EventComplexTypeInertColumnsRule implements NormalisationRule {

    private static final String SHEET = "EventToComplexTypes";

    /**
     * The importer-ignored columns, by the spellings real definitions use.
     */
    private static final List<String> INERT_COLUMNS = List.of(
        "CaseTypeID", "CaseTypeId",
        "PageLabel", "PageDisplayOrder", "PageFieldDisplayOrder");

    @Override
    public String name() {
        return "EVENT_COMPLEX_TYPE_INERT_COLUMNS";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        int removed = stripInert(expectedRows) + stripInert(actualRows);
        if (removed > 0) {
            recorder.record(this, "dropped " + removed + " importer-ignored column value(s) on sheet '"
                + sheetName + "'");
        }
    }

    private int stripInert(List<Map<String, Object>> rows) {
        int removed = 0;
        for (Map<String, Object> row : rows) {
            for (String column : INERT_COLUMNS) {
                if (row.remove(column) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }
}
