package uk.gov.hmcts.ccd.sdk.generator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.jodah.typetools.TypeResolver;
import org.apache.commons.lang3.StringUtils;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.JsonUtils.OverwriteSpecific;
import uk.gov.hmcts.ccd.sdk.types.CCD;
import uk.gov.hmcts.ccd.sdk.types.ComplexType;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.types.FieldType;
import uk.gov.hmcts.ccd.sdk.types.Label;

import static java.util.stream.Collectors.toSet;

public class CaseFieldGenerator {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  public static void generateCaseFields(File outputFolder, String caseTypeId, Class dataClass,
      List<Event> events, ConfigBuilderImpl builder) {
    List<Map<String, Object>> fields = toComplex(dataClass, caseTypeId);

    Map<String, Object> history = getField(caseTypeId, "caseHistory");
    history.put("Label", " ");
    history.put("FieldType", "CaseHistoryViewer");
    fields.add(history);

    fields.addAll(getExplicitFields(caseTypeId, events, builder));

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    List<Map<String, Object>> fields = Lists.newArrayList();

    for (Field field : getAllFieldDistinct(dataClass)) {

      CCD cf = field.getAnnotation(CCD.class);
      if (null != cf) {
        if (cf.ignore()) {
          continue;
        }
      }

      if (field.getAnnotation(JsonIgnore.class) != null) {
        continue;
      }

      JsonUnwrapped ju = field.getAnnotation(JsonUnwrapped.class);
      if (ju != null) {
        fields.addAll(toComplex(field.getType(), caseTypeId));
        continue;
      }
      JsonProperty j = field.getAnnotation(JsonProperty.class);
      String id = j != null && StringUtils.isNotBlank(j.value()) ? j.value() : field.getName();

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

  private static Set<Field> getAllFieldDistinct(Class dataClass) {
    Set<String> fields = ReflectionUtils.getFields(dataClass)
            .stream().map(Field::getName).collect(toSet());

    return ReflectionUtils.getAllFields(dataClass, field ->
            field.getDeclaringClass() == dataClass || !fields.contains(field.getName())
    );
  }

  private static void inferFieldType(Class dataClass, Field field, Map<String, Object> info,
      CCD cf) {
    String type = field.getType().getSimpleName();
    if (null != cf && !Strings.isNullOrEmpty(cf.typeParameter())) {
      info.put("FieldTypeParameter", cf.typeParameter());
    }
    if (Collection.class.isAssignableFrom(field.getType())) {
      type = "Collection";
      ParameterizedType parameterizedType = (ParameterizedType) TypeResolver
          .reify(field.getGenericType(), dataClass);
      Class typeClass;
      if (parameterizedType.getActualTypeArguments()[0] instanceof ParameterizedType) {
        parameterizedType = (ParameterizedType) parameterizedType.getActualTypeArguments()[0];
        typeClass = (Class) parameterizedType.getActualTypeArguments()[0];
      } else {
        typeClass = (Class) parameterizedType.getActualTypeArguments()[0];
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
          case "int":
          case "float":
          case "double":
          case "Integer":
          case "Float":
          case "Double":
            type = "Number";
            break;
          default:
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

  private static List<Map<String, Object>> getExplicitFields(String caseType, List<Event> events,
      ConfigBuilderImpl builder) {
    Map<String, uk.gov.hmcts.ccd.sdk.types.Field> explicitFields = Maps.newHashMap();
    for (Event event : events) {
      List<uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder> fc = event.getFields().build()
          .getExplicitFields();

      for (uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder fieldBuilder : fc) {
        uk.gov.hmcts.ccd.sdk.types.Field field = fieldBuilder.build();
        explicitFields.put(field.getId(), field);
      }
    }

    List<uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder> fs = builder.explicitFields;
    for (FieldBuilder explicitField : fs) {
      uk.gov.hmcts.ccd.sdk.types.Field field = explicitField.build();
      explicitFields.put(field.getId(), field);
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (String fieldId : explicitFields.keySet()) {
      uk.gov.hmcts.ccd.sdk.types.Field field = explicitFields.get(fieldId);
      Map<String, Object> fieldData = getField(caseType, fieldId);
      // Don't export inbuilt metadata fields.
      if (fieldId.matches("\\[.+\\]")) {
        continue;
      }
      result.add(fieldData);
      if (field.getLabel() != null) {
        fieldData.put("Label", field.getLabel());
      }
      String type = field.getType() == null ? "Label" : field.getType();
      fieldData.put("FieldType", type);
      if (field.getFieldTypeParameter() != null) {
        fieldData.put("FieldTypeParameter", field.getFieldTypeParameter());
      }
    }


    return result;
  }

  public static Map<String, Object> getField(String caseType, String id) {
    Map<String, Object> result = new Hashtable<>();
    result.put("LiveFrom", "01/01/2017");
    result.put("CaseTypeID", caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }
}
