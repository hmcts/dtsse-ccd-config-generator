package uk.gov.hmcts.ccd.sdk.diff;

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
import com.google.common.io.Resources;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils;

/**
 * Utility to compare CCD configuration output directories and surface semantic differences.
 */
public final class CcdConfigComparator {

    private CcdConfigComparator() {
    }

    public static void compareDirectories(File expectedDir, File actualDir, String... ignoringFieldNames) {
        compareDirectories(expectedDir, actualDir, JSONCompareMode.NON_EXTENSIBLE, ignoringFieldNames);
    }

    public static void compareDirectories(File expectedDir, File actualDir, JSONCompareMode mode,
                                          String... ignoringFieldNames) {
        Map<String, File> expected = dirToMap(expectedDir);
        Map<String, File> actual = dirToMap(actualDir);
        assertEquivalent(expected, actual, mode, ignoringFieldNames);
    }

    public static void assertEquivalent(Map<String, File> expected, Map<String, File> actual,
                                        String... ignoringFieldNames) {
        assertEquivalent(expected, actual, JSONCompareMode.NON_EXTENSIBLE, ignoringFieldNames);
    }

    public static void assertEquivalent(Map<String, File> expected, Map<String, File> actual, JSONCompareMode mode,
                                        String... ignoringFieldNames) {
        MapDifference<String, File> diff = Maps.difference(expected, actual);
        boolean success = true;
        for (Map.Entry<String, File> entry : diff.entriesOnlyOnLeft().entrySet()) {
            System.out.println("Missing " + entry.getKey());
            success = false;
        }

        for (Map.Entry<String, File> entry : diff.entriesOnlyOnRight().entrySet()) {
            System.out.println("Unexpected " + entry.getKey());
            success = false;
        }

        if (!success) {
            throw new RuntimeException("Comparison failed!");
        }

        for (String key : expected.keySet()) {
            assertEquals(expected.get(key), actual.get(key), mode, ignoringFieldNames);
        }
    }

    @SneakyThrows
    public static void assertEquals(File expected, File actual, JSONCompareMode mode, String... ignoring) {
        if (expected.getName().contains("nonprod")) {
            return;
        }
        try {
            String expectedString = FileUtils.readFileToString(expected, Charset.defaultCharset());
            String actualString = FileUtils.readFileToString(actual, Charset.defaultCharset());
            boolean stripID = expected.getAbsolutePath().contains("CaseEventToComplexTypes");
            expectedString = stripIrrelevant(expectedString, stripID, ignoring);
            actualString = stripIrrelevant(actualString, stripID, ignoring);
            JSONCompareResult result = JSONCompare.compareJSON(expectedString, actualString, mode);
            if (result.failed()) {
                System.out.println(
                    "Failed comparing expected " + expected.getPath() + " to actual " + actual.getPath());
                System.out.println(result);

                List<Map<String, Object>> expectedValues = fromJSON(expectedString);
                List<Map<String, Object>> actualValues = fromJSON(actualString);

                Set<Object> expectedIds = expectedValues.stream().map(x -> x.get("CaseFieldID"))
                    .collect(Collectors.toSet());
                Set<Object> actualIDs = actualValues.stream().map(x -> x.get("CaseFieldID"))
                    .collect(Collectors.toSet());

                SetView<Object> missing = Sets.difference(expectedIds, actualIDs);
                System.out.println(missing.size() + " missing:");
                System.out.println(missing);

                SetView<Object> unexpected = Sets.difference(actualIDs, expectedIds);
                System.out.println(unexpected.size() + " unexpected:");
                System.out.println(unexpected.stream().sorted().collect(Collectors.toList()));

                Collection<Map<String, Object>> missingValues = Collections2
                    .filter(fromJSON(expectedString), Predicates.not(Predicates.in(actualValues)));

                System.out.println(missingValues.size() + " missing values:");
                int count = 1;
                for (Map<String, Object> missingValue : missingValues) {
                    System.out.println(count++);
                    debugDifference(actualValues, missingValue);
                }

                Collection<Map<String, Object>> unexpectedValues = Collections2
                    .filter(fromJSON(actualString), Predicates.not(Predicates.in(expectedValues)));

                System.out.println(unexpectedValues.size() + " unexpected values:");
                count = 1;
                for (Map<String, Object> unexpectedValue : unexpectedValues) {
                    System.out.println(count++);
                    debugDifference(expectedValues, unexpectedValue);
                }

                System.out.println("ACTUAL count:" + actualValues.size());
                System.out.println("ACTUAL:");
                System.out.println(FileUtils.readFileToString(actual, Charset.defaultCharset()));
                throw new RuntimeException("Compare failed for " + expected.getPath());
            }

        } catch (IOException e) {
            if (!actual.exists()) {
                System.out.println(actual.getName() + "Does not exist");
            }
            throw e;
        } catch (Exception e) {
            if (actual != null) {
                File root = actual.getParentFile();
                if (root != null && root.exists()) {
                    System.out.println("Generated files:");
                    for (var iterator = FileUtils.iterateFiles(root, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);
                         iterator.hasNext(); ) {
                        File file = iterator.next();
                        System.out.println(file.getPath());
                    }
                } else if (!actual.exists()) {
                    System.out.println(actual.getName() + "Does not exist");
                }
            }
            throw e;
        }
    }

