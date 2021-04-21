package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.JsonUtils.OverwriteSpecific;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Label;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

class CaseFieldGenerator {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  public static <T, S, R extends HasRole> void generateCaseFields(
      File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> fields = toComplex(config.typeArg, config.builder.caseType);

    Map<String, Object> history = getField(config.builder.caseType, "caseHistory");
    history.put("Label", " ");
    history.put("FieldType", "CaseHistoryViewer");
    fields.add(history);

    fields.addAll(getExplicitFields(config.builder.caseType, config.events, config.builder));

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    List<Map<String, Object>> fields = Lists.newArrayList();

    for (Field field : ReflectionUtils.getAllFields(dataClass)) {

      CCD cf = field.getAnnotation(CCD.class);
      if (null != cf) {
        if (cf.ignore()) {
          continue;
        }
      }

      if (field.getAnnotation(JsonIgnore.class) != null) {
        continue;
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
        if (!Strings.isNullOrEmpty(cf.label())) {
          fieldInfo.put("Label", cf.label());
        }
        if (!Strings.isNullOrEmpty(cf.hint())) {
          fieldInfo.put("HintText", cf.hint());
        }
        if (!Strings.isNullOrEmpty(cf.regex())) {
          fieldInfo.put("RegularExpression", cf.regex());
        }
        if (cf.showSummaryContent()) {
          fieldInfo.put("ShowSummaryContentOption", "Y");
        }
        if (!Strings.isNullOrEmpty(cf.showCondition())) {
          fieldInfo.put("FieldShowCondition", cf.showCondition());
        }
      }

      if (cf != null && cf.typeOverride() != FieldType.Unspecified) {
        fieldInfo.put("FieldType", cf.typeOverride().toString());
        if (!Strings.isNullOrEmpty(cf.typeParameterOverride())) {
          fieldInfo.put("FieldTypeParameter", cf.typeParameterOverride());
        }
      } else {
        inferFieldType(dataClass, field, fieldInfo, cf);
      }

    }

    return fields;
  }

  private static void inferFieldType(Class dataClass, Field field, Map<String, Object> info,
      CCD cf) {
    String type = field.getType().getSimpleName();
    if (null != cf && !Strings.isNullOrEmpty(cf.typeParameterOverride())) {
      info.put("FieldTypeParameter", cf.typeParameterOverride());
    }
    if (Collection.class.isAssignableFrom(field.getType())) {
      type = "Collection";
      Class typeClass = getTypeClass(dataClass, field);
      ComplexType c = (ComplexType) typeClass.getAnnotation(ComplexType.class);
      if (null != c && !Strings.isNullOrEmpty(c.name())) {
        info.put("FieldTypeParameter", c.name());
      } else {
        info.put("FieldTypeParameter", typeClass.getSimpleName());
      }

      if (Set.class.isAssignableFrom(field.getType())) {
        if (typeClass.isEnum()) {
          type = "MultiSelectList";
        }
      }
    } else {
      ComplexType c = field.getType().getAnnotation(ComplexType.class);
      if (field.getType().isEnum() && (c == null || c.generate())) {
        type = "FixedRadioList";
        info.putIfAbsent("FieldTypeParameter", field.getType().getSimpleName());
      } else {
        switch (type) {
          case "String":
            type = "Text";
            if (cf != null && !Strings.isNullOrEmpty(cf.typeParameterOverride())) {
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

  private static Class getTypeClass(Class dataClass, Field field) {
    ParameterizedType parameterizedType = (ParameterizedType) TypeResolver
        .reify(field.getGenericType(), dataClass);

    if (parameterizedType.getActualTypeArguments()[0] instanceof ParameterizedType) {
      parameterizedType = (ParameterizedType) parameterizedType.getActualTypeArguments()[0];
    }

    return (Class) parameterizedType.getActualTypeArguments()[0];
  }

  private static <T, S, R extends HasRole> List<Map<String, Object>> getExplicitFields(
      String caseType, List<Event<T, R, S>> events, ConfigBuilderImpl<T, S, R> builder) {
    Map<String, uk.gov.hmcts.ccd.sdk.Field> explicitFields = Maps.newHashMap();
    for (Event event : events) {
      List<uk.gov.hmcts.ccd.sdk.Field.FieldBuilder> fc = event.getFields().build()
          .getExplicitFields();

      for (uk.gov.hmcts.ccd.sdk.Field.FieldBuilder fieldBuilder : fc) {
        uk.gov.hmcts.ccd.sdk.Field field = fieldBuilder.build();
        explicitFields.put(field.getId(), field);
      }
    }

    List<uk.gov.hmcts.ccd.sdk.Field.FieldBuilder> fs = builder.explicitFields;
    for (FieldBuilder explicitField : fs) {
      uk.gov.hmcts.ccd.sdk.Field field = explicitField.build();
      explicitFields.put(field.getId(), field);
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (String fieldId : explicitFields.keySet()) {
      uk.gov.hmcts.ccd.sdk.Field field = explicitFields.get(fieldId);
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
