package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.io.Resources;
import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang.StringUtils;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils;

@SpringBootTest(properties = { "config-generator.basePackage=uk.gov.hmcts" })
@RunWith(SpringRunner.class)
public class E2EConfigGenerationTests {
    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder();

    @Autowired
    private CCDDefinitionGenerator generator;
    @Before
    public void before() {
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
        // Generate a second time to ensure existing config is correctly merged.
        generator.generateAllCaseTypesToJSON(tmp.getRoot());
    }

  @Test
  public void generatesCareSupervisionEPO() {
    var actual = dirToMap(new File(tmp.getRoot(), "CARE_SUPERVISION_EPO"));
    var expected = resourcesDirToMap("CARE_SUPERVISION_EPO");
    assertEquivalent(expected, actual);
  }

    @Test
    public void respectsComplexTypeOrdering() {
      assertEquals("CARE_SUPERVISION_EPO/ComplexTypes/HearingBooking.json", JSONCompareMode.STRICT);
    }

    @Test
    public void testCustomHistoryTabOrder() {
      var actual = dirToMap(new File(tmp.getRoot(), "CustomHistory/CaseTypeTab"));
      var expected = resourcesDirToMap("CustomHistory/CaseTypeTab");
      assertEquivalent(expected, actual);
    }

    @SneakyThrows
    @Test
    public void generatesDerivedConfig() {
      var actual = dirToMap(new File(tmp.getRoot(), "derived"));
      var expected = ImmutableMap.<String, File>builder()
        .putAll(this.resourcesDirToMap("CARE_SUPERVISION_EPO"))
        .putAll(this.resourcesDirToMap("derived"))
        .buildKeepingLast();
      assertEquivalent(expected, actual, "CaseTypeID");
    }

    private void assertEquivalent(Map<String, File> expected, Map<String, File> actual, String... ignoringFieldNames) {
      var diff = Maps.difference(expected, actual);
      var success = true;
      for (var e : diff.entriesOnlyOnLeft().entrySet()) {
        System.out.println("Missing " + e.getKey());
        success = false;
      }

      for (var e : diff.entriesOnlyOnRight().entrySet()) {
        System.out.println("Unexpected " + e.getKey());
        success = false;
      }

      if (!success) {
        throw new RuntimeException("Comparison failed!");
      }

      for (String key : expected.keySet()) {
        assertEquals(expected.get(key), actual.get(key), JSONCompareMode.NON_EXTENSIBLE, ignoringFieldNames);
      }
    }

    private Map<String, File> resourcesDirToMap(String resourcesFolderName) {
      return dirToMap(new File(Resources.getResource(resourcesFolderName).getPath()));
    }

    // A map of all files in a directory indexed by their relative path.
    @SneakyThrows
    private Map<String, File> dirToMap(File dir) {
      try (var stream = Files.walk(dir.toPath())) {
        return stream
          .filter(Files::isRegularFile)
          .collect(Collectors.toUnmodifiableMap(x -> dir.toPath().relativize(x).toString(), Path::toFile));
      }
    }

    @SneakyThrows
    private void assertEquals(String jsonPath, JSONCompareMode mode) {
        System.out.println("Comparing " + jsonPath);
        URL u = Resources.getResource(jsonPath);
        File expected = new File(u.getPath());
        File actual = new File(tmp.getRoot(), jsonPath);
        assertEquals(expected, actual, mode);
    }

    @SneakyThrows
    private void assertEquals(File expected, File actual, JSONCompareMode mode, String... ignoring) {
        if (expected.getName().contains("nonprod")) {
            return;
        }
        try {
            String expectedString = FileUtils.readFileToString(expected, Charset.defaultCharset());
            String actualString = FileUtils.readFileToString(actual, Charset.defaultCharset());
            // ID irrelevant to this sheet.
            boolean stripID = expected.getAbsolutePath().contains("CaseEventToComplexTypes");
            expectedString = stripIrrelevant(expectedString, stripID, ignoring);
            actualString = stripIrrelevant(actualString, stripID, ignoring);
            JSONCompareResult result = JSONCompare.compareJSON(expectedString, actualString, mode);
            if (result.failed()) {
                System.out.println("Failed comparing expected " + expected.getPath() + " to actual " + actual.getPath());
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
                System.out.println(FileUtils.readFileToString(actual, Charset.defaultCharset()));
                throw new RuntimeException("Compare failed for " + expected.getPath());
            }

        } catch (Exception e) {
            if (tmp.getRoot().exists()) {
                System.out.println("Generated files:");
                for (Iterator<File> it = FileUtils.iterateFiles(tmp.getRoot(), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE); it.hasNext(); ) {
                    File f = it.next();
                    System.out.println(f.getPath());
                }
            } else if (!actual.exists()) {
              System.out.println(actual.getName() + "Does not exist");
            }
            throw e;
        }
    }

    private String stripIrrelevant(String json, boolean stripID, String... ignoring) {
        List<Map<String, Object>> entries = fromJSON(json);
        for (Map<String, Object> entry : entries) {
            for (String s : ignoring) {
              entry.remove(s);
            }

            entry.remove("Comment");
            entry.remove("DisplayOrder");
            entry.remove("FieldDisplayOrder");
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
          if ("N".equals(entry.get("Publish"))) {
               entry.remove("Publish");
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

        return JsonUtils.serialise(entries, true);
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
}
