package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CaseFormat;
import de.cronn.reflection.util.TypedPropertyGetter;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

class PropertyUtils implements uk.gov.hmcts.ccd.sdk.api.PropertyUtils {

  @Override
  public <T, A extends Annotation> A getAnnotationOfProperty(Class<T> entityType,
      TypedPropertyGetter<T, ?> propertyGetter, Class<A> annotationClass) {
    return de.cronn.reflection.util.PropertyUtils
        .getAnnotationOfProperty(entityType, propertyGetter, annotationClass);
  }

  @Override
  public <U, T> Class<T> getPropertyType(Class<U> c, TypedPropertyGetter<U, T> getter) {
    PropertyDescriptor descriptor = de.cronn.reflection.util.PropertyUtils
        .getPropertyDescriptor(c, getter);
    return (Class<T>) descriptor.getPropertyType();
  }

  @Override
  public <U> String getPropertyName(Class<U> c, TypedPropertyGetter<U, ?> getter) {
    JsonGetter g = de.cronn.reflection.util.PropertyUtils
        .getAnnotationOfProperty(c, getter, JsonGetter.class);
    if (g != null) {
      return g.value();
    }
    JsonProperty j = de.cronn.reflection.util.PropertyUtils
        .getAnnotationOfProperty(c, getter, JsonProperty.class);
    if (j != null) {
      return j.value();
    }

    String name = de.cronn.reflection.util.PropertyUtils.getPropertyName(c, getter);
    if (name == null || name.isEmpty()) {
      return name;
    }

    Field declaredField = findField(c, name);
    if (declaredField != null) {
      return declaredField.getName();
    }

    String lowerCamel = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, name);
    if (!lowerCamel.equals(name)) {
      // For getters like getAField the bean descriptor returns `AField`; fall back to the
      // declared field name (`aField`)
      Field alternateField = findField(c, lowerCamel);
      if (alternateField != null) {
        return alternateField.getName();
      }
    }

    return name;
  }

  @Override
  public <U, T> Class<T> getListValueElementType(Class<U> c,
      TypedPropertyGetter<U, List<ListValue<T>>> getter) {
    PropertyDescriptor descriptor = de.cronn.reflection.util.PropertyUtils
        .getPropertyDescriptor(c, getter);
    if (descriptor == null || descriptor.getReadMethod() == null) {
      throw new IllegalArgumentException("Unable to resolve getter method for property");
    }

    Type genericReturnType = descriptor.getReadMethod().getGenericReturnType();
    if (!(genericReturnType instanceof ParameterizedType listType)) {
      throw new IllegalArgumentException("Property is not parameterized as a List");
    }

    Type listValueType = listType.getActualTypeArguments()[0];
    if (!(listValueType instanceof ParameterizedType parameterizedListValue)) {
      throw new IllegalArgumentException("Property is not parameterized as ListValue");
    }

    if (!ListValue.class.equals(parameterizedListValue.getRawType())) {
      throw new IllegalArgumentException("Property is not a ListValue collection");
    }

    Type elementType = parameterizedListValue.getActualTypeArguments()[0];
    if (elementType instanceof Class<?>) {
      return (Class<T>) elementType;
    }

    if (elementType instanceof ParameterizedType parameterizedElement
        && parameterizedElement.getRawType() instanceof Class<?>) {
      return (Class<T>) parameterizedElement.getRawType();
    }

    throw new IllegalArgumentException("Unable to determine ListValue element type");
  }

  private Field findField(Class<?> type, String name) {
    return ReflectionUtils.getAllFields(type, ReflectionUtils.withName(name))
        .stream()
        .findFirst()
        .orElse(null);
  }

}
