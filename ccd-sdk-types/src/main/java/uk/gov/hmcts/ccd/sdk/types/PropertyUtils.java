package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.lang.annotation.Annotation;

public interface PropertyUtils {

  <T, A extends Annotation> A getAnnotationOfProperty(Class<T> entityType,
      TypedPropertyGetter<T, ?> propertyGetter, Class<A> annotationClass);

  <U> Class<U> getPropertyType(Class<U> c, TypedPropertyGetter<U, ?> getter);

  <U> String getPropertyName(Class<U> c, TypedPropertyGetter<U, ?> getter);
}
