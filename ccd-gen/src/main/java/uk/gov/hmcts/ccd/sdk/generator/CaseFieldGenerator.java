package uk.gov.hmcts.ccd.sdk.generator;

import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.Utils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.types.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class CaseFieldGenerator {
    public static void generateCaseFields(File outputFolder, String caseTypeId, Class dataClass, List<Event> events, ConfigBuilderImpl builder) {
        List<Map<String, Object>> fields = toComplex(dataClass, caseTypeId);

        Map<String, Object> history = getField(caseTypeId,"caseHistory");
        history.put("Label", " ");
        history.put("FieldType", "CaseHistoryViewer");
        fields.add(history);

        fields.addAll(getExplicitFields(caseTypeId, events, builder));

        Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
        Utils.mergeInto(path, fields, "ID");
    }

    public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
        List<Map<String, Object>> fields = Lists.newArrayList();

        for (Field field : ReflectionUtils.getAllFields(dataClass)) {

            CaseField cf = field.getAnnotation(CaseField.class);
            if (null != cf) {
                if (cf.ignore()) {
                    continue;
                }
            }
            JsonProperty j = field.getAnnotation(JsonProperty.class);
            String id = j != null ? j.value() : field.getName();

            Label label = field.getAnnotation(Label.class);
            if (null != label) {

                Map<String, Object> fieldInfo = getField(caseTypeId, label.id());
                fieldInfo.put("FieldType", "Label");
                fieldInfo.put("Label", label.value());
                fields.add(fieldInfo);
            }


            Map<String, Object> fieldInfo = getField(caseTypeId, id);
            fields.add(fieldInfo);
            if (null != cf) {
                fieldInfo.put("Label", cf.label());
                if (!Strings.isNullOrEmpty(cf.hint())) {
                    fieldInfo.put("HintText", cf.hint());
                }
                if (cf.showSummaryContent()) {
                    fieldInfo.put("ShowSummaryContentOption", "Y");
                }
                if (!Strings.isNullOrEmpty(cf.showCondition())) {
                    fieldInfo.put("FieldShowCondition", cf.showCondition());
                }
            }

            if (cf != null && cf.type() != FieldType.Unspecified) {
                fieldInfo.put("FieldType", cf.type().toString());
                if (!Strings.isNullOrEmpty(cf.typeParameter())) {
                    fieldInfo.put("FieldTypeParameter", cf.typeParameter());
                }
            } else {
                inferFieldType(dataClass, field, fieldInfo, cf);
            }

        }

        return fields;
    }

    private static void inferFieldType(Class dataClass, Field field, Map<String, Object> info, CaseField cf) {
        String type = field.getType().getSimpleName();
        if (null != cf && !Strings.isNullOrEmpty(cf.typeParameter())) {
            info.put("FieldTypeParameter", cf.typeParameter());
        }
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
            ComplexType c = (ComplexType) typeClass.getAnnotation(ComplexType.class);
            if (null != c && !Strings.isNullOrEmpty(c.name())) {
                info.put("FieldTypeParameter", c.name());
            } else {
                if (null != cf && !Strings.isNullOrEmpty(cf.typeParameter())) {
                    type = "MultiSelectList";
                } else {
                    info.put("FieldTypeParameter", typeClass.getSimpleName());
                }
            }
        } else {
            if (field.getType().isEnum()) {
                type = "FixedRadioList";
                info.putIfAbsent("FieldTypeParameter", field.getType().getSimpleName());
            } else {
                switch (type) {
                    case "String":
                        type = "Text";
                        if (cf != null && !Strings.isNullOrEmpty(cf.typeParameter())) {
                            type = "FixedList";
                        }
                        break;
                    case "LocalDate":
                        type = "Date";
                        break;
                    case "LocalDateTime":
                        type = "DateTime";
                        break;
                }
            }
        }
        ComplexType c = field.getType().getAnnotation(ComplexType.class);
        if (null != c && !Strings.isNullOrEmpty(c.name())) {
            type = c.name();
        }
        info.put("FieldType", type);
    }

    private static List<Map<String, Object>> getExplicitFields(String caseType, List<Event> events, ConfigBuilderImpl builder) {
        Map<String, uk.gov.hmcts.ccd.sdk.types.Field> explicitFields = Maps.newHashMap();
        for (Event event : events) {
            List<uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder> fc = event.getFields().build().getExplicitFields();

            for (uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder fieldBuilder : fc) {
                uk.gov.hmcts.ccd.sdk.types.Field field = fieldBuilder.build();
                explicitFields.put(field.getId(), field);
            }
        }

        List<Map<String, Object>> result = Lists.newArrayList();
        for (String fieldId : explicitFields.keySet()) {
            uk.gov.hmcts.ccd.sdk.types.Field field = explicitFields.get(fieldId);
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

    public static Map<String, Object> getField(String caseType, String id) {
        Map<String, Object> result = Maps.newHashMap();
        result.put("LiveFrom", "01/01/2017");
        result.put("CaseTypeID", caseType);
        result.put("ID", id);
        result.put("SecurityClassification", "Public");
        return result;
    }
}
