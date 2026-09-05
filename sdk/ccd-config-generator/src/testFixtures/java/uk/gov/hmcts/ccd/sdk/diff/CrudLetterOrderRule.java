package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRUD_LETTER_ORDER — canonicalises the letter order of the {@code CRUD} column on the
 * authorisation sheets, so that two spellings of the same permission set (e.g. {@code CUR}
 * and {@code CRU}) compare equal.
 *
 * <p>Rationale: the definition-store importer parses {@code CRUD} as an order-independent,
 * case-insensitive <em>set</em> of permission letters. Each of the four flags is set purely by
 * membership testing — {@code AuthorisationParser#parseCrud} (ccd-definition-store-api,
 * {@code excel-importer/.../parser/AuthorisationParser.java}) does
 * {@code crud.toUpperCase().contains("C")} for create and likewise for {@code R}/{@code U}/{@code D}
 * — so {@code CRUD}, {@code RUDC} and {@code DURC} all import to the identical
 * {@code create/read/update/delete} booleans. Letter order therefore carries no runtime meaning.</p>
 *
 * <p>This rule rewrites a {@code CRUD} value composed solely of the permission letters
 * {@code C}/{@code R}/{@code U}/{@code D} (case-insensitively) to its sorted-letter form on both
 * sides. A genuine set difference — a letter present on one side and absent on the other (e.g.
 * {@code D} vs {@code CRUD}) — sorts to different strings and still fails. A value that is not a
 * pure CRUD string (blank, or carrying an unexpected character) is left untouched so it is judged
 * as-is.</p>
 *
 * <p>It runs in {@link #normaliseSheets} (before rows are matched by primary key), like
 * {@code YnCanonRule}, because {@code CRUD} is part of the {@code AuthorisationComplexType} primary
 * key: canonicalising before matching keeps a row whose only difference is CRUD letter order paired
 * with its counterpart rather than splitting into a no-match/unexpected pair.</p>
 */
public final class CrudLetterOrderRule implements NormalisationRule {

    private static final String CRUD_COLUMN = "CRUD";

    private static final Set<String> AUTHORISATION_SHEETS = Set.of(
        "AuthorisationCaseType", "AuthorisationCaseField",
        "AuthorisationCaseEvent", "AuthorisationCaseState", "AuthorisationComplexType");

    @Override
    public String name() {
        return "CRUD_LETTER_ORDER";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!AUTHORISATION_SHEETS.contains(sheetName)) {
            return;
        }
        canonicalise(sheetName, "expected", expectedRows, recorder);
        canonicalise(sheetName, "actual", actualRows, recorder);
    }

    private void canonicalise(String sheetName, String side, List<Map<String, Object>> rows,
                              RuleApplications recorder) {
        int canonicalised = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(CRUD_COLUMN);
            String sorted = sortedCrud(value);
            if (sorted != null && !sorted.equals(value)) {
                row.put(CRUD_COLUMN, sorted);
                canonicalised++;
            }
        }
        if (canonicalised > 0) {
            recorder.record(this, "canonicalised CRUD letter order on " + canonicalised
                + " row(s) of sheet '" + sheetName + "' (" + side + ")");
        }
    }

    /**
     * Returns the canonical-order form of a pure CRUD string (composed only of {@code C}/{@code R}/
     * {@code U}/{@code D}, case-insensitively, uppercased), or {@code null} when the value is not a
     * pure CRUD string — blank, non-string, or carrying any other character — so it is left as-is.
     * The canonical order is {@code C,R,U,D}, mirroring the SDK's own {@code CrudSet} ordering; the
     * exact order is immaterial to matching (both sides are rewritten to it) but the domain order
     * keeps the recorded/normalised value readable.
     */
    private String sortedCrud(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim().toUpperCase();
        if (s.isEmpty()) {
            return null;
        }
        boolean[] present = new boolean[256];
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != 'C' && ch != 'R' && ch != 'U' && ch != 'D') {
                return null;
            }
            present[ch] = true;
        }
        StringBuilder canonical = new StringBuilder(4);
        for (char ch : new char[] {'C', 'R', 'U', 'D'}) {
            if (present[ch]) {
                canonical.append(ch);
            }
        }
        return canonical.toString();
    }
}
