package uk.gov.hmcts.ccd.sdk.generator;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.primitives.Chars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Label;

public class JsonUtils {

  public static final String DEFAULT_LIVE_FROM = "01/01/2017";

  @SneakyThrows
  private static void writeFile(Path path, String value) {
    Files.writeString(path, value, StandardCharsets.UTF_8);
  }

  @SneakyThrows
  public static String serialise(List<Map<String, Object>> data, boolean sort, String... primaryKeys) {
    class CustomPrinter extends DefaultPrettyPrinter {
      @Override
      public DefaultPrettyPrinter createInstance() {
        CustomPrinter result = new CustomPrinter();
        result.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return result;
      }

      @Override
      public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
        jg.writeRaw(": ");
      }
    }

    // Emit deterministic JSON so we get exactly the same json output for a given configuration.
    // This allows Gradle to skip converting it to xlsx if unchanged (eg. if we only changed a callback).
    // We make the json deterministic by sorting the maps by their primary keys and sorting the keys in each map.
    if (sort) {
      data.sort((a, b) -> {
        var chain = ComparisonChain.start();
        for (String primaryKey : primaryKeys) {
          chain = chain.compare(String.valueOf(a.get(primaryKey)), String.valueOf(b.get(primaryKey)));
        }
        return chain.result();
      });
    }

    return new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
      .writer(new CustomPrinter()).writeValueAsString(data) + "\n";
  }

  public static Map<String, Object> getField(String id) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", DEFAULT_LIVE_FROM);
    field.put("SecurityClassification", "Public");
    field.put("ID", id);
    return field;
  }

  public static Map<String, Object> caseRow(String caseTypeId) {
    return caseRow(caseTypeId, DEFAULT_LIVE_FROM);
  }

  public static Map<String, Object> caseRow(String caseTypeId, String liveFrom) {
    Map<String, Object> row = Maps.newHashMap();
    row.put("LiveFrom", liveFrom);
    row.put("CaseTypeID", caseTypeId);
    return row;
  }

  public static void putYn(Map<String, Object> target, String key, boolean condition) {
    target.put(key, condition ? "Y" : "N");
  }

  public static String yn(boolean condition) {
    return condition ? "Y" : "N";
  }

  static void applyCcdAnnotation(Map<String, Object> target, CCD annotation) {
    if (annotation == null) {
      return;
    }

    if (!target.containsKey("Label")) {
      target.put("Label", annotation.label().isEmpty() ? " " : annotation.label());
    }
    if (!Strings.isNullOrEmpty(annotation.hint())) {
      target.put("HintText", annotation.hint());
    }
    if (!Strings.isNullOrEmpty(annotation.regex())) {
      target.put("RegularExpression", annotation.regex());
    }
    if (annotation.showSummaryContent()) {
      target.put("ShowSummaryContentOption", "Y");
    }
    if (!annotation.searchable()) {
      target.put("Searchable", "N");
    }
    if (!Strings.isNullOrEmpty(annotation.showCondition())) {
      target.put("FieldShowCondition", annotation.showCondition());
    }
    if (annotation.displayOrder() > 0) {
      target.put("DisplayOrder", annotation.displayOrder());
    }
    if (!Strings.isNullOrEmpty(annotation.categoryID())) {
      target.put("CategoryID", annotation.categoryID());
    }
    if (annotation.min() > Integer.MIN_VALUE) {
      target.put("Min", annotation.min());
    }
    if (annotation.max() < Integer.MAX_VALUE) {
      target.put("Max", annotation.max());
    }
  }

  static void ensureDefaultLabel(Map<String, Object> target) {
    target.putIfAbsent("Label", " ");
  }

  static void applyLabelAnnotation(
      List<Map<String, Object>> target, String caseTypeId, Label annotation) {
    if (annotation == null) {
      return;
    }

    Map<String, Object> labelField = CaseFieldGenerator.getField(caseTypeId, annotation.id());
    labelField.put("FieldType", "Label");
    labelField.put("Label", annotation.value());
    target.add(labelField);
  }

  static List<Map<String, Object>> mergeInto(List<Map<String, Object>> existing,
      List<Map<String, Object>> generated, JsonMerger merger, String... primaryKeys) {
    for (Map<String, Object> generatedField : generated) {
      Optional<Map<String, Object>> existingMatch = existing.stream().filter(x -> {
        for (String primaryKey : primaryKeys) {
          if (!x.containsKey(primaryKey)) {
            return !generatedField.containsKey(primaryKey);
          }
          if (!generatedField.containsKey(primaryKey)) {
            return !x.containsKey(primaryKey);
          }

          if (!x.get(primaryKey).equals(generatedField.get(primaryKey).toString())) {
            return false;
          }
        }

        return true;
      }).findFirst();

      if (!existingMatch.isPresent()) {
        existing.add(generatedField);
      } else {
        Map<String, Object> match = existingMatch.get();
        for (String generatedKey : generatedField.keySet()) {
          if (!match.containsKey(generatedKey)) {
            match.put(generatedKey, generatedField.get(generatedKey));
          } else {
            match.put(generatedKey,
                merger.merge(generatedKey, match.get(generatedKey),
                    generatedField.get(generatedKey)));
          }
        }
      }
    }

    return existing;
  }

  @SneakyThrows
  public static void mergeInto(Path path, List<Map<String, Object>> fields,
                               JsonMerger merger, String... primaryKeys) {
    mergeInto(path,fields, merger, true, primaryKeys);
  }

  @SneakyThrows
  public static void mergeInto(Path path, List<Map<String, Object>> fields,
      JsonMerger merger, boolean sort, String... primaryKeys) {
    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> existing;
    if (path.toFile().exists()) {
      CollectionType mapCollectionType = mapper.getTypeFactory()
          .constructCollectionType(List.class, Map.class);
      existing = mapper.readValue(path.toFile(), mapCollectionType);
    } else {
      existing = Lists.newArrayList();
    }

    mergeInto(existing, fields, merger, primaryKeys);


    writeFile(path, serialise(existing, sort, primaryKeys));
  }

  @FunctionalInterface
  public interface JsonMerger {
    Object merge(String key, Object existingValue, Object generatedValue);
  }

  @AllArgsConstructor
  public static  class OverwriteSpecific implements JsonMerger {
    private Set<String> overwriteKeys;

    @Override
    public Object merge(String key, Object existingValue, Object generatedValue) {
      return overwriteKeys.contains(key) ? generatedValue : existingValue;
    }
  }

  public static class AddMissing extends OverwriteSpecific {
    public AddMissing() {
      super(Sets.newHashSet());
    }
  }

  public static class CRUDMerger implements JsonMerger {

    @Override
    public Object merge(String key, Object existing, Object generated) {
      if (!key.equals("CRUD")) {
        return existing;
      }
      String existingPermissions = existing.toString() + generated.toString();
      // Remove any dupes.
      existingPermissions = Sets.newHashSet(Chars.asList(existingPermissions.toCharArray()))
          .stream().map(String::valueOf).collect(Collectors.joining());

      existingPermissions = existingPermissions.replaceAll("[^CRUD]+", "");
      if (!existingPermissions.matches("^[CRUD]+$")) {
        throw new RuntimeException(existingPermissions);
      }

      List<Character> perm = Chars.asList(existingPermissions.toCharArray());
      Collections.sort(perm, Ordering.explicit('C', 'R', 'U', 'D'));
      return perm.stream().map(String::valueOf).collect(Collectors.joining());
    }
  }
}
