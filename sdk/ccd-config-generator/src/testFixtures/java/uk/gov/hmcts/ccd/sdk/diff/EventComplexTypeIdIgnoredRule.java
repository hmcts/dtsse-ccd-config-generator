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
 * <p>The drop is scoped to <b>derived</b> groups only, so it does not weaken row identity for a
 * <b>fallback</b> (non-derivable) group, whose whole row — {@code ID} included — is passed through
 * verbatim on both sides and where {@code ID} legitimately disambiguates two members that share a
 * {@code (CaseEventID, CaseFieldID, ListElementCode)} under different declaring types (prl's
 * {@code children} hosts both a {@code Child} and an {@code OtherPersonWhoLivesWithChild} member named
 * {@code firstName}). The discriminator is exact and needs no side-channel: the SDK generator emits no
 * {@code ID} on this sheet and a derived group's companion tail-graft carries none either, so a
 * {@code (CaseEventID, CaseFieldID)} group is derived precisely when the <em>actual</em> side carries
 * no non-blank {@code ID} for it; a fallback group carries the passed-through {@code ID} on every
 * actual row. {@code ID} is stripped (both sides) only for the derived groups, so the fallback groups
 * keep {@code ID} as a matching key exactly as before.
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
        // A (CaseEventID, CaseFieldID) group is a fallback passthrough exactly when the actual side
        // carries an ID for it (the generator writes none; a derived group's tail-graft carries none).
        Set<String> fallbackGroups = new LinkedHashSet<>();
        for (Map<String, Object> row : actualRows) {
            Object id = row.get(ID);
            if (id != null && !String.valueOf(id).isBlank()) {
                fallbackGroups.add(groupKey(row));
            }
        }
        int removed = stripDerivedId(expectedRows, fallbackGroups)
            + stripDerivedId(actualRows, fallbackGroups);
        if (removed > 0) {
            recorder.record(this, "dropped importer-ignored ID column on " + removed
                + " derived-group row(s) of sheet '" + sheetName + "'");
        }
    }

    private int stripDerivedId(List<Map<String, Object>> rows, Set<String> fallbackGroups) {
        int removed = 0;
        for (Map<String, Object> row : rows) {
            if (!fallbackGroups.contains(groupKey(row)) && row.remove(ID) != null) {
                removed++;
            }
        }
        return removed;
    }

    private String groupKey(Map<String, Object> row) {
        return String.valueOf(row.get(CASE_EVENT_ID)) + '' + String.valueOf(row.get(CASE_FIELD_ID));
    }
}
