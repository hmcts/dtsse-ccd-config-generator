package ccd.sdk.generator;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.Event;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CaseFieldGenerator {
    public static void generateCaseFields(File outputFolder, String caseTypeId, Class dataClass, List<Event> events, ConfigBuilderImpl builder) {

        List<Map<String, Object>> fields = Lists.newArrayList();

        Map<String, Object> history = getField(caseTypeId, "caseHistory");
        history.put("Label", " ");
        history.put("FieldType", "CaseHistoryViewer");
        fields.add(history);

        for (Field field : ReflectionUtils.getFields(dataClass)) {

            JsonProperty j = field.getAnnotation(JsonProperty.class);
            String id = j != null ? j.value() : field.getName();

            CaseField cf = field.getAnnotation(CaseField.class);
            Map<String, Object> fieldInfo = getField(caseTypeId, id);
            fields.add(fieldInfo);
            if (null != cf) {
                fieldInfo.put("Label", cf.label());
                fieldInfo.put("Hint", cf.hint());
            }
            setFieldType(dataClass, field, fieldInfo);
        }

        fields.addAll(getExplicitFields(caseTypeId, events, builder));

        Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
        Utils.writeFile(path, Utils.serialise(fields));
    }

    private static void setFieldType(Class dataClass, Field field, Map<String, Object> info) {
        String type = field.getType().getSimpleName();
        if (Collection.class.isAssignableFrom(field.getType())) {
            type = "Collection";
            ParameterizedType pType = (ParameterizedType) TypeResolver.reify(field.getGenericType(), dataClass);
            Class typeClass;
            if (pType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                pType = (ParameterizedType) pType.getActualTypeArguments()[0];
                typeClass = (Class) pType.getActualTypeArguments()[0];
            } else {
                typeClass = (Class) pType.getActualTypeArguments()[0];
            }
            info.put("FieldTypeParameter", typeClass.getSimpleName());
        } else {
            switch (type) {
                case "String":
                    type = "Text";
                    break;
                case "LocalDate":
                    type = "Date";
                    break;
            }
        }
        info.put("FieldType", type);
    }

    private static List<Map<String, Object>> getExplicitFields(String caseType, List<Event> events, ConfigBuilderImpl builder) {
        Map<String, ccd.sdk.types.Field> explicitFields = Maps.newHashMap();
        for (Event event : events) {
            List<ccd.sdk.types.Field.FieldBuilder> fc = event.getFields().build().getExplicitFields();

            for (ccd.sdk.types.Field.FieldBuilder fieldBuilder : fc) {
                ccd.sdk.types.Field field = fieldBuilder.build();
                explicitFields.put(field.getId(), field);
            }
        }

        List<Map<String, Object>> result = Lists.newArrayList();
        for (String fieldId : explicitFields.keySet()) {
            ccd.sdk.types.Field field = explicitFields.get(fieldId);
            Map<String, Object> fieldData = getField(caseType, fieldId);
            if (fieldId.equals("[STATE]")) {
                continue;
            }
            result.add(fieldData);
            fieldData.put("Label", field.getLabel());
            String type = field.getType() == null ? "Label" : field.getType();
            fieldData.put("FieldType", type);
            fieldData.put("FieldTypeParameter", field.getFieldTypeParameter());
        }

        List<Map<String, Object>> fs = builder.explicitFields;
        for (Map<String, Object> explicitField : fs) {
            Map<String, Object> entry = getField(caseType, explicitField.get("ID").toString());
            entry.putAll(explicitField);
            result.add(entry);
        }

        return result;
    }

    private static Map<String, Object> getField(String caseType, String id) {
        Map<String, Object> result = Maps.newHashMap();
        result.put("LiveFrom", "01/01/2017");
        result.put("CaseTypeID", caseType);
        result.put("ID", id);
        result.put("SecurityClassification", "Public");
        return result;
    }
}
