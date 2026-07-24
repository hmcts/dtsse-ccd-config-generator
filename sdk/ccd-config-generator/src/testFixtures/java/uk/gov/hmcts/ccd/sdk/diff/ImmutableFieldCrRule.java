package uk.gov.hmcts.ccd.sdk.diff;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IMMUTABLE_FIELD_CR — maintainer-accepted difference — see docs/json-conversion-fidelity.md
 * (section "Accepted semantic differences", immutable-field CR injection).
 *
 * <p>The SDK's {@code AuthorisationCaseFieldGenerator} grants {@code Permission.CR} (Create+Read)
 * on every <em>immutable</em> field — a {@code Label} field, or a field whose display context is
 * {@code READONLY} — for any role that holds any grant on the containing event, regardless of the
 * permission the hand-written definition assigned. Because passthrough only adds missing rows and
 * never subtracts generator-emitted ones, the regenerated {@code AuthorisationCaseField} grants on
 * these fields are a strict superset (within {@code {C,R}}) of the input's.</p>
 *
 * <p>This rule forgives exactly that superset, and nothing wider. As a whole-definition rule it
 * first derives the <strong>immutable field set</strong> from the <em>expected</em> aggregate:</p>
 * <ul>
 *   <li>every {@code CaseField} row whose {@code FieldType} is {@code Label}; and</li>
 *   <li>every field that appears in {@code CaseEventToFields} and whose {@code DisplayContext} is
 *       {@code READONLY} on <strong>every</strong> row it appears in (a field that is editable on
 *       any event is not immutable and is excluded).</li>
 * </ul>
 *
 * <p>Then, per {@code (CaseFieldID, AccessProfile)} on the {@code AuthorisationCaseField} sheet, for
 * immutable fields only, it compares the expected permission set {@code E} (empty when the input
 * has no such row) with the actual set {@code A}:</p>
 * <ul>
 *   <li>if {@code E ⊆ A} and the injected surplus {@code A \ E ⊆ {C,R}}, the difference is pure
 *       CR injection: the expected row is aligned to {@code A} (created if it did not exist) so the
 *       pair compares equal;</li>
 *   <li>any surplus containing {@code U} or {@code D}, or an actual set that drops a permission the
 *       expected side holds, is left untouched and still fails — that is not CR injection.</li>
 * </ul>
 *
 * <p>Ordinary editable fields are never in the immutable set, so an extra grant on one still fails
 * (proven by test). This is the field-level counterpart of {@code TAB_READ_INJECTION} (which
 * forgives bare {@code R} injected for tab/search fields); here the surplus is {@code C} and/or
 * {@code R} on immutable fields.</p>
 */
public final class ImmutableFieldCrRule implements NormalisationRule {

    private static final String CASE_FIELD = "CaseField";
    private static final String CASE_EVENT_TO_FIELDS = "CaseEventToFields";
    private static final String AUTH_CASE_FIELD = "AuthorisationCaseField";
    private static final String AUTH_CASE_EVENT = "AuthorisationCaseEvent";
    private static final Set<Character> INJECTABLE = Set.of('C', 'R');

    @Override
    public String name() {
        return "IMMUTABLE_FIELD_CR";
    }

    @Override
    public void normaliseDefinition(Map<String, List<Map<String, Object>>> expected,
                                    Map<String, List<Map<String, Object>>> actual,
                                    RuleApplications recorder) {
        Set<String> immutableFields = immutableFields(expected);
        // A field editable on some events but READONLY on the event that grants a particular role
        // is immutable *for that role only* in the SDK's explicit-grants world: the generator
        // injects CR for the (field, role) pair because the field builds immutable on the granting
        // event, even though the field is mutable elsewhere. The whole-field set above cannot see
        // that (the field is not READONLY on every row), so derive the role-scoped pairs too.
        Set<String> immutableFieldRoles = immutableFieldRoles(expected);
        if (immutableFields.isEmpty() && immutableFieldRoles.isEmpty()) {
            return;
        }
        List<Map<String, Object>> actualRows = actual.get(AUTH_CASE_FIELD);
        if (actualRows == null || actualRows.isEmpty()) {
            return;
        }
        List<Map<String, Object>> expectedRows =
            expected.computeIfAbsent(AUTH_CASE_FIELD, k -> new java.util.ArrayList<>());

        // Index expected rows by (CaseFieldID, role) — role may be spelled UserRole or
        // AccessProfile; KEY_ALIAS has not run yet, so read whichever is present.
        Map<String, Map<String, Object>> expectedByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : expectedRows) {
            expectedByKey.put(authKey(row), row);
        }

