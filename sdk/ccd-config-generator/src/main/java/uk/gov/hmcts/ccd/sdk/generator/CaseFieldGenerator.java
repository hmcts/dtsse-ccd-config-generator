package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getFieldId;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedField;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Label;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Component
class CaseFieldGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  @Override
  public void write(
      File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> fields = toComplex(
        config.getCaseClass(), config.getCaseType(), config.getSchemaProfile());

    if (config.isIncludeCaseHistory()) {
      Map<String, Object> history = getField(config.getCaseType(), "caseHistory");
      history.put("Label", config.getCaseHistoryLabel());
      history.put("FieldType", "CaseHistoryViewer");
      fields.add(history);
    }

    fields.addAll(getExplicitFields(config));

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    return toComplex(dataClass, caseTypeId, null);
  }

  public static List<Map<String, Object>> toComplex(
      Class dataClass, String caseTypeId, Class<?> schemaProfile) {
    return buildComplexFields(dataClass, caseTypeId, schemaProfile);
  }

  private static <T, S, R extends HasRole> List<Map<String, Object>> getExplicitFields(
      ResolvedCCDConfig<T, S, R> config) {
    Map<String, uk.gov.hmcts.ccd.sdk.api.Field> explicitFields = Maps.newHashMap();
    for (Event event : config.getEvents().values()) {
      List<uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder> fc = event.getFields()
          .getExplicitFields();

      for (uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder fieldBuilder : fc) {
        uk.gov.hmcts.ccd.sdk.api.Field field = fieldBuilder.build();
        explicitFields.put(field.getId(), field);
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (String fieldId : explicitFields.keySet()) {
      Optional<JsonUnwrapped> unwrapped = isUnwrappedField(config.getCaseClass(), fieldId);
      // Don't export inbuilt metadata fields. Ignore unwrapped complex types
      if (fieldId.matches("\\[.+\\]") || unwrapped.isPresent()) {
        continue;
      }

      final uk.gov.hmcts.ccd.sdk.api.Field field = explicitFields.get(fieldId);
      Map<String, Object> fieldData = getField(config.getCaseType(), fieldId);
      result.add(fieldData);

      Optional<Field> caseField = findCaseField(
          config.getCaseClass(), fieldId, config.getSchemaProfile());
      caseField.ifPresent(candidate ->
          populateFieldMetadata(
              fieldData, config.getCaseClass(), candidate, config.getSchemaProfile()));
      JsonUtils.ensureDefaultLabel(fieldData);

      if (!Strings.isNullOrEmpty(field.getLabel())) {
        fieldData.put("Label", field.getLabel());
      }

      if (field.getType() != null) {
        fieldData.put("FieldType", field.getType());
      } else if (caseField.isEmpty()) {
        fieldData.put("FieldType", "Label");
      }

      if (field.getFieldTypeParameter() != null) {
        fieldData.put("FieldTypeParameter", field.getFieldTypeParameter());
      }
    }


    return result;
  }

  private static List<Map<String, Object>> buildComplexFields(
      Class<?> dataClass, String caseTypeId, Class<?> schemaProfile) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    appendFields(fields, dataClass, caseTypeId, "", schemaProfile);
    return fields;
  }

  private static void appendFields(
      List<Map<String, Object>> fields,
      Class<?> dataClass,
      String caseTypeId,
      String idPrefix,
      Class<?> schemaProfile) {
    for (Field field : getCaseFields(dataClass, schemaProfile)) {
      appendField(fields, caseTypeId, dataClass, field, idPrefix, schemaProfile);
    }
  }

  private static void appendField(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Class<?> ownerClass,
      Field field,
      String idPrefix,
      Class<?> schemaProfile) {
    JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
    if (unwrapped != null) {
      appendUnwrapped(fields, caseTypeId, field, idPrefix, unwrapped, schemaProfile);
      return;
    }

    String id = getFieldId(field, idPrefix, schemaProfile);
    Label label = field.getAnnotation(Label.class);
    JsonUtils.applyLabelAnnotation(fields, caseTypeId, label);

    Map<String, Object> fieldInfo = getField(caseTypeId, id);
    fields.add(fieldInfo);

    populateFieldMetadata(fieldInfo, ownerClass, field, schemaProfile);
  }

  private static void appendUnwrapped(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Field field,
      String currentPrefix,
      JsonUnwrapped unwrapped,
      Class<?> schemaProfile) {
    String prefix = currentPrefix.isEmpty()
        ? unwrapped.prefix()
        : currentPrefix.concat(StringUtils.capitalize(unwrapped.prefix()));
    appendFields(fields, field.getType(), caseTypeId, prefix, schemaProfile);
  }

  private static void populateFieldMetadata(
      Map<String, Object> target, Class<?> ownerClass, Field field, Class<?> schemaProfile) {
    CCD annotation = uk.gov.hmcts.ccd.sdk.FieldUtils.getCCD(field, schemaProfile).orElse(null);
    JsonUtils.applyCcdAnnotation(target, annotation);
    JsonUtils.ensureDefaultLabel(target);

    if (annotation != null && annotation.typeOverride() != FieldType.Unspecified) {
      target.put("FieldType", annotation.typeOverride().toString());
      if (!Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
        target.put("FieldTypeParameter", annotation.typeParameterOverride());
      }
      return;
    }

    applyFieldType(ownerClass, field, target, annotation);
  }

  private static void applyFieldType(
      Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
    String resolvedType = resolveFieldType(dataClass, field, target, annotation);
    target.put("FieldType", resolvedType);
  }

  private static String resolveFieldType(
      Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
    String type = field.getType().getSimpleName();

    if (annotation != null && !Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
      target.put("FieldTypeParameter", annotation.typeParameterOverride());
    }

    if (Collection.class.isAssignableFrom(field.getType())) {
      type = resolveCollectionType(dataClass, field, target);
    } else {
      type = resolveSimpleType(field, target, type, annotation);
    }

    ComplexType complexType = field.getType().getAnnotation(ComplexType.class);
    if (complexType != null && !Strings.isNullOrEmpty(complexType.name())) {
      type = complexType.name();
    }

    return type;
  }

  private static String resolveCollectionType(
      Class<?> dataClass, Field field, Map<String, Object> target) {
    String type = "Collection";
    Class<?> elementClass = resolveCollectionElementType(dataClass, field);
    ComplexType complexType = elementClass.getAnnotation(ComplexType.class);
    if (complexType != null && !Strings.isNullOrEmpty(complexType.name())) {
      target.put("FieldTypeParameter", complexType.name());
    } else {
      target.put("FieldTypeParameter", elementClass.getSimpleName());
    }

    if (Set.class.isAssignableFrom(field.getType()) && elementClass.isEnum()) {
      type = "MultiSelectList";
    }
    return type;
  }

  private static String resolveSimpleType(
      Field field,
      Map<String, Object> target,
      String inferredType,
      CCD annotation) {
    ComplexType complexType = field.getType().getAnnotation(ComplexType.class);
    if (field.getType().isEnum() && (complexType == null || complexType.generate())) {
      target.putIfAbsent("FieldTypeParameter", field.getType().getSimpleName());
      return "FixedRadioList";
    }
    return switch (inferredType) {
      case "String" -> {
        if (annotation != null && !Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
          yield "FixedList";
        }
        yield "Text";
      }
      case "LocalDate" -> "Date";
      case "LocalDateTime" -> "DateTime";
      case "int", "float", "double", "Integer", "Float", "Double", "Long", "long" -> "Number";
      default -> inferredType;
    };
  }

  private static Class<?> resolveCollectionElementType(Class<?> dataClass, Field field) {
    ResolvableType fieldType = ResolvableType.forField(field, dataClass);
    ResolvableType elementType = fieldType.getGeneric(0);
    if (elementType.hasGenerics()) {
      elementType = elementType.getGeneric(0);
    }
    Class<?> resolved = elementType.resolve();
    if (resolved == null) {
      throw new IllegalStateException("Unable to resolve element type for %s on %s"
          .formatted(field.getName(), dataClass.getName()));
    }
    return uk.gov.hmcts.ccd.sdk.FieldUtils.unwrapCollectionValueType(resolved);
  }

  public static Map<String, Object> getField(String caseType, String id) {
    Map<String, Object> result = JsonUtils.caseRow(caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }

  private static Optional<Field> findCaseField(
      Class<?> caseClass, String fieldId, Class<?> schemaProfile) {
    return getCaseFields(caseClass, schemaProfile)
        .stream()
        .filter(candidate -> getFieldId(candidate, null, schemaProfile).equals(fieldId))
        .findFirst();
  }

}
