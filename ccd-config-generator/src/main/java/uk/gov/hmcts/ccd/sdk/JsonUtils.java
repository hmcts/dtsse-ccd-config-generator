package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;

public class JsonUtils {

  @SneakyThrows
  private static void writeFile(Path path, String value) {
    Files.write(path, value.getBytes());
  }

  @SneakyThrows
  public static String serialise(List data) {
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    return new ObjectMapper().writer(printer).writeValueAsString(data);
  }

  public static Map<String, Object> getField(String id) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("SecurityClassification", "Public");
    field.put("ID", id);
    return field;
  }

  public static List<Map<String, Object>> mergeInto(List<Map<String, Object>> existing,
      List<Map<String, Object>> generated, Set<String> overwriteFields, String... primaryKeys) {
    for (Map<String, Object> generatedField : generated) {
      Optional<Map<String, Object>> existingMatch = existing.stream().filter(x -> {
        for (String primaryKey : primaryKeys) {
          if (!x.get(primaryKey).equals(generatedField.get(primaryKey).toString())) {
            return false;
          }
        }

        return true;
      }).findFirst();
      if (!existingMatch.isPresent()) {
        System.out.println("Adding new field " + generatedField.get(primaryKeys[0]));
        existing.add(generatedField);
      } else {
        Map<String, Object> match = existingMatch.get();
        for (String generatedKey : generatedField.keySet()) {
          if (!match.containsKey(generatedKey) || overwriteFields.contains(generatedKey)) {
            match.put(generatedKey, generatedField.get(generatedKey));
          }
        }
      }
    }

    return existing;
  }

  @SneakyThrows
  public static void mergeInto(Path path, List<Map<String, Object>> fields,
      Set<String> overwritesFields, String... primaryKeys) {
    System.out.println("Merging into " + path.getFileName());
    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> existing;
    if (path.toFile().exists()) {
      CollectionType mapCollectionType = mapper.getTypeFactory()
          .constructCollectionType(List.class, Map.class);
      existing = mapper.readValue(path.toFile(), mapCollectionType);
    } else {
      existing = Lists.newArrayList();
    }

    mergeInto(existing, fields, overwritesFields, primaryKeys);

    writeFile(path, serialise(existing));
  }

  public static void mergeInto(Path path, List<Map<String, Object>> fields, String... primaryKeys) {
    if (primaryKeys.length == 0) {
      throw new RuntimeException("No primary keys!");
    }
    mergeInto(path, fields, Sets.newHashSet(), primaryKeys);
  }
}
