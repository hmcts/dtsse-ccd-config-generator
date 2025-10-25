package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CaseFormat;
import de.cronn.reflection.util.TypedPropertyGetter;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import org.reflections.ReflectionUtils;

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

  private Field findField(Class<?> type, String name) {
    return ReflectionUtils.getAllFields(type, ReflectionUtils.withName(name))
        .stream()
        .findFirst()
        .orElse(null);
  }

}
