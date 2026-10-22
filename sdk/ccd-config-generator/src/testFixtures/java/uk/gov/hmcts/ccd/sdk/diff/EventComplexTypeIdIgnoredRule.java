package uk.gov.hmcts.ccd.sdk.diff;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EVENT_COMPLEX_TYPE_ID_IGNORED — drops the {@code ID} column from a <em>derived</em>
 * {@code (CaseEventID, CaseFieldID)} group of the {@code EventToComplexTypes}
 * ({@code CaseEventToComplexTypes}) sheet, on both sides.
 *
 * <p>Rationale: the definition-store importer never reads the {@code ID} column of this sheet. Its
 * parser {@code EventCaseFieldComplexTypeParser.parseEventCaseFieldComplexType}
 * (ccd-definition-store-api, {@code excel-importer/.../parser/EventCaseFieldComplexTypeParser.java})
 * maps only {@code ListElementCode}, {@code EventElementLabel}, {@code EventHintText},
 * {@code LiveFrom}/{@code LiveTo}, {@code FieldDisplayOrder}, {@code DefaultValue}, the display
 * context, {@code FieldShowCondition}, {@code Publish}/{@code PublishAs} and {@code RetainHiddenValue}
 * — it never references {@code ColumnName.ID}. {@code ColumnName.isRequired} has no
 * {@code CASE_EVENT_TO_COMPLEX_TYPES} branch, so {@code ID} is not even a required column here, and
 * the target {@code EventComplexTypeEntity.id} is a DB-generated sequence, not populated from the
 * sheet. So the {@code ID} an author writes on a {@code CaseEventToComplexTypes} row (the declaring
 * complex type's name) is arbitrary metadata that never reaches the imported definition.
 *
 * <p>The converter therefore no longer grafts it back onto a derived group's generated rows
 * (see {@code DefaultDefinitionLinker.buildEventToComplexTypesPassthrough}). The generated side of a
 * derived group carries no {@code ID} while a hand-written definition does, so a residual would
 * appear; this rule drops the column from both sides, making it a maintainer-accepted semantic
 * difference.
 *
 * <p>The drop is scoped to <b>derived</b> rows only, so it does not weaken row identity for a
 * <b>fallback</b> (non-derivable) row, whose whole row — {@code ID} included — is passed through
 * verbatim on both sides and where {@code ID} legitimately disambiguates two members that share a
 * {@code (CaseEventID, CaseFieldID, ListElementCode)} under different declaring types (prl's
 * {@code children} hosts both a {@code Child} and an {@code OtherPersonWhoLivesWithChild} member named
 * {@code firstName}). The discriminator is exact and needs no side-channel: the SDK generator emits no
 * {@code ID} on this sheet and a derived row's companion tail-graft carries none either, so a row is
 * derived precisely when the <em>actual</em> side carries no non-blank {@code ID} for its
 * {@code (CaseEventID, CaseFieldID, ListElementCode, FieldShowCondition)} key; a passed-through row
 * carries the verbatim {@code ID}. {@code ID} is stripped (both sides) only for the derived rows, so
 * passed-through rows keep {@code ID} as a matching key exactly as before.
 *
 * <p>The discriminator is keyed per <em>row</em>, not per {@code (CaseEventID, CaseFieldID)} group:
 * since the linker gained per-member fallback one group can mix derived members (no {@code ID}) with
 * passed-through ones (verbatim {@code ID}), and a group-wide decision would either re-inject
 * {@code ID} onto derived rows the generated side has none for, or strip it off the passed-through
 * rows that need it. The key carries {@code FieldShowCondition} because that column is part of both
 * this sheet's row key and the linker's fallback merge key, so two placements of the same member
 * under different conditions classify independently. {@code ShowConditionWhitespaceRule} runs before
 * this rule so the condition is already trimmed on both sides.
 *
 * <p>The strip runs in {@code normaliseSheets}, before rows are keyed, so the empty {@code ID} token
 * a stripped derived row keys on matches on both sides. It is scoped to exactly this sheet:
 * {@code ID} is a real key on {@code CaseField}, {@code CaseEvent}, {@code State}, {@code ComplexTypes}
 * and {@code FixedLists}, where it is compared normally.
 */
public final class EventComplexTypeIdIgnoredRule implements NormalisationRule {

    private static final String SHEET = "EventToComplexTypes";
    private static final String ID = "ID";
    private static final String CASE_EVENT_ID = "CaseEventID";
    private static final String CASE_FIELD_ID = "CaseFieldID";
    private static final String LIST_ELEMENT_CODE = "ListElementCode";
    private static final String FIELD_SHOW_CONDITION = "FieldShowCondition";

    @Override
    public String name() {
        return "EVENT_COMPLEX_TYPE_ID_IGNORED";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName)) {
            return;
        }
        // A row is a verbatim passthrough exactly when the actual side carries an ID for its key (the
        // generator writes none; a derived row's tail-graft carries none either).
        Set<String> passedThroughRows = new LinkedHashSet<>();
        for (Map<String, Object> row : actualRows) {
            Object id = row.get(ID);
            if (id != null && !String.valueOf(id).isBlank()) {
                passedThroughRows.add(rowKey(row));
            }
        }
        int removed = stripDerivedId(expectedRows, passedThroughRows)
            + stripDerivedId(actualRows, passedThroughRows);
        if (removed > 0) {
            recorder.record(this, "dropped importer-ignored ID column on " + removed
                + " derived row(s) of sheet '" + sheetName + "'");
        }
    }

    private int stripDerivedId(List<Map<String, Object>> rows, Set<String> passedThroughRows) {
        int removed = 0;
        for (Map<String, Object> row : rows) {
            if (!passedThroughRows.contains(rowKey(row)) && row.remove(ID) != null) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * The row's identity for classification purposes: the sheet's own primary key minus {@code ID}
     * (which is precisely what is being decided). Blank and absent collapse to the same token so a
     * row whose condition column is present-but-empty classifies with the rows that omit it, matching
     * {@code EmptyStringAbsentRule}'s tolerance elsewhere in the comparator.
     */
    private String rowKey(Map<String, Object> row) {
        return token(row.get(CASE_EVENT_ID)) + '' + token(row.get(CASE_FIELD_ID))
            + '' + token(row.get(LIST_ELEMENT_CODE))
            + '' + token(row.get(FIELD_SHOW_CONDITION));
    }

    private String token(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
