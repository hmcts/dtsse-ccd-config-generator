package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.lang.annotation.Annotation;

public interface PropertyUtils {

  <T, A extends Annotation> A getAnnotationOfProperty(Class<T> entityType,
      TypedPropertyGetter<T, ?> propertyGetter, Class<A> annotationClass);

  <U, T> Class<T> getPropertyType(Class<U> c, TypedPropertyGetter<U, T> getter);

  <U> String getPropertyName(Class<U> c, TypedPropertyGetter<U, ?> getter);
}
