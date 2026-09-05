package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DEFAULTS — tolerates generator defaults that hand-written definitions omit, and strips
 * columns that carry no behavioural meaning.
 *
 * <p>Rationale: the config generator always writes certain columns with their CCD default
 * value ({@code SecurityClassification=Public}, {@code ShowSummary=N}, and so on) where a
 * hand-written definition simply leaves the column out — both spellings import identically.
 * A defaulted value is only forgiven when the other side omits the column; an explicit
 * non-default value on either side still fails. {@code ShowSummaryChangeOption} is display-only
 * (it only controls whether the "change" link shows in the event summary), so both {@code Y}
 * and {@code N} are treated as generator defaults for it.</p>
 *
 * <p>Separately, purely presentational or importer-ignored columns are removed
 * unconditionally from both sides: {@code Comment} (importer-ignored), any column whose header
 * starts with {@code _} (an inline documentation annotation the importer does not map to a
 * field — civil ships {@code _Comment}/{@code _Category}/{@code _Definition}/{@code _Max}/
 * {@code _PageShowCondition} holding prose), the various {@code *DisplayOrder} /
 * {@code PageColumnNumber} ordering columns (renumbered freely by the generator without
 * behavioural effect), and whitespace-only {@code ElementLabel} / {@code PageLabel}
 * placeholders. On the {@code ComplexTypes} sheet a {@code CaseTypeID}/{@code CaseTypeId} column is
 * also stripped: complex types are jurisdiction-global, the importer does not read a case-type
 * column there, and the generator's {@code ComplexTypeGenerator} explicitly removes it. On the
 * {@code CaseField} sheet a {@code DisplayContextParameter} column is stripped for the same reason:
 * the importer's {@code CaseFieldParser} never reads it there (DCP is a per-page property, read on
 * {@code CaseEventToFields}/{@code ComplexTypes} only) and the SDK has no CaseField-level DCP API,
 * so it is importer-ignored metadata like {@code Comment}.</p>
 */
public final class DefaultsRule implements NormalisationRule {

    /**
     * Column → values the generator emits by default. A row may omit the column or carry one
     * of these values interchangeably.
     */
    private static final Map<String, Set<String>> GENERATOR_DEFAULTS = Map.ofEntries(
        Map.entry("SecurityClassification", Set.of("Public", "PUBLIC")),
        // The config generator only writes Searchable when it is false (@CCD(searchable=false));
        // a searchable field (the CCD default) omits the column, so Y is a generator default.
        Map.entry("Searchable", Set.of("Y")),
        Map.entry("ShowSummary", Set.of("N")),
        Map.entry("ShowEventNotes", Set.of("N")),
        Map.entry("Publish", Set.of("N")),
        // The generator only ever writes RetainHiddenValue when it is true
        // (CaseEventToFieldsGenerator/JsonUtils emit "Y" under `if (retainHiddenValue)` and never
        // "N"); N is the CCD default (hidden values are not retained), so a hand-written N/No where
        // the generated side omits the column imports identically. YnCanonRule canonicalises No→N
        // first. This is scoped like every other default: only forgiven when the other side omits.
        Map.entry("RetainHiddenValue", Set.of("N")),
        Map.entry("ShowSummaryChangeOption", Set.of("Y", "N", "No")),
        Map.entry("EndButtonLabel", Set.of("Save and continue")),
        Map.entry("PostConditionState", Set.of("*")),
        // RoleToAccessProfilesGenerator always writes Disabled/ReadOnly as Y/N booleans, whereas a
        // hand-written RoleToAccessProfiles row omits them when false — N is the CCD default.
        Map.entry("Disabled", Set.of("N")),
        Map.entry("ReadOnly", Set.of("N")),
        // The converter emits jurisdictionShuttered()/enableForDeletion()/significant() only when the
        // flag is true, so the generator writes the column only then (Y); a hand-written definition
        // carrying an explicit N/No/false (civil ships Jurisdiction Shuttered=false) is the CCD
        // default and imports identically to the column being omitted. YnCanonRule canonicalises
        // Yes/No/true/false to Y/N first, so N is the value seen here.
        Map.entry("Shuttered", Set.of("N")),
        Map.entry("EnableForDeletion", Set.of("N")),
        Map.entry("SignificantEvent", Set.of("N"))
    );

    /**
     * Columns removed from every row regardless of value.
     */
    private static final Set<String> ALWAYS_REMOVED = Set.of(
        "Comment",
        "DisplayOrder",
        "FieldDisplayOrder",
        "PageFieldDisplayOrder",
        "PageDisplayOrder",
        "PageColumnNumber",
        "TabDisplayOrder"
    );

