package ccd.sdk.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Utils {

    public static void writeFile(Path path, String value) {
        try {
            Files.writeString(path, value);
        } catch (IOException e) {
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
}
