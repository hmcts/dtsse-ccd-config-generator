package uk.gov.hmcts.divorce.roundtrip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Case data that places every SDK complex type behind a prefixed {@code @JsonUnwrapped} and in a
 * plain nested complex type, plus a comparison that reports every value lost on the way back.
 */
public final class RoundTripFixture {

    public static final String SDK_COMPLEX_TYPES = "roundtrip/sdk-complex-types.json";

    private RoundTripFixture() {
    }

    public static String load(String resource, long caseReference) {
        try (InputStream stream = RoundTripFixture.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing round-trip fixture " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("{{caseReference}}", String.valueOf(caseReference));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read round-trip fixture " + resource, e);
        }
    }

    public static Map<String, Object> loadAsMap(ObjectMapper mapper, String resource, long caseReference) {
        try {
            return mapper.readValue(load(resource, caseReference), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to parse round-trip fixture " + resource, e);
        }
    }

    /**
     * Lists every path in {@code expected} that is missing or different in {@code actual}.
     * Values that only exist in {@code actual} are ignored, so fields added by callbacks or CCD
     * do not count as loss.
     */
    public static List<String> lostOrChanged(JsonNode expected, JsonNode actual) {
        List<String> differences = new ArrayList<>();
        compare("", expected, actual, differences);
        return differences;
    }

    public static String describe(List<String> differences) {
        return differences.size() + " value(s) lost or changed:" + System.lineSeparator()
            + String.join(System.lineSeparator(), differences);
    }

    private static void compare(String path, JsonNode expected, JsonNode actual, List<String> differences) {
        if (actual == null || actual.isMissingNode()) {
            differences.add("LOST    " + path + " expected " + expected);
            return;
        }
        if (actual.isNull()) {
            if (!expected.isNull()) {
                differences.add("LOST    " + path + " expected " + expected);
            }
            return;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                differences.add("CHANGED " + path + " expected " + expected + " but was " + actual);
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                compare(path + "." + field.getKey(), field.getValue(), actual.get(field.getKey()), differences);
            }
            return;
        }
        if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) {
                differences.add("CHANGED " + path + " expected " + expected + " but was " + actual);
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                compare(path + "[" + i + "]", expected.get(i), actual.get(i), differences);
            }
            return;
        }
        if (!expected.equals(actual)) {
            differences.add("CHANGED " + path + " expected " + expected + " but was " + actual);
        }
    }
}
