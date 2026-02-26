package uk.gov.hmcts.ccd.sdk.api;

import java.beans.Introspector;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Utility for resolving property names from serializable getter method references.
 */
class LambdaUtils {

  private LambdaUtils() {
  }

  /**
   * Resolves the Java bean property name from a TypedPropertyGetter method reference.
   * Uses lambda serialization to inspect the implementing method name, then derives
   * the property name using standard JavaBean conventions.
   */
  static <T> String resolvePropertyName(TypedPropertyGetter<T, ?> getter) {
    SerializedLambda lambda = extractSerializedLambda(getter);
    String methodName = lambda.getImplMethodName();
    String implClassName = lambda.getImplClass().replace('/', '.');

    Class<?> implClass;
    try {
      implClass = ClassUtils.forName(implClassName, getter.getClass().getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Unable to resolve class: " + implClassName, e);
    }

    String propertyName = derivePropertyName(methodName);
    if (propertyName == null) {
      return methodName;
    }

    // Try to find the actual declared field name (handles casing differences)
    Field field = ReflectionUtils.findField(implClass, propertyName);
    if (field != null) {
      return field.getName();
    }

    // Try lowerCamel variant (e.g., AField -> aField)
    String lowerCamel = Introspector.decapitalize(propertyName);
    if (!lowerCamel.equals(propertyName)) {
      Field altField = ReflectionUtils.findField(implClass, lowerCamel);
      if (altField != null) {
        return altField.getName();
      }
    }

    return propertyName;
  }

  @SuppressWarnings("unchecked")
  static <T> Class<T> resolveDeclaringClass(TypedPropertyGetter<T, ?> getter) {
    SerializedLambda lambda = extractSerializedLambda(getter);
    String implClassName = lambda.getImplClass().replace('/', '.');
    try {
      return (Class<T>) ClassUtils.forName(implClassName, getter.getClass().getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Unable to resolve class: " + implClassName, e);
    }
  }

  private static SerializedLambda extractSerializedLambda(TypedPropertyGetter<?, ?> getter) {
    try {
      Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
      ReflectionUtils.makeAccessible(writeReplace);
      Object replacement = writeReplace.invoke(getter);
      if (replacement instanceof SerializedLambda serializedLambda) {
        return serializedLambda;
      }
      throw new IllegalArgumentException("Invalid lambda replacement");
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to introspect getter lambda", e);
    }
  }

  private static String derivePropertyName(String methodName) {
    if (methodName.startsWith("get") && methodName.length() > 3) {
      return Introspector.decapitalize(methodName.substring(3));
    }
    if (methodName.startsWith("is") && methodName.length() > 2) {
      return Introspector.decapitalize(methodName.substring(2));
    }
    return null;
  }
}
