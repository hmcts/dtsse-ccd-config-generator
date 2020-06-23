package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.cronn.reflection.util.TypedPropertyGetter;
import org.apache.commons.lang3.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;

public class PropertyUtils implements uk.gov.hmcts.ccd.sdk.types.PropertyUtils {

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
    return j != null && StringUtils.isNotBlank(j.value()) ? j.value() : de.cronn.reflection.util.PropertyUtils
        .getPropertyName(c, getter);
  }
}
