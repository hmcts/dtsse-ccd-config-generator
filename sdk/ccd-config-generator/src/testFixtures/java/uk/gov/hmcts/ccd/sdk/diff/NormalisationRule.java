package uk.gov.hmcts.ccd.sdk.diff;

import java.util.List;
import java.util.Map;

/**
 * A single, named normalisation applied to CCD definition rows before they are compared.
 *
 * <p>Each rule encodes one known-superficial difference between a hand-written ("expected")
 * CCD definition and a generated ("actual") one. Rules mutate the rows in place and record
 * every application with the supplied {@link RuleApplications} recorder so that callers can
 * see exactly which tolerances were exercised.</p>
 *
 * <p>Rules get three hooks:</p>
 * <ul>
 *     <li>{@link #normaliseDefinition} runs once for the whole definition, before any sheet is
 *     processed. Use it for tolerances that depend on more than one sheet (e.g. a
 *     {@code CaseEventToFields} rewrite conditioned on the owning {@code CaseEvent} row).</li>
 *     <li>{@link #normaliseSheets} runs once per sheet, before rows are matched by primary
 *     key. Use it for row-local rewrites (renaming keys, removing columns) and for
 *     cross-side sheet-level decisions.</li>
 *     <li>{@link #normaliseMatchedRows} runs once per matched row pair, before the pair is
 *     compared column-by-column. Use it for tolerances that depend on what the other side
 *     says for the same row.</li>
 * </ul>
 */
public interface NormalisationRule {

    /**
     * Stable, upper-snake-case identifier for this rule, used in recorded applications.
     */
    String name();

    /**
     * Normalise the whole definition before any per-sheet processing. Implementations may mutate
     * the sheet maps and the row maps within them in place. Both maps are guaranteed to contain
     * an (possibly empty) entry for every sheet present on either side.
     *
     * @param expected sheetName → mutable expected rows (never null)
     * @param actual   sheetName → mutable actual rows (never null)
     * @param recorder records each application of the rule
     */
    default void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                     Map<String, List<Map<String, Object>>> actual,
                                     RuleApplications recorder) {
    }

    /**
     * Normalise whole sheets before row matching. Implementations may mutate either list
     * (and the row maps within them) in place.
     *
     * @param sheetName    the CCD sheet name, e.g. {@code CaseEvent}
     * @param expectedRows mutable rows from the expected definition (never null)
     * @param actualRows   mutable rows from the actual definition (never null)
     * @param recorder     records each application of the rule
     */
    default void normaliseSheets(String sheetName,
                                 List<Map<String, Object>> expectedRows,
                                 List<Map<String, Object>> actualRows,
                                 RuleApplications recorder) {
    }

    /**
     * Normalise a matched pair of rows before they are compared column-by-column.
     * Implementations may mutate either row map in place.
     *
     * @param sheetName   the CCD sheet name
     * @param rowKey      the primary-key string the rows were matched on
     * @param expectedRow mutable expected row
     * @param actualRow   mutable actual row
     * @param recorder    records each application of the rule
     */
    default void normaliseMatchedRows(String sheetName,
                                      String rowKey,
                                      Map<String, Object> expectedRow,
                                      Map<String, Object> actualRow,
                                      RuleApplications recorder) {
    }
}
