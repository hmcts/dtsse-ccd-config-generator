package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.base.Strings;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.jodah.typetools.TypeResolver;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

final class CaseFieldTypeResolver {

  private CaseFieldTypeResolver() {
  }

  static void applyFieldType(Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
    String resolvedType = resolveType(dataClass, field, target, annotation);
    target.put("FieldType", resolvedType);
  }

  private static String resolveType(Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
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

  private static String resolveCollectionType(Class<?> dataClass, Field field, Map<String, Object> target) {
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

  private static String resolveSimpleType(Field field,
                                          Map<String, Object> target,
                                          String inferredType,
                                          CCD annotation) {
    ComplexType complexType = field.getType().getAnnotation(ComplexType.class);
    if (field.getType().isEnum() && (complexType == null || complexType.generate())) {
      target.putIfAbsent("FieldTypeParameter", field.getType().getSimpleName());
      return "FixedRadioList";
    }
    switch (inferredType) {
      case "String":
        if (annotation != null && !Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
          return "FixedList";
        }
        return "Text";
      case "LocalDate":
        return "Date";
      case "LocalDateTime":
        return "DateTime";
      case "int":
      case "float":
      case "double":
      case "Integer":
      case "Float":
      case "Double":
      case "Long":
      case "long":
        return "Number";
      default:
        return inferredType;
    }
  }

  private static Class<?> resolveCollectionElementType(Class<?> dataClass, Field field) {
    ParameterizedType parameterizedType = (ParameterizedType) TypeResolver
        .reify(field.getGenericType(), dataClass);

    if (parameterizedType.getActualTypeArguments()[0] instanceof ParameterizedType) {
      parameterizedType = (ParameterizedType) parameterizedType.getActualTypeArguments()[0];
    }

    return (Class<?>) parameterizedType.getActualTypeArguments()[0];
  }
}
