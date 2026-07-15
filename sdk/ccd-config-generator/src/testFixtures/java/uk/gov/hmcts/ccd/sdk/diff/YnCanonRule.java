package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YN_CANON — canonicalises boolean-ish strings on known Y/N columns.
 *
 * <p>Rationale: the CCD importer accepts several spellings for yes/no columns
 * ({@code Yes}/{@code Y}/{@code true} and {@code No}/{@code N}/{@code false}) that are
 * interchangeable at runtime. Values are canonicalised to {@code Y}/{@code N} on both sides,
 * but only on columns that are genuinely Y/N per the CCD spec — in particular
 * {@code ShowSummaryContentOption} is numeric and is never touched.</p>
 */
public final class YnCanonRule implements NormalisationRule {

    /**
     * Columns that the CCD spec defines as Y/N flags.
     */
    private static final Set<String> YN_COLUMNS = Set.of(
        "ShowSummary",
        "ShowEventNotes",
        "Publish",
        "ShowSummaryChangeOption",
        "Searchable",
        "RetainHiddenValue",
        // RoleToAccessProfiles flags: the SDK writes Y/N, hand-written definitions also use T/F.
        "Disabled",
        "ReadOnly",
        // Definition-time boolean flags the converter now emits via builder switches. The generator
        // writes Y/N; hand-written definitions use Yes/No (ia/ET) or JSON true/false (civil/probate).
        "SignificantEvent",
        "EnableForDeletion",
        "Shuttered",
        "BannerEnabled"
    );

    @Override
    public String name() {
        return "YN_CANON";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        canonicalise(sheetName, "expected", expectedRows, recorder);
        canonicalise(sheetName, "actual", actualRows, recorder);
    }

    private void canonicalise(String sheetName, String side, List<Map<String, Object>> rows,
                              RuleApplications recorder) {
        int canonicalised = 0;
        for (Map<String, Object> row : rows) {
            for (String column : YN_COLUMNS) {
                if (!row.containsKey(column)) {
                    continue;
                }
                Object value = row.get(column);
                String canonical = canonicalValue(value);
                if (canonical != null && !canonical.equals(value)) {
                    row.put(column, canonical);
                    canonicalised++;
                }
            }
        }
        if (canonicalised > 0) {
            recorder.record(this, "canonicalised " + canonicalised
                + " Y/N value(s) on sheet '" + sheetName + "' (" + side + ")");
        }
    }

    private String canonicalValue(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return "Y";
        }
        if (Boolean.FALSE.equals(value)) {
            return "N";
        }
        if (!(value instanceof String)) {
            return null;
        }
        // The importer parses these boolean columns case-insensitively, so match the yes/no
        // spellings without regard to case: prl writes Publish as "True"/"False", others as
        // "true"/"Yes"/"Y"/"T".
        String s = ((String) value).trim();
        if (s.equalsIgnoreCase("Yes") || s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("true")
            || s.equalsIgnoreCase("T")) {
            return "Y";
        }
        if (s.equalsIgnoreCase("No") || s.equalsIgnoreCase("N") || s.equalsIgnoreCase("false")
            || s.equalsIgnoreCase("F")) {
            return "N";
        }
        return null;
    }
}