    public static Map<String, File> dirToMap(File dir) {
        try {
            return Files.walk(dir.toPath())
                .filter(Files::isRegularFile)
                .collect(Collectors.toUnmodifiableMap(path -> relativePath(dir.toPath(), path), Path::toFile));
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files under " + dir, e);
        }
    }

    public static Map<String, File> resourcesDirToMap(String resourcesFolderName) {
        try {
            URL resource = Resources.getResource(resourcesFolderName);
            return dirToMap(new File(resource.toURI()));
        } catch (Exception e) {
            throw new RuntimeException("Unable to locate resources for " + resourcesFolderName, e);
        }
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString();
    }

    private static String stripIrrelevant(String json, boolean stripID, String... ignoring) {
        List<Map<String, Object>> entries = fromJSON(json);
        for (Map<String, Object> entry : entries) {
            for (String field : ignoring) {
                entry.remove(field);
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

    private static void debugDifference(List<Map<String, Object>> values, Map<String, Object> target) {
        if (values.isEmpty()) {
            throw new RuntimeException("No values!");
        }
        Map<String, Object> match = getClosest(target, values);
        System.out.println(pretty(ImmutableSortedMap.copyOf(target)));
        System.out.println("best match:");
        System.out.println(pretty(ImmutableSortedMap.copyOf(match)));
        MapDifference<String, Object> diff = Maps.difference(target, match);
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

    private static Map<String, Object> getClosest(Map<String, Object> missing, List<Map<String, Object>> expected) {
        int bestScore = Integer.MAX_VALUE;
        Map<String, Object> result = null;
        for (Map<String, Object> candidate : expected) {
            MapDifference<String, Object> diff = Maps.difference(missing, candidate);
            int score = diff.entriesOnlyOnLeft().size() + diff.entriesOnlyOnRight().size();
            for (ValueDifference<Object> value : diff.entriesDiffering().values()) {
                if (value.leftValue() != null && value.rightValue() != null) {
                    score += StringUtils.getLevenshteinDistance(value.leftValue().toString(),
                        value.rightValue().toString());
                }
            }

            if (score < bestScore) {
                result = candidate;
                bestScore = score;
            }
        }

        return result;
    }

    @SneakyThrows
    private static List<Map<String, Object>> fromJSON(String json) {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType mapCollectionType = mapper.getTypeFactory()
            .constructCollectionType(List.class, Map.class);
        return mapper.readValue(json, mapCollectionType);
    }

    @SneakyThrows
    private static String pretty(Object obj) {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }
}
