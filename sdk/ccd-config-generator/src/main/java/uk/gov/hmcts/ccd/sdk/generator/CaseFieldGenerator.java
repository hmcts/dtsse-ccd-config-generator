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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.DtoFieldPrefix;
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
  private static final int CCD_FIELD_ID_LIMIT = 70;

  @Override
  public void write(
      File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> fields = toComplex(config.getCaseClass(), config.getCaseType());
    List<Map<String, Object>> explicitFields = getExplicitFields(config);

    Map<String, Object> history = getField(config.getCaseType(), "caseHistory");
    history.put("Label", " ");
    history.put("FieldType", "CaseHistoryViewer");
    fields.add(history);

    Map<String, String> prefixToEventId = Maps.newHashMap();
    Map<String, String> reservedFieldIds = getReservedFieldIds(fields, explicitFields);
    Map<String, String> generatedFieldIds = Maps.newHashMap();
    for (Event event : config.getEvents().values()) {
      if (event.isDtoEvent()) {
        DtoFieldPrefix.validate(config.getCaseType(), event.getId(), event.getFieldPrefix());
        String prefix = event.getEventFieldPrefix();
        validateDtoPrefix(config.getCaseType(), event.getId(), prefix, prefixToEventId);
        validateDtoClass(config.getCaseType(), event.getDtoClass(), event.getId(), event.getFieldPrefix());
        validateGeneratedFieldIds(config.getCaseType(), event.getDtoClass(), event.getId(), event.getFieldPrefix());
        validateGeneratedDtoFieldIdCollisions(event.getDtoClass(), config.getCaseType(), event.getId(), prefix,
            generatedFieldIds, reservedFieldIds);
        appendDtoFields(fields, event.getDtoClass(), config.getCaseType(), prefix);
      }
    }

    fields.addAll(explicitFields);

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    return buildComplexFields(dataClass, caseTypeId);
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

      Optional<Field> caseField = findCaseField(config.getCaseClass(), fieldId);
      caseField.ifPresent(candidate ->
          populateFieldMetadata(fieldData, config.getCaseClass(), candidate));
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
      Class<?> dataClass, String caseTypeId) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    appendFields(fields, dataClass, caseTypeId, "");
    return fields;
  }

  private static void appendFields(
      List<Map<String, Object>> fields,
      Class<?> dataClass,
      String caseTypeId,
      String idPrefix) {
    for (Field field : getCaseFields(dataClass)) {
      appendField(fields, caseTypeId, dataClass, field, idPrefix);
    }
  }

  private static void appendDtoFields(
      List<Map<String, Object>> fields,
      Class<?> dtoClass,
      String caseTypeId,
      String fieldPrefix) {
    for (Field field : getCaseFields(dtoClass)) {
      appendDtoField(fields, caseTypeId, dtoClass, field, fieldPrefix);
    }
  }

  private static void appendField(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Class<?> ownerClass,
      Field field,
      String idPrefix) {
    JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
    if (unwrapped != null) {
      appendUnwrapped(fields, caseTypeId, field, idPrefix, unwrapped);
      return;
    }

    String id = getFieldId(field, idPrefix);
    Label label = field.getAnnotation(Label.class);
    JsonUtils.applyLabelAnnotation(fields, caseTypeId, label);

    Map<String, Object> fieldInfo = getField(caseTypeId, id);
    fields.add(fieldInfo);

    populateFieldMetadata(fieldInfo, ownerClass, field);
  }

  private static void appendDtoField(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Class<?> ownerClass,
      Field field,
      String fieldPrefix) {
    String id = DtoFieldPrefix.toFieldId(fieldPrefix, getFieldId(field));
    Label label = field.getAnnotation(Label.class);
    JsonUtils.applyLabelAnnotation(fields, caseTypeId, label);

    Map<String, Object> fieldInfo = getField(caseTypeId, id);
    fields.add(fieldInfo);

    populateFieldMetadata(fieldInfo, ownerClass, field);
  }

  private static void appendUnwrapped(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Field field,
      String currentPrefix,
      JsonUnwrapped unwrapped) {
    String prefix = currentPrefix.isEmpty()
        ? unwrapped.prefix()
        : currentPrefix.concat(StringUtils.capitalize(unwrapped.prefix()));
    appendFields(fields, field.getType(), caseTypeId, prefix);
  }

  private static void populateFieldMetadata(
      Map<String, Object> target, Class<?> ownerClass, Field field) {
    CCD annotation = field.getAnnotation(CCD.class);
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
    return resolved;
  }

  public static Map<String, Object> getField(String caseType, String id) {
    Map<String, Object> result = JsonUtils.caseRow(caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }

  private static Optional<Field> findCaseField(Class<?> caseClass, String fieldId) {
    return getCaseFields(caseClass)
        .stream()
        .filter(candidate -> getFieldId(candidate).equals(fieldId))
        .findFirst();
  }

  static void validateDtoClass(String caseTypeId, Class<?> dtoClass, String eventId, String fieldPrefix) {
    for (Field field : getCaseFields(dtoClass)) {
      if (field.getAnnotation(com.fasterxml.jackson.annotation.JsonUnwrapped.class) != null) {
        throw new IllegalArgumentException(
            ("DTO class %s for case type '%s', event '%s', field prefix '%s' must be flat: "
                + "field '%s' uses @JsonUnwrapped")
                .formatted(dtoClass.getSimpleName(), caseTypeId, eventId, fieldPrefix, field.getName()));
      }
      validateSupportedDtoFieldType(caseTypeId, dtoClass, eventId, fieldPrefix, field);
    }
  }

  private static void validateGeneratedFieldIds(
      String caseTypeId, Class<?> dtoClass, String eventId, String fieldPrefix) {
    for (Field field : getCaseFields(dtoClass)) {
      String fieldId = DtoFieldPrefix.toFieldId(fieldPrefix, getFieldId(field));
      if (fieldId.length() > CCD_FIELD_ID_LIMIT) {
        throw new IllegalArgumentException(
            ("Generated CCD field ID exceeds %s characters for case type '%s', event '%s', field prefix '%s', "
                + "field '%s': '%s'")
                .formatted(CCD_FIELD_ID_LIMIT, caseTypeId, eventId, fieldPrefix, field.getName(), fieldId));
      }
    }
  }

  private static void validateDtoPrefix(
      String caseTypeId,
      String eventId,
      String fieldPrefix,
      Map<String, String> prefixToEventId) {
    String existingEventId = prefixToEventId.get(fieldPrefix);
    if (existingEventId != null && !existingEventId.equals(eventId)) {
      throw new IllegalStateException(
          ("DTO field prefix collision for case type '%s': events '%s' and '%s' "
              + "both use prefix '%s'.")
              .formatted(caseTypeId, existingEventId, eventId, fieldPrefix));
    }
    prefixToEventId.put(fieldPrefix, eventId);
  }

  private static void validateGeneratedDtoFieldIdCollisions(
      Class<?> dtoClass,
      String caseTypeId,
      String eventId,
      String fieldPrefix,
      Map<String, String> generatedFieldIds,
      Map<String, String> reservedFieldIds) {
    for (Field field : getCaseFields(dtoClass)) {
      String fieldId = DtoFieldPrefix.toFieldId(fieldPrefix, getFieldId(field));
      String reservedOrigin = reservedFieldIds.get(fieldId);
      if (reservedOrigin != null) {
        throw new IllegalStateException(
            ("DTO generated field ID collision for case type '%s': '%s.%s' maps to '%s', "
                + "which is already used by %s.")
                .formatted(caseTypeId, eventId, field.getName(), fieldId, reservedOrigin));
      }
      String currentOrigin = "%s.%s".formatted(eventId, field.getName());
      String existingOrigin = generatedFieldIds.putIfAbsent(fieldId, currentOrigin);
      if (existingOrigin != null) {
        throw new IllegalStateException(
            ("DTO generated field ID collision for case type '%s': '%s' and '%s' both map to '%s'.")
                .formatted(caseTypeId, existingOrigin, currentOrigin, fieldId));
      }
    }
  }

  private static Map<String, String> getReservedFieldIds(
      List<Map<String, Object>> caseFields,
      List<Map<String, Object>> explicitFields) {
    Map<String, String> reservedFieldIds = Maps.newHashMap();
    for (Map<String, Object> field : caseFields) {
      if (field.containsKey("ID")) {
        reservedFieldIds.put(field.get("ID").toString(), "case field '%s'".formatted(field.get("ID")));
      }
    }
    for (Map<String, Object> field : explicitFields) {
      if (field.containsKey("ID")) {
        reservedFieldIds.put(field.get("ID").toString(), "explicit field '%s'".formatted(field.get("ID")));
      }
    }
    return reservedFieldIds;
  }

  private static void validateSupportedDtoFieldType(
      String caseTypeId, Class<?> dtoClass, String eventId, String fieldPrefix, Field field) {
    Class<?> type = field.getType();
    if (Map.class.isAssignableFrom(type)) {
      throw unsupportedType(caseTypeId, dtoClass, eventId, fieldPrefix, field, "Map is not supported");
    }

    if (Collection.class.isAssignableFrom(type)) {
      validateCollectionFieldType(caseTypeId, dtoClass, eventId, fieldPrefix, field);
      return;
    }

    if (!isAllowedScalarDtoFieldType(type)) {
      throw unsupportedType(caseTypeId, dtoClass, eventId, fieldPrefix, field,
          "nested/custom types are not supported");
    }
  }

  private static void validateCollectionFieldType(
      String caseTypeId, Class<?> dtoClass, String eventId, String fieldPrefix, Field field) {
    Deque<ResolvableType> elementTypes = new ArrayDeque<>();
    elementTypes.add(ResolvableType.forField(field, dtoClass).getGeneric(0));

    while (!elementTypes.isEmpty()) {
      ResolvableType current = elementTypes.removeFirst();
      Class<?> resolved = current.resolve();
      if (resolved == null) {
        throw new IllegalArgumentException(
            "Unable to resolve DTO collection element type for case type '%s', event '%s', field prefix '%s', "
                + "field '%s' on %s"
                .formatted(caseTypeId, eventId, fieldPrefix, field.getName(), dtoClass.getName()));
      }

      if (Map.class.isAssignableFrom(resolved)) {
        throw unsupportedType(
            caseTypeId,
            dtoClass,
            eventId,
            fieldPrefix,
            field,
            "collection element type '%s' is not supported".formatted(resolved.getSimpleName())
        );
      }

      if (Collection.class.isAssignableFrom(resolved)) {
        if (!current.hasGenerics()) {
          throw unsupportedType(caseTypeId, dtoClass, eventId, fieldPrefix, field,
              "nested collections must resolve to supported element types");
        }
        elementTypes.addFirst(current.getGeneric(0));
        continue;
      }

      if (!isAllowedScalarDtoFieldType(resolved)) {
        throw unsupportedType(caseTypeId, dtoClass, eventId, fieldPrefix, field,
            "collection element type '%s' is not supported".formatted(resolved.getSimpleName()));
      }
    }
  }

  private static IllegalArgumentException unsupportedType(
      String caseTypeId, Class<?> dtoClass, String eventId, String fieldPrefix, Field field, String reason) {
    return new IllegalArgumentException(
        "DTO class %s for case type '%s', event '%s', field prefix '%s' has unsupported field '%s' of type '%s': %s"
            .formatted(dtoClass.getSimpleName(), caseTypeId, eventId, fieldPrefix,
                field.getName(), field.getGenericType().getTypeName(), reason));
  }

  private static boolean isAllowedScalarDtoFieldType(Class<?> type) {
    if (type.isPrimitive()) {
      return true;
    }
    if (type == String.class || type == LocalDate.class || type == LocalDateTime.class) {
      return true;
    }
    if (type == Integer.class || type == Long.class || type == Float.class
        || type == Double.class || type == Boolean.class) {
      return true;
    }
    if (type.isEnum()) {
      return true;
    }
    if (type.getAnnotation(ComplexType.class) != null) {
      return true;
    }
    return false;
  }

}
