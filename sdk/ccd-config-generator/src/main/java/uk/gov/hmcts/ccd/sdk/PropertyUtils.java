package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CaseFormat;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.TypedPropertyGetter;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

class PropertyUtils implements uk.gov.hmcts.ccd.sdk.api.PropertyUtils {

  @Override
  public <T, A extends Annotation> A getAnnotationOfProperty(Class<T> entityType,
      TypedPropertyGetter<T, ?> propertyGetter, Class<A> annotationClass) {
    PropertyDescriptor descriptor = resolvePropertyDescriptor(entityType, propertyGetter);
    if (descriptor != null && descriptor.getReadMethod() != null) {
      A annotation = AnnotatedElementUtils.findMergedAnnotation(descriptor.getReadMethod(), annotationClass);
      if (annotation != null) {
        return annotation;
      }
    }

    String propertyName = resolvePropertyName(entityType, propertyGetter, descriptor);
    Field field = findField(entityType, propertyName);
    if (field != null) {
      return AnnotatedElementUtils.findMergedAnnotation(field, annotationClass);
    }

    return null;
  }

  @Override
  public <U, T> Class<T> getPropertyType(Class<U> c, TypedPropertyGetter<U, T> getter) {
    PropertyDescriptor descriptor = resolveRequiredDescriptor(c, getter);
    return (Class<T>) descriptor.getPropertyType();
  }

  @Override
  public <U> String getPropertyName(Class<U> c, TypedPropertyGetter<U, ?> getter) {
    JsonGetter g = getAnnotationOfProperty(c, getter, JsonGetter.class);
    if (g != null) {
      return g.value();
    }
    JsonProperty j = getAnnotationOfProperty(c, getter, JsonProperty.class);
    if (j != null) {
      return j.value();
    }

    String name = resolvePropertyName(c, getter, null);
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
    PropertyDescriptor descriptor = resolveRequiredDescriptor(c, getter);
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
    if (name == null || name.isEmpty()) {
      return null;
    }
    return ReflectionUtils.findField(type, name);
  }

  private <T> PropertyDescriptor resolveRequiredDescriptor(Class<T> type, TypedPropertyGetter<T, ?> getter) {
    PropertyDescriptor descriptor = resolvePropertyDescriptor(type, getter);
    if (descriptor == null) {
      String propertyName = resolvePropertyName(type, getter, null);
      throw new IllegalArgumentException("Unable to resolve property descriptor for property '"
          + propertyName + "' on " + type.getName());
    }
    return descriptor;
  }

  private <T> PropertyDescriptor resolvePropertyDescriptor(Class<T> type, TypedPropertyGetter<T, ?> getter) {
    Method getterMethod = resolveGetterMethod(type, getter);
    String propertyName = derivePropertyName(getterMethod);

    PropertyDescriptor descriptor = propertyName != null
        ? BeanUtils.getPropertyDescriptor(type, propertyName)
        : null;

    if (descriptor == null) {
      for (PropertyDescriptor candidate : BeanUtils.getPropertyDescriptors(type)) {
        if (getterMethod.equals(candidate.getReadMethod())) {
          descriptor = candidate;
          break;
        }
      }
    }

    return descriptor;
  }

  private <T> String resolvePropertyName(Class<T> type, TypedPropertyGetter<T, ?> getter,
      PropertyDescriptor descriptor) {
    if (descriptor == null) {
      descriptor = resolvePropertyDescriptor(type, getter);
    }
    if (descriptor != null) {
      return descriptor.getName();
    }
    Method getterMethod = resolveGetterMethod(type, getter);
    String derivedName = derivePropertyName(getterMethod);
    return derivedName != null ? derivedName : getterMethod.getName();
  }

  private <T> Method resolveGetterMethod(Class<T> type, TypedPropertyGetter<T, ?> getter) {
    SerializedLambda lambda = extractSerializedLambda(getter);
    Class<?> implementationClass = resolveLambdaClass(type, lambda);
    String methodName = lambda.getImplMethodName();

    Method method = ReflectionUtils.findMethod(implementationClass, methodName);
    if (method == null && !implementationClass.equals(type)) {
      method = ReflectionUtils.findMethod(type, methodName);
    }

    if (method == null) {
      throw new IllegalArgumentException("Unable to resolve getter method '" + methodName
          + "' for " + type.getName());
    }

    return method;
  }

  private SerializedLambda extractSerializedLambda(TypedPropertyGetter<?, ?> getter) {
    try {
      Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
      ReflectionUtils.makeAccessible(writeReplace);
      Object replacement = writeReplace.invoke(getter);
      if (replacement instanceof SerializedLambda serializedLambda) {
        return serializedLambda;
      }
      throw new IllegalArgumentException("Invalid lambda replacement for property getter");
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
      throw new IllegalArgumentException("Unable to introspect property getter lambda", ex);
    }
  }

  private Class<?> resolveLambdaClass(Class<?> rootType, SerializedLambda lambda) {
    String implementationClassName = lambda.getImplClass().replace('/', '.');
    try {
      return ClassUtils.forName(implementationClassName, rootType.getClassLoader());
    } catch (ClassNotFoundException ex) {
      throw new IllegalArgumentException("Unable to resolve class for property getter: "
          + implementationClassName, ex);
    }
  }

  private String derivePropertyName(Method method) {
    if (method == null) {
      return null;
    }

    String methodName = method.getName();
    if (methodName.startsWith("get") && methodName.length() > 3) {
      return decapitalize(methodName.substring(3));
    }

    if (methodName.startsWith("is") && methodName.length() > 2) {
      return decapitalize(methodName.substring(2));
    }

    if (methodName.startsWith("has") && methodName.length() > 3) {
      return decapitalize(methodName.substring(3));
    }

    return methodName;
  }

  private String decapitalize(String name) {
    return name == null ? null : Introspector.decapitalize(name);
  }
}
