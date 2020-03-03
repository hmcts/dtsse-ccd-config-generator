package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;

public class Utils {

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

  @SneakyThrows
  public static void mergeInto(Path path, List<Map<String, Object>> fields, String... primaryKeys) {
    System.out.println("Merging into " + path.getFileName());
    ObjectMapper mapper = new ObjectMapper();
    CollectionType mapCollectionType = mapper.getTypeFactory()
        .constructCollectionType(List.class, Map.class);
    List<Map<String, Object>> existing;
    if (path.toFile().exists()) {
      existing = mapper.readValue(path.toFile(), mapCollectionType);
    } else {
      existing = Lists.newArrayList();
    }

    for (Map<String, Object> field : fields) {
      Optional<Map<String, Object>> existingMatch = existing.stream().filter(x -> {
        for (String primaryKey : primaryKeys) {
          if (!x.get(primaryKey).equals(field.get(primaryKey).toString())) {
            return false;
          }
        }

        return true;
      }).findFirst();
      if (!existingMatch.isPresent()) {
        System.out.println("Adding new field " + field.get(primaryKeys[0]));
        existing.add(field);
      }
    }
    writeFile(path, serialise(existing));
  }
}
