package ccd.sdk.generator;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ComplexType;
import ccd.sdk.types.FieldType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.reflections.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class ConfigGenerator {
    public static String toComplexType(Class c) {
        Map<String, Object> label = getField(c);
        label.put("ListElementCode", c.getSimpleName().toLowerCase() + "Label");
        ComplexType a = (ComplexType) c.getAnnotation(ComplexType.class);
        label.put("FieldType", "Label");
        label.put("ElementLabel", a.label());

        List<Map<String, Object>> fields = Lists.newArrayList();
        fields.add(label);
        for (Field field : ReflectionUtils.getFields(c)) {
            Map<String, Object> fieldInfo = getField(c);
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

        try {
            return new ObjectMapper().writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> getField(Class c) {
        Map<String, Object> field = Maps.newHashMap();
        field.put("LiveFrom", "01/01/2017");
        field.put("SecurityClassification", "Public");
        field.put("ID", c.getSimpleName());
        return field;
    }
}