    /**
     * Columns removed when their value is a whitespace-only string. The config generator's
     * {@code ensureDefaultLabel} forces an empty label to a single space {@code " "}, whereas a
     * definition may carry an empty or multi-space placeholder; the exact run of whitespace in a
     * blank {@code Label}/{@code ElementLabel}/{@code PageLabel} is not meaningful, so a
     * whitespace-only value is dropped from both sides (a non-blank label still compares).
     */
    private static final Set<String> REMOVED_WHEN_BLANK = Set.of(
        "ElementLabel",
        "PageLabel",
        "Label"
    );

    @Override
    public String name() {
        return "DEFAULTS";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        stripMeaningless(sheetName, "expected", expectedRows, recorder);
        stripMeaningless(sheetName, "actual", actualRows, recorder);
    }

    @Override
    public void normaliseMatchedRows(String sheetName,
                                     String rowKey,
                                     Map<String, Object> expectedRow,
                                     Map<String, Object> actualRow,
                                     RuleApplications recorder) {
        for (Map.Entry<String, Set<String>> entry : GENERATOR_DEFAULTS.entrySet()) {
            String column = entry.getKey();
            Set<String> defaults = entry.getValue();
            forgiveDefault(sheetName, column, defaults, expectedRow, actualRow, recorder);
            forgiveDefault(sheetName, column, defaults, actualRow, expectedRow, recorder);
        }
    }

    private void forgiveDefault(String sheetName, String column, Set<String> defaults,
                                Map<String, Object> present, Map<String, Object> other,
                                RuleApplications recorder) {
        if (present.containsKey(column) && !other.containsKey(column)
            && defaults.contains(String.valueOf(present.get(column)))) {
            present.remove(column);
            recorder.record(this, "removed defaulted '" + column
                + "' where the other side omits it on sheet '" + sheetName + "'");
        }
    }

    private void stripMeaningless(String sheetName, String side, List<Map<String, Object>> rows,
                                  RuleApplications recorder) {
        int removed = 0;
        // The definition-store importer does not use a case-type column on the ComplexTypes sheet
        // (complex types are jurisdiction-global), and the generator's ComplexTypeGenerator
        // explicitly `info.remove("CaseTypeID")` on every member row. A CaseTypeID a hand-written
        // ComplexTypes row carries (civil, prl) is therefore dead metadata; drop it from both sides.
        boolean complexTypes = "ComplexTypes".equals(sheetName);
        // The importer's CaseField-sheet parser never reads DisplayContextParameter: CaseFieldParser
        // .parseCaseField (ccd-definition-store-api, excel-importer/.../parser/CaseFieldParser.java,
        // reads only reference/fieldType/securityClassification/Label/Hidden/HintText/LiveFrom/LiveTo/
        // Searchable/CategoryId) does not map ColumnName.DISPLAY_CONTEXT_PARAMETER, and CaseFieldEntity
        // has no field to hold it. DCP is a per-page property (read on CaseEventToFields / ComplexTypes,
        // NOT the CaseField sheet), so a CaseField-row DCP is importer-ignored metadata like Comment.
        // The SDK has no CaseField-level DCP API, so it never emits one; drop it from the CaseField
        // sheet only (leaving DCP on every other sheet, where it IS read, to compare normally).
        boolean caseFieldSheet = "CaseField".equals(sheetName);
        for (Map<String, Object> row : rows) {
            for (String column : List.copyOf(row.keySet())) {
                if (complexTypes && ("CaseTypeID".equals(column) || "CaseTypeId".equals(column))) {
                    row.remove(column);
                    removed++;
                    continue;
                }
                if (caseFieldSheet && "DisplayContextParameter".equals(column)) {
                    row.remove(column);
                    removed++;
                    continue;
                }
                if (ALWAYS_REMOVED.contains(column) || column.startsWith("_")) {
                    // ALWAYS_REMOVED are importer-ignored/presentational columns. A column whose
                    // header starts with '_' is, by the definition authors' convention, an inline
                    // documentation annotation (civil ships _Comment/_Category/_Definition/_Max/
                    // _PageShowCondition holding prose): the definition-store importer maps only its
                    // known column headers to fields and drops any other, exactly as it does the
                    // canonical 'Comment'. Neither reaches the generated definition.
                    row.remove(column);
                    removed++;
                }
            }
            for (String column : REMOVED_WHEN_BLANK) {
                Object value = row.get(column);
                if (value instanceof String && ((String) value).isBlank()) {
                    row.remove(column);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            recorder.record(this, "removed " + removed
                + " meaningless column value(s) on sheet '" + sheetName + "' (" + side + ")");
        }
    }
}
