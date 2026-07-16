package uk.gov.hmcts.ccd.sdk.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Semantically compares an expected CCD definition (hand-written, aggregated per sheet)
 * against an actual generated definition, tolerating only the explicit, enumerated set of
 * known-superficial differences encoded as {@link NormalisationRule}s.
 *
 * <p>Both sides are modelled as {@code Map<sheetName, List<row>>} so file layout never
 * influences the comparison; {@link #aggregateDirectory(File)} builds such an aggregate from
 * a generated output directory, merging fragmented per-event/per-field files into their
 * logical sheets. After normalisation, rows are matched by per-sheet primary keys and
 * compared column-by-column with strict equality; any unexplained difference is a failure.</p>
 */
public final class NormalisingCcdConfigComparator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<NormalisationRule> RULES = List.of(
        new AccessControlExpansionRule(),
        new UserProfileExcludedRule(),
        // Orphan / predefined-redeclaration declaration drops run in normaliseDefinition and read the
        // raw CaseField/ComplexTypes type columns for reachability, so they precede any rule that
        // rewrites those columns (e.g. FIELD_TYPE_COMPLEX, which only runs at matched-row time).
        new OrphanComplexTypeRule(),
        new OrphanFixedListRule(),
        new PredefinedComplexTypeRedeclarationRule(),
        new IdentifierWhitespaceRule(),
        new KeyAliasRule(),
        new LiveFromRule(),
        new LiveToVestigialRule(),
        new YnCanonRule(),
        new SecurityClassificationRule(),
        new DefaultsRule(),
        new ConflictingElementLabelsRule(),
        new CaseEventMidEventRule(),
        new CaseEventRetriesRule(),
        new CaseHistoryRule(),
        new NumericStringsRule(),
        new EmptyStringAbsentRule(),
        new StateDescriptionRule(),
        new FieldTypeComplexRule(),
        new RedundantFieldTypeParameterRule(),
        new PublishIgnoredOnFieldSheetsRule(),
        new CollectionElementTypeRule(),
        new EmptyCrudAuthorisationRule(),
        new CrudLetterOrderRule(),
        new ShowConditionWhitespaceRule(),
        new PostConditionNoChangeRule(),
        new ConditionalPostStateRule(),
        new PreConditionStateOrderRule(),
        new PageLabelPropagationRule(),
        new CaseTypeTabRule(),
        new TabReadInjectionRule(),
        new ImmutableFieldCrRule()
    );

    /**
     * Primary-key columns per sheet. Sheets absent from this map (including SearchCriteria
     * and SearchParty) fall back to full-row identity.
     */
    private static final Map<String, List<String>> SHEET_PRIMARY_KEYS = Map.ofEntries(
        Map.entry("CaseEvent", List.of("ID")),
        Map.entry("CaseType", List.of("ID")),
        Map.entry("CaseField", List.of("ID")),
        Map.entry("State", List.of("ID")),
        Map.entry("CaseRoles", List.of("ID")),
        Map.entry("CaseEventToFields", List.of("CaseEventID", "CaseFieldID")),
        Map.entry("AuthorisationCaseEvent", List.of("CaseEventID", "AccessProfile")),
        Map.entry("AuthorisationCaseField", List.of("CaseFieldID", "AccessProfile")),
        Map.entry("AuthorisationCaseState", List.of("CaseStateID", "AccessProfile")),
        Map.entry("AuthorisationCaseType", List.of("AccessProfile")),
        // AuthorisationComplexType is emitted as flat per-role grantComplexType rows and the input's
        // UserRoles[]/AccessControl[] shapes are flattened by AccessControlExpansionRule, so both
        // sides carry flat rows keyed by field member + role + CRUD (KEY_ALIAS canonicalises UserRole
        // to AccessProfile before this key is computed). Residual (unresolvable) rows the converter
        // still passes through verbatim flatten identically.
        Map.entry("AuthorisationComplexType",
            List.of("CaseFieldID", "ListElementCode", "UserRole", "AccessProfile", "CRUD")),
        Map.entry("ComplexTypes", List.of("ID", "ListElementCode")),
        Map.entry("FixedLists", List.of("ID", "ListElementCode")),
        Map.entry("CaseTypeTab", List.of("TabID", "CaseFieldID")),
        Map.entry("EventToComplexTypes", List.of("ID", "CaseEventID", "CaseFieldID", "ListElementCode")),
        // Search/workbasket rows scope by role (UserRole/AccessProfile) and can repeat one
        // CaseFieldID with different ListElementCodes, so both discriminate the row identity
        // (KEY_ALIAS canonicalises UserRole to AccessProfile before this key is computed).
        Map.entry("SearchInputFields", List.of("CaseFieldID", "AccessProfile", "ListElementCode", "UseCase")),
        Map.entry("SearchResultFields", List.of("CaseFieldID", "AccessProfile", "ListElementCode", "UseCase")),
        Map.entry("SearchCasesResultFields", List.of("CaseFieldID", "AccessProfile", "ListElementCode", "UseCase")),
        Map.entry("WorkBasketInputFields", List.of("CaseFieldID", "AccessProfile", "ListElementCode", "UseCase")),
        Map.entry("WorkBasketResultFields", List.of("CaseFieldID", "AccessProfile", "ListElementCode", "UseCase")),
        Map.entry("ChallengeQuestion", List.of("ID", "QuestionId")),
        Map.entry("RoleToAccessProfiles", List.of("RoleName")),
        Map.entry("Categories", List.of("CategoryID")),
        // A case type declares one Jurisdiction row; the generator and the input both key it by ID,
        // so matching on ID (rather than whole-row identity) lets a column difference — e.g. a
        // Shuttered flag the input carries but the SDK builder omits — surface as a single column
        // diff instead of an unreconcilable no-match/unexpected pair.
        Map.entry("Jurisdiction", List.of("ID")),
        // Exactly one banner per jurisdiction, so key on the description to match the single row by
        // identity (rather than whole-row identity, which the BannerEnabled Y/N vs Yes/No/false and
        // absent BannerUrl/BannerUrlText spellings would split into a no-match/unexpected pair
        // before YN_CANON / EMPTY_STRING_ABSENT can reconcile the columns).
        Map.entry("Banner", List.of("BannerDescription")),
        // SearchParty rows carry no synthetic key; their identity is the party they describe. The
        // SearchPartyName (the comma-joined name-component paths) plus the collection field name
        // uniquely identify a party within a case type, so keying on them lets the row match by
        // identity rather than insertion order (the generator emits every SearchPartyField column,
        // including nulls the input omits, which then reconcile via EMPTY_STRING_ABSENT).
        Map.entry("SearchParty",
            List.of("CaseTypeID", "SearchPartyName", "SearchPartyCollectionFieldName"))
    );

    private NormalisingCcdConfigComparator() {
    }

    /**
     * Builds a per-sheet aggregate from a generated CCD definition output directory. The
     * generator fragments some sheets across many files (e.g. {@code CaseEvent/*.json},
     * {@code CaseEventToComplexTypes/<event>/<field>.json}); every JSON file under a sheet
     * directory is merged into that sheet, and top-level files map to the sheet named by the
     * file. The {@code CaseEventToComplexTypes} directory maps to the
     * {@code EventToComplexTypes} sheet.
     *
     * @param dir root of the generated definition directory
     * @return sheetName → rows, with insertion-independent (sorted-path) row order
     */
    public static Map<String, List<Map<String, Object>>> aggregateDirectory(File dir) {
        Map<String, List<Map<String, Object>>> sheets = new TreeMap<>();
        Path root = dir.toPath();
        try (var paths = Files.walk(root)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(Path::toString))
                .collect(Collectors.toList());
            for (Path file : files) {
                String sheet = sheetNameFor(root, file);
                sheets.computeIfAbsent(sheet, key -> new ArrayList<>()).addAll(readRows(file));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to aggregate CCD definition directory " + dir, e);
        }
        return sheets;
    }

    /**
     * Compares two per-sheet aggregates after applying every normalisation rule. The inputs
     * are deep-copied and never mutated.
     */
    public static ComparisonResult compare(Map<String, List<Map<String, Object>>> expected,
                                           Map<String, List<Map<String, Object>>> actual) {
        Map<String, List<Map<String, Object>>> expectedCopy = deepCopy(expected);
        Map<String, List<Map<String, Object>>> actualCopy = deepCopy(actual);

        RuleApplications recorder = new RuleApplications();
        List<String> failures = new ArrayList<>();

        Set<String> sheetNames = new TreeSet<>();
        sheetNames.addAll(expectedCopy.keySet());
        sheetNames.addAll(actualCopy.keySet());

        // Ensure both sides carry an entry for every sheet so whole-definition rules can look
        // across sheets without null checks.
        for (String sheet : sheetNames) {
            expectedCopy.computeIfAbsent(sheet, key -> new ArrayList<>());
            actualCopy.computeIfAbsent(sheet, key -> new ArrayList<>());
        }
        for (NormalisationRule rule : RULES) {
            rule.normaliseDefinition(expectedCopy, actualCopy, recorder);
        }

        for (String sheet : sheetNames) {
            List<Map<String, Object>> expectedRows = expectedCopy.computeIfAbsent(sheet, key -> new ArrayList<>());
            List<Map<String, Object>> actualRows = actualCopy.computeIfAbsent(sheet, key -> new ArrayList<>());
            for (NormalisationRule rule : RULES) {
                rule.normaliseSheets(sheet, expectedRows, actualRows, recorder);
            }
            compareSheet(sheet, expectedRows, actualRows, recorder, failures);
        }

        return new ComparisonResult(failures, recorder.asList());
    }

    /**
     * Convenience wrapper for {@link #compare} against a generated output directory.
     */
    public static ComparisonResult compareWithDirectory(Map<String, List<Map<String, Object>>> expected,
                                                        File actualDir) {
        return compare(expected, aggregateDirectory(actualDir));
    }

    /**
     * Asserts the two definitions are semantically equivalent, throwing an
     * {@link AssertionError} carrying the formatted {@link ComparisonResult#report()} when
     * they are not.
     */
    public static void assertEquivalent(Map<String, List<Map<String, Object>>> expected,
                                        Map<String, List<Map<String, Object>>> actual) {
        ComparisonResult result = compare(expected, actual);
        if (!result.matches()) {
            throw new AssertionError(result.report());
        }
    }

    private static void compareSheet(String sheet,
                                     List<Map<String, Object>> expectedRows,
                                     List<Map<String, Object>> actualRows,
                                     RuleApplications recorder,
                                     List<String> failures) {
        Map<String, List<Map<String, Object>>> expectedByKey = groupByKey(sheet, expectedRows);
        Map<String, List<Map<String, Object>>> actualByKey = groupByKey(sheet, actualRows);

        Set<String> rowKeys = new LinkedHashSet<>();
        rowKeys.addAll(expectedByKey.keySet());
        rowKeys.addAll(actualByKey.keySet());

        for (String rowKey : rowKeys) {
            List<Map<String, Object>> expectedMatches =
                dropExactDuplicates(expectedByKey.getOrDefault(rowKey, List.of()));
            List<Map<String, Object>> actualMatches =
                dropExactDuplicates(actualByKey.getOrDefault(rowKey, List.of()));
            int paired = Math.min(expectedMatches.size(), actualMatches.size());
            for (int i = 0; i < paired; i++) {
                Map<String, Object> expectedRow = expectedMatches.get(i);
                Map<String, Object> actualRow = actualMatches.get(i);
                for (NormalisationRule rule : RULES) {
                    rule.normaliseMatchedRows(sheet, rowKey, expectedRow, actualRow, recorder);
                }
                compareRow(sheet, rowKey, expectedRow, actualRow, failures);
            }
            for (int i = paired; i < expectedMatches.size(); i++) {
                failures.add("Sheet '" + sheet + "' row [" + rowKey
                    + "]: expected row has no match in actual: " + expectedMatches.get(i));
            }
            for (int i = paired; i < actualMatches.size(); i++) {
                failures.add("Sheet '" + sheet + "' row [" + rowKey
                    + "]: unexpected row in actual: " + actualMatches.get(i));
            }
        }
    }

    /**
     * Drops rows that are exactly equal (after normalisation, and after treating a blank/null
     * column as equivalent to the column being entirely absent — the same tolerance
     * {@link EmptyStringAbsentRule} applies across sides) to an earlier row sharing the same
     * primary key. The definition-store importer keys rows by their primary key and stores one per
     * key, so a definition that ships the same keyed row more than once — for example prl, which
     * lists many {@code CaseEventToComplexTypes} rows in both a flat {@code CaseEventToComplexTypes.json}
     * and a {@code CaseEventToComplexTypes/} fragment directory — collapses to a single row on
     * import. Collapsing only duplicates *up to blank-vs-absent* keeps a genuine same-key content
     * conflict (two rows differing in a non-blank column) visible, since those do not collapse.
     *
     * @param rows the rows sharing a primary key, in order
     * @return the rows with (blank-vs-absent-tolerant) duplicates removed
     */
    private static List<Map<String, Object>> dropExactDuplicates(List<Map<String, Object>> rows) {
        if (rows.size() < 2) {
            return rows;
        }
        List<Map<String, Object>> deduped = new ArrayList<>(rows.size());
        List<Map<String, Object>> canonicalised = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> canonical = withoutBlankOrNullColumns(row);
            if (!canonicalised.contains(canonical)) {
                canonicalised.add(canonical);
                deduped.add(row);
            }
        }
        return deduped;
    }

    private static Map<String, Object> withoutBlankOrNullColumns(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object value = entry.getValue();
            boolean blankOrNull = value == null || (value instanceof String && ((String) value).isBlank());
            if (!blankOrNull) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static void compareRow(String sheet, String rowKey,
                                   Map<String, Object> expectedRow,
                                   Map<String, Object> actualRow,
                                   List<String> failures) {
        Set<String> columns = new TreeSet<>();
        columns.addAll(expectedRow.keySet());
        columns.addAll(actualRow.keySet());

        for (String column : columns) {
            boolean inExpected = expectedRow.containsKey(column);
            boolean inActual = actualRow.containsKey(column);
            if (inExpected && inActual) {
                Object expectedValue = expectedRow.get(column);
                Object actualValue = actualRow.get(column);
                if (!Objects.equals(expectedValue, actualValue)) {
                    failures.add("Sheet '" + sheet + "' row [" + rowKey + "] column '" + column
                        + "': expected <" + expectedValue + "> but was <" + actualValue + ">");
                }
            } else if (inExpected) {
                failures.add("Sheet '" + sheet + "' row [" + rowKey + "] column '" + column
                    + "': expected <" + expectedRow.get(column) + "> but column is missing in actual");
            } else {
                failures.add("Sheet '" + sheet + "' row [" + rowKey + "] column '" + column
                    + "': unexpected column in actual with value <" + actualRow.get(column) + ">");
            }
        }
    }

    private static Map<String, List<Map<String, Object>>> groupByKey(String sheet,
                                                                     List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byKey.computeIfAbsent(rowKey(sheet, row), key -> new ArrayList<>()).add(row);
        }
        return byKey;
    }

    private static String rowKey(String sheet, Map<String, Object> row) {
        List<String> keyColumns = SHEET_PRIMARY_KEYS.get(sheet);
        if (keyColumns == null) {
            // SearchCriteria, SearchParty and any unknown sheet: identity is the whole row.
            return new TreeMap<>(stringify(row)).toString();
        }
        // A key column that is absent, null or an empty string is canonicalised to the same token:
        // the importer treats a missing column and an empty-string column identically (see
        // EmptyStringAbsentRule), so a row keyed on e.g. an empty ListElementCode must match the
        // same row where the generator omitted the column, rather than splitting into a
        // no-match/unexpected pair that never reconciles because matching precedes column rules.
        return keyColumns.stream()
            .map(column -> {
                Object value = row.get(column);
                if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                    return "";
                }
                return String.valueOf(value);
            })
            .collect(Collectors.joining("|"));
    }

    private static Map<String, String> stringify(Map<String, Object> row) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static String sheetNameFor(Path root, Path file) {
        Path relative = root.relativize(file);
        String sheet;
        if (relative.getNameCount() > 1) {
            sheet = relative.getName(0).toString();
        } else {
            String fileName = relative.getFileName().toString();
            sheet = fileName.substring(0, fileName.length() - ".json".length());
        }
        return "CaseEventToComplexTypes".equals(sheet) ? "EventToComplexTypes" : sheet;
    }

    private static List<Map<String, Object>> readRows(Path file) {
        try {
            JsonNode node = MAPPER.readTree(file.toFile());
            if (node.isObject()) {
                return new ArrayList<>(List.of(toRow(node)));
            }
            CollectionType rowListType = MAPPER.getTypeFactory()
                .constructCollectionType(List.class, Map.class);
            return MAPPER.convertValue(node, rowListType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CCD definition file " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toRow(JsonNode node) {
        return MAPPER.convertValue(node, Map.class);
    }

    private static Map<String, List<Map<String, Object>>> deepCopy(
        Map<String, List<Map<String, Object>>> sheets) {
        Map<String, List<Map<String, Object>>> copy = new TreeMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> sheet : sheets.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : sheet.getValue()) {
                rows.add(new LinkedHashMap<>(row));
            }
            copy.put(sheet.getKey(), rows);
        }
        return copy;
    }
}
