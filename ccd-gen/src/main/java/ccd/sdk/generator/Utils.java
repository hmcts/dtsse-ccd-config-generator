package ccd.sdk.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Utils {

    public static void writeFile(Path path, String value) {
        try {
            Files.writeString(path, pretty(value));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String pretty(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Object o = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String serialise(List data) {
        try {
            return new ObjectMapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> getField(String id) {
        Map<String, Object> field = Maps.newHashMap();
        field.put("LiveFrom", "01/01/2017");
        field.put("SecurityClassification", "Public");
        field.put("ID", id);
        return field;
    }

    public static void mergeInto(Path path, List<Map<String, Object>> fields, String primaryKey) {
        System.out.println("Merging into " + path.getFileName());
        ObjectMapper mapper = new ObjectMapper();
        CollectionType mapCollectionType = mapper.getTypeFactory().constructCollectionType(List.class, Map.class);
        List<Map<String, Object>> existing;
        try {
            if (path.toFile().exists()) {
                existing = mapper.readValue(path.toFile(), mapCollectionType);
            } else {
                existing = Lists.newArrayList();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (Map<String, Object> field : fields) {
            Object pk = field.get(primaryKey);

            Optional<Map<String, Object>> existingMatch = existing.stream().filter(x -> x.get(primaryKey).equals(pk)).findFirst();
            if (!existingMatch.isPresent()) {
                System.out.println("Adding new field " + field.get(primaryKey));
                existing.add(field);
            }
        }
        writeFile(path, serialise(existing));
    }
}