        int forgiven = 0;
        for (Map<String, Object> actualRow : actualRows) {
            String fieldId = str(actualRow.get("CaseFieldID"));
            Object roleValue = actualRow.containsKey("AccessProfile")
                ? actualRow.get("AccessProfile") : actualRow.get("UserRole");
            boolean immutable = immutableFields.contains(fieldId)
                || immutableFieldRoles.contains(fieldId + ' ' + str(roleValue));
            if (!immutable) {
                continue;
            }
            Set<Character> actualPerms = perms(actualRow.get("CRUD"));
            Map<String, Object> expectedRow = expectedByKey.get(authKey(actualRow));
            Set<Character> expectedPerms = expectedRow == null
                ? new LinkedHashSet<>() : perms(expectedRow.get("CRUD"));

            // Actual must be a superset of expected, and the surplus must be only C and/or R.
            if (!actualPerms.containsAll(expectedPerms)) {
                continue;
            }
            Set<Character> surplus = new LinkedHashSet<>(actualPerms);
            surplus.removeAll(expectedPerms);
            if (surplus.isEmpty() || !INJECTABLE.containsAll(surplus)) {
                continue;
            }

            // Align expected to actual so the pair (or the actual-only row) compares equal.
            if (expectedRow == null) {
                expectedRow = new LinkedHashMap<>(actualRow);
                expectedRows.add(expectedRow);
                expectedByKey.put(authKey(actualRow), expectedRow);
            } else {
                expectedRow.put("CRUD", actualRow.get("CRUD"));
            }
            forgiven++;
        }
        if (forgiven > 0) {
            recorder.record(this, "forgave generator CR injection on " + forgiven
                + " AuthorisationCaseField grant(s) for immutable (Label/READONLY) fields");
        }
    }

    /**
     * Derives the immutable field set from the expected side: any {@code Label}-typed
     * {@code CaseField}, plus any field whose {@code CaseEventToFields} display context is
     * {@code READONLY} on every occurrence.
     */
    private Set<String> immutableFields(Map<String, List<Map<String, Object>>> expected) {
        Set<String> immutable = new LinkedHashSet<>();
        List<Map<String, Object>> caseFields = expected.get(CASE_FIELD);
        if (caseFields != null) {
            for (Map<String, Object> row : caseFields) {
                if ("Label".equals(str(row.get("FieldType")))) {
                    immutable.add(str(row.get("ID")));
                }
            }
        }
        List<Map<String, Object>> cetf = expected.get(CASE_EVENT_TO_FIELDS);
        if (cetf != null) {
            Map<String, Boolean> allReadonly = new LinkedHashMap<>();
            for (Map<String, Object> row : cetf) {
                String fieldId = str(row.get("CaseFieldID"));
                boolean readonly = "READONLY".equalsIgnoreCase(str(row.get("DisplayContext")).trim());
                allReadonly.merge(fieldId, readonly, (a, b) -> a && b);
            }
            for (Map.Entry<String, Boolean> entry : allReadonly.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    immutable.add(entry.getKey());
                }
            }
        }
        return immutable;
    }

    /**
     * Derives the role-scoped immutable pairs: {@code "<field> <role>"} for every field that is
     * {@code READONLY} on at least one {@code CaseEventToFields} row of an event whose
     * {@code AuthorisationCaseEvent} grants that role. This mirrors the SDK generator's
     * explicit-grants CR injection, which fires per (field, role) when the field builds immutable
     * on an event granting the role — regardless of whether the field is editable on other events.
     * Fields already whole-field immutable are still covered by {@link #immutableFields}; this only
     * adds pairs that set misses.
     */
    private Set<String> immutableFieldRoles(Map<String, List<Map<String, Object>>> expected) {
        List<Map<String, Object>> cetf = expected.get(CASE_EVENT_TO_FIELDS);
        List<Map<String, Object>> events = expected.get(AUTH_CASE_EVENT);
        if (cetf == null || events == null) {
            return Set.of();
        }
        // event -> roles it grants.
        Map<String, Set<String>> rolesByEvent = new LinkedHashMap<>();
        for (Map<String, Object> row : events) {
            Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
            rolesByEvent.computeIfAbsent(str(row.get("CaseEventID")), k -> new LinkedHashSet<>())
                .add(str(role));
        }
        Set<String> pairs = new LinkedHashSet<>();
        for (Map<String, Object> row : cetf) {
            if (!"READONLY".equalsIgnoreCase(str(row.get("DisplayContext")).trim())) {
                continue;
            }
            String fieldId = str(row.get("CaseFieldID"));
            for (String role : rolesByEvent.getOrDefault(str(row.get("CaseEventID")), Set.of())) {
                pairs.add(fieldId + ' ' + role);
            }
        }
        return pairs;
    }

    private static String authKey(Map<String, Object> row) {
        Object role = row.containsKey("AccessProfile") ? row.get("AccessProfile") : row.get("UserRole");
        return str(row.get("CaseFieldID")) + " " + str(role);
    }

    private static Set<Character> perms(Object crud) {
        Set<Character> set = new LinkedHashSet<>();
        if (crud != null) {
            for (char ch : String.valueOf(crud).toUpperCase().toCharArray()) {
                if (ch == 'C' || ch == 'R' || ch == 'U' || ch == 'D') {
                    set.add(ch);
                }
            }
        }
        return set;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
