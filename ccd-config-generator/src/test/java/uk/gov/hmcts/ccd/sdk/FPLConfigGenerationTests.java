package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang.StringUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FPLConfigGenerationTests {
    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();

    private static Path prodConfig;

    static ConfigGenerator generator;
    static Reflections reflections;

    @BeforeClass
    public static void before() throws IOException, URISyntaxException {
        prodConfig = tmp.getRoot().toPath().resolve("production");
        Path resRoot = Paths.get(Resources.getResource("ccd-definition").toURI());

        reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage("uk.gov.hmcts"))
                .setExpandSuperTypes(false));

        generator = new ConfigGenerator(reflections, "uk.gov.hmcts");
        generator.resolveConfig(tmp.getRoot());
        // Generate a second time to ensure existing config is correctly merged.
        generator.resolveConfig(tmp.getRoot());
    }

    @SneakyThrows
    static void copyResourceToOutput(String path) {
        Path resRoot = Paths.get(Resources.getResource("ccd-definition").toURI());
        File dest = prodConfig.resolve(path).toFile();
        Files.createParentDirs(dest);
        FileUtils.copyFile(resRoot.resolve(path).toFile(), dest);
    }

    @Test
    public void generatesAuthorisationCaseState() {
        assertEquals("AuthorisationCaseState.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldBulkScan() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-bulkscan.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldBulkScanSystemUpdate() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-bulkscansystemupdate.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldGatekeeper() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-gatekeeper.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldSystem() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-systemupdate.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldJudiciary() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-judiciary.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldCourtAdmin() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-courtadmin.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldCafcass() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-cafcass.json");
    }

    @Test
    public void generatesAuthorisationCaseFieldSolicitor() {
        assertEquals("AuthorisationCaseField/caseworker-publiclaw-solicitor.json");
    }

    @Test
    public void generatesJurisdiction() {
        assertEquals("Jurisdiction.json");
    }

    @Test
    public void generatesCaseTypeTab() {
        assertResourceFolderMatchesGenerated("CaseTypeTab");
        assertGeneratedFolderMatchesResource("CaseTypeTab");
    }

    @Test
    public void generatesWorkBasketResultFields() {
        assertEquals("WorkBasketResultFields.json");
    }

    @Test
    public void generatesWorkBasketInputFields() {
        assertEquals("WorkBasketInputFields.json");
    }

    @Test
    public void generatesAllComplexTypes() {
        assertResourceFolderMatchesGenerated("ComplexTypes");
        assertGeneratedFolderMatchesResource("ComplexTypes");
    }

    @Test
    public void generatesAllCaseEventToField() {
        assertResourceFolderMatchesGenerated("CaseEventToFields");
        assertGeneratedFolderMatchesResource("CaseEventToFields");
    }

    @Test
    public void generatesAllCaseEvent() {
        assertResourceFolderMatchesGenerated("CaseEvent");
        assertGeneratedFolderMatchesResource("CaseEvent");
    }

    @Test
    public void generatesAllCaseEventToComplexTypes() {
        assertResourceFolderMatchesGenerated("CaseEventToComplexTypes");
        assertGeneratedFolderMatchesResource("CaseEventToComplexTypes");
    }

    @Test
    public void generatesCaseField() {
        assertEquals("CaseField.json");
    }

    @Test
    public void generatesAuthorisationCaseEvent() {
        assertEquals("AuthorisationCaseEvent/AuthorisationCaseEvent.json");
    }

    @Test
    public void generatesFixedLists() {
        assertGeneratedFolderMatchesResource("FixedLists");
    }

    private void assertGeneratedFolderMatchesResource(String folder) {
        URL u = Resources.getResource("ccd-definition/" + folder);
        File resourceDir = new File(u.getPath());
        File dir = prodConfig.resolve(folder).toFile();
        for (Iterator<File> it = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
            File expected = it.next();
            if (expected.getName().endsWith(".json")) {
                Path path = dir.toPath().relativize(expected.toPath());
                Path actual = resourceDir.toPath().resolve(path);
                assertEquals(expected, actual.toFile());
            }
        }
    }

    private void assertResourceFolderMatchesGenerated(String folder) {
        URL u = Resources.getResource("ccd-definition/" + folder);
        File dir = new File(u.getPath());
        assert dir.exists();
        int succ = 0, failed = 0;
        for (Iterator<File> it = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
            File expected = it.next();
            if (expected.getName().endsWith(".json")) {
                Path path = dir.toPath().relativize(expected.toPath());
                Path actual = prodConfig.resolve(folder).resolve(path);
//            try {
                assertEquals(expected, actual.toFile());
//                succ++;
//            } catch (Exception r) {
//                failed++;
//            }
            }
        }
        System.out.println("DONE " + succ);
        System.out.println("TODO " + failed);
        if (failed > 0) {
            throw new RuntimeException();
        }
    }

    private void assertEquals(String jsonPath) {
        System.out.println("Comparing " + jsonPath);
        URL u = Resources.getResource("ccd-definition/" + jsonPath);
        File expected = new File(u.getPath());
        File actual = new File(prodConfig.toFile(), jsonPath);
        assertEquals(expected, actual);
    }

    @SneakyThrows
    private void assertEquals(File expected, File actual) {
        if (expected.getName().contains("nonprod")) {
            return;
        }
        try {
            String expectedString = FileUtils.readFileToString(expected, Charset.defaultCharset());
            String actualString = FileUtils.readFileToString(actual, Charset.defaultCharset());
            // ID irrelevant to this sheet.
            boolean stripID = expected.getAbsolutePath().contains("CaseEventToComplexTypes");
            expectedString = stripIrrelevant(expectedString, stripID);
            actualString = stripIrrelevant(actualString, stripID);
            JSONCompareResult result = JSONCompare.compareJSON(expectedString, actualString, JSONCompareMode.NON_EXTENSIBLE);
            if (result.failed()) {
                System.out.println("Failed comparing " + expected.getName() + " to " + actual.getName());
                System.out.println(result.toString());

                List<Map<String, Object>> expectedValues = fromJSON(expectedString);
                List<Map<String, Object>> actualValues = fromJSON(actualString);

                Set<Object> expectedIds = expectedValues.stream().map(x -> x.get("CaseFieldID"))
                    .collect(Collectors.toSet());
                Set<Object> actualIDs = actualValues.stream().map(x -> x.get("CaseFieldID"))
                    .collect(Collectors.toSet());

                SetView<Object> m = Sets.difference(expectedIds, actualIDs);
                System.out.println(m.size() + " missing:");
                System.out.println(m);

                SetView<Object> u = Sets.difference(actualIDs, expectedIds);
                System.out.println(u.size() + " unexpected:");
                System.out.println(u.stream().sorted().collect(Collectors.toList()));

                Collection<Map<String, Object>> missing = Collections2
                    .filter(fromJSON(expectedString), Predicates.not(Predicates.in(actualValues)));

                System.out.println(missing.size() + " missing values:");
                int count = 1;
                for (Map<String, Object> missingValue : missing) {
                    System.out.println(count++);
                    debugMissingValue(actualValues, missingValue);
                }

                Collection<Map<String, Object>> unexpected = Collections2
                    .filter(fromJSON(actualString), Predicates.not(Predicates.in(expectedValues)));

                System.out.println(unexpected.size() + " unexpected values:");
                count = 1;
                for (Map<String, Object> unexpectedValue : unexpected) {
                    System.out.println(count++);
                    debugMissingValue(expectedValues, unexpectedValue);
                }

                System.out.println("ACTUAL count:" + actualValues.size());
                System.out.println("ACTUAL:");
                System.out.println(actualString);
                throw new RuntimeException("Compare failed for " + expected.getPath());
            }

        } catch (Exception e) {
            System.out.println("Generated files:");
            for (Iterator<File> it = FileUtils.iterateFiles(prodConfig.toFile(), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
                File f = it.next();
                System.out.println(f.getPath());
            }
            throw e;
        }
    }

    private String stripIrrelevant(String json, boolean stripID) {
        List<Map<String, Object>> entries = fromJSON(json);
        for (Map<String, Object> entry : entries) {
            entry.remove("Comment");
            entry.remove("DisplayOrder");
            entry.remove("PageFieldDisplayOrder");
            entry.remove("FieldDisplayOrder");
            entry.remove("EventElementLabel");
            entry.remove("ElementLabel");
            if (stripID) {
               entry.remove("ID");
            }
            if ("Public".equals(entry.get("SecurityClassification"))) {
                entry.remove("SecurityClassification");
            }
            if (" ".equals(entry.get("EventElementLabel"))) {
                entry.remove("EventElementLabel");
            }

            if (" ".equals(entry.get("PageLabel"))) {
                entry.remove("PageLabel");
            }

            if ("N".equals(entry.get("ShowSummary"))) {
                entry.remove("ShowSummary");
            }
            if ("N".equals(entry.get("ShowEventNotes"))) {
                entry.remove("ShowEventNotes");
            }
            if ("N".equals(entry.get("ShowSummaryChangeOption"))) {
                entry.remove("ShowSummaryChangeOption");
            }
            if ("No".equals(entry.get("ShowSummaryChangeOption"))) {
                entry.remove("ShowSummaryChangeOption");
            }
        }

        return JsonUtils.serialise(entries);
    }

    private void debugMissingValue(List<Map<String, Object>> actualValues,
        Map<String, Object> missingValue) {
        if (actualValues.isEmpty()) {
            throw new RuntimeException("No values!");
        }
        Map<String, Object> match = getClosest(missingValue, actualValues);
        System.out.println(pretty(ImmutableSortedMap.copyOf(missingValue)));
        System.out.println("best match:");
        System.out.println(pretty(ImmutableSortedMap.copyOf(match)));
        MapDifference<String, Object> diff = Maps
            .difference(missingValue, match);
        if (!diff.entriesOnlyOnLeft().isEmpty()) {
            System.out.println("Only on left:");
            System.out.println(diff.entriesOnlyOnLeft());
        }
        if (!diff.entriesOnlyOnRight().isEmpty()) {
            System.out.println("Only on right:");
            System.out.println(diff.entriesOnlyOnRight());
        }
        if (!diff.entriesDiffering().isEmpty()) {
            System.out.println("Differing values:");
            System.out.println(diff.entriesDiffering());
        }
    }

    private Map<String, Object> getClosest(Map<String, Object> missing, List<Map<String, Object>> expected) {
        int bestScore = Integer.MAX_VALUE;
        Map<String, Object> result = null;
        for (Map<String, Object> e : expected) {
            MapDifference<String, Object> diff = Maps.difference(missing, e);
            int score = diff.entriesOnlyOnLeft().size() + diff.entriesOnlyOnRight().size();
            for (ValueDifference<Object> value : diff.entriesDiffering().values()) {
              if (value.leftValue() != null && value.rightValue() != null) {
                  score += StringUtils.getLevenshteinDistance(value.leftValue().toString(),
                      value.rightValue().toString());
              }
            }

            if (score < bestScore) {
                result = e;
                bestScore = score;
            }
        }

        return result;
    }

    @SneakyThrows
    private List<Map<String, Object>> fromJSON(String json) {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType mapCollectionType = mapper.getTypeFactory()
            .constructCollectionType(List.class, Map.class);
        return mapper.readValue(json, mapCollectionType);
    }

    @SneakyThrows
    private String pretty(Object obj) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    @SneakyThrows
    private String pretty(String json) {
        ObjectMapper mapper = new ObjectMapper();
        Object o = mapper.readValue(json, Object.class);
        return pretty(o);
    }
}
