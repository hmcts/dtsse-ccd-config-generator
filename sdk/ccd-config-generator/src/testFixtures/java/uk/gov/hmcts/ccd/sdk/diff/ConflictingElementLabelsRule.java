package uk.gov.hmcts.ccd.sdk.diff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CONFLICTING_ELEMENT_LABELS — collapses a {@code ComplexTypes} member declared more than once on
 * the expected side with differing {@code ElementLabel} values down to the first-seen label before
 * matching.
 *
 * <p><strong>Not semantic</strong> (importer citation below). prl ships some complex-type members
 * in both a flat {@code ComplexTypes.json} and a {@code ComplexTypes/} fragment directory, and the
 * fragments disagree on the member's {@code ElementLabel} (e.g. {@code PartyDetails.firstName}
 * carries several labels). A complex-type member becomes a single Java field, so the converter can
 * emit only one — it keeps the <em>first-seen</em> declaration and records a gap
 * ({@code DefaultDefinitionLinker#buildComplexTypeModels}, the {@code seenTypes.putIfAbsent(code,
 * …)} + {@code continue} branch). This rule mirrors that on the expected side: where a member's key
 * (ID + ListElementCode) repeats with differing labels, every occurrence's {@code ElementLabel} is
 * rewritten to the first-seen value, so the duplicates become exact and collapse against the single
 * generated row.</p>
 *
 * <p>Why first-seen matches import reality: the definition-store importer does <em>not</em> dedup
 * the {@code ComplexTypes} sheet at all — {@code ComplexFieldTypeParser.parseComplexType}
 * (ccd-definition-store-api, {@code excel-importer/.../parser/ComplexFieldTypeParser.java:126-128})
 * maps <em>every</em> row to its own {@code ComplexFieldEntity} and stores them in a
 * {@code LinkedHashSet} ({@code FieldTypeEntity.complexFields}, {@code repository/.../entity/
 * FieldTypeEntity.java:81}) fed from {@code DefinitionSheet.groupDataItemsById}'s order-preserving
 * {@code LinkedHashMap} ({@code parser/model/DefinitionSheet.java:49}). The first-declared row is
 * therefore the first the data store iterates, which is exactly the one the converter keeps.</p>
 *
 * <p>Scope is narrow: the rule fires only when the same member appears on the expected side
 * <em>more than once</em> with at least two distinct labels. A genuine single-declaration label
 * difference (one expected row whose label simply differs from the generated one) is untouched and
 * still fails; any non-label column difference between the duplicates also survives (they will not
 * collapse to an exact duplicate) and still fails.</p>
 */
public final class ConflictingElementLabelsRule implements NormalisationRule {

    private static final String SHEET = "ComplexTypes";
    private static final String LABEL = "ElementLabel";

    @Override
    public String name() {
        return "CONFLICTING_ELEMENT_LABELS";
    }

    @Override
    public void normaliseSheets(String sheetName,
                                List<Map<String, Object>> expectedRows,
                                List<Map<String, Object>> actualRows,
                                RuleApplications recorder) {
        if (!SHEET.equals(sheetName) || expectedRows.size() < 2) {
            return;
        }
        // First-seen label per member key, in declaration (sheet-row) order.
        Map<String, String> firstSeenLabel = new LinkedHashMap<>();
        Map<String, Boolean> hasConflict = new LinkedHashMap<>();
        for (Map<String, Object> row : expectedRows) {
            String key = memberKey(row);
            if (!row.containsKey(LABEL)) {
                continue;
            }
            String label = String.valueOf(row.get(LABEL));
            String existing = firstSeenLabel.putIfAbsent(key, label);
            if (existing != null && !existing.equals(label)) {
                hasConflict.put(key, Boolean.TRUE);
            }
        }
        int collapsed = 0;
        for (Map<String, Object> row : expectedRows) {
            String key = memberKey(row);
            if (Boolean.TRUE.equals(hasConflict.get(key)) && row.containsKey(LABEL)
                && !firstSeenLabel.get(key).equals(String.valueOf(row.get(LABEL)))) {
                row.put(LABEL, firstSeenLabel.get(key));
                collapsed++;
            }
        }
        if (collapsed > 0) {
            recorder.record(this, "collapsed " + collapsed + " conflicting '" + LABEL
                + "' occurrence(s) on sheet '" + SHEET + "' to the first-seen label");
        }
    }

    private static String memberKey(Map<String, Object> row) {
        return String.valueOf(row.get("ID")) + '|' + String.valueOf(row.get("ListElementCode"));
    }
}
