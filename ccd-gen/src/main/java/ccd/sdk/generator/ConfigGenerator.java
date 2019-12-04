package ccd.sdk.generator;

import ccd.sdk.types.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigGenerator {
    private final File outputfolder;
    private final Reflections reflections;

    public ConfigGenerator(Reflections reflections, File outputFolder) {
        this.reflections = reflections;
        this.outputfolder = outputFolder;
    }

    public void generate(String caseTypeId) {
        generateComplex();
    }



    public void generateComplex() {
        File complexTypes = new File(outputfolder, "ComplexTypes");
        complexTypes.mkdir();

        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        for (Class<?> type : types) {
            Path path = Paths.get(complexTypes.getPath(), type.getSimpleName() + ".json");
            writeFile(path, toComplexType(type));
        }
    }

    public static String toComplexType(Class c) {
        Map<String, Object> label = getField(c.getSimpleName());
        label.put("ListElementCode", c.getSimpleName().toLowerCase() + "Label");
        ComplexType a = (ComplexType) c.getAnnotation(ComplexType.class);
        label.put("FieldType", "Label");
        label.put("ElementLabel", a.label());

        List<Map<String, Object>> fields = Lists.newArrayList();
        fields.add(label);
        for (Field field : ReflectionUtils.getFields(c)) {
            Map<String, Object> fieldInfo = getField(c.getSimpleName());
            fieldInfo.put("ListElementCode", field.getName());
            fieldInfo.put("FieldType", "Text");
            fieldInfo.put("HintText", "Foo");

            CaseField f = field.getAnnotation(CaseField.class);
            fieldInfo.put("ElementLabel", f.label());
            if (f.hint().length() > 0) {
                fieldInfo.put("HintText", f.hint());
            }

            if (f.type() != FieldType.Unspecified) {
                fieldInfo.put("FieldType", f.type().toString());
            }

            fields.add(fieldInfo);
        }

        return serialise(fields);
    }

    private static void writeFile(Path path, String value) {
        try {
            Files.writeString(path, value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String serialise(List<Map<String, Object>> data) {
        try {
            return new ObjectMapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> getField(String id) {
        Map<String, Object> field = Maps.newHashMap();
        field.put("LiveFrom", "01/01/2017");
        field.put("SecurityClassification", "Public");
        field.put("ID", id);
        return field;
    }
}
