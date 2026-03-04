package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.beans.Introspector;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Type-safe builder for CCD show condition expressions.
 */
public final class TypedShowCondition {

  private final String expression;

  private TypedShowCondition(String expression) {
    this.expression = expression;
  }

  public static <T> FieldCondition<T> when(TypedPropertyGetter<T, ?> getter) {
    return new FieldCondition<>(getter);
  }

  public TypedShowCondition and(TypedShowCondition other) {
    TypedShowCondition rhs = Objects.requireNonNull(other, "other must not be null");
    return new TypedShowCondition(this.expression + " AND " + rhs.expression);
  }

  public TypedShowCondition or(TypedShowCondition other) {
    TypedShowCondition rhs = Objects.requireNonNull(other, "other must not be null");
    return new TypedShowCondition(this.expression + " OR " + rhs.expression);
  }

  @Override
  public String toString() {
    return expression;
  }

  public static final class FieldCondition<T> {

    private final TypedPropertyGetter<T, ?> getter;
    private String fieldName;

    private FieldCondition(TypedPropertyGetter<T, ?> getter) {
      this.getter = Objects.requireNonNull(getter, "getter must not be null");
    }

    public TypedShowCondition is(Object value) {
      return new TypedShowCondition(resolveFieldName() + "=\"" + escape(formatValue(value)) + "\"");
    }

    public TypedShowCondition isAnyOf(Object... values) {
      if (values == null || values.length == 0) {
        throw new IllegalArgumentException("values must not be empty");
      }

      String expression = Arrays.stream(values)
          .map(v -> resolveFieldName() + "=\"" + escape(formatValue(v)) + "\"")
          .collect(Collectors.joining(" OR "));
      return new TypedShowCondition(expression);
    }

    public TypedShowCondition contains(Object value) {
      return new TypedShowCondition(resolveFieldName() + " CONTAINS \"" + escape(formatValue(value)) + "\"");
    }

    private String resolveFieldName() {
      if (fieldName == null) {
        fieldName = resolvePropertyName(getter);
      }
      return fieldName;
    }
  }

  private static String formatValue(Object value) {
    if (!(value instanceof Enum<?> enumValue)) {
      return String.valueOf(value);
    }

    try {
      Field enumField = enumValue.getClass().getField(enumValue.name());
      JsonProperty jsonProperty = enumField.getAnnotation(JsonProperty.class);
      if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
        return jsonProperty.value();
      }
    } catch (NoSuchFieldException ignored) {
      // Fall back to JsonValue or enum name.
    }

    for (Method method : enumValue.getClass().getMethods()) {
      if (method.isAnnotationPresent(JsonValue.class) && method.getParameterCount() == 0) {
        ReflectionUtils.makeAccessible(method);
        Object result = ReflectionUtils.invokeMethod(method, enumValue);
        return String.valueOf(result);
      }
    }

    return enumValue.name();
  }

  private static String escape(String value) {
    return value.replace("\"", "\\\"");
  }

  private static String resolvePropertyName(TypedPropertyGetter<?, ?> getter) {
    SerializedLambda lambda = extractSerializedLambda(getter);
    String methodName = lambda.getImplMethodName();
    Class<?> implementationClass = resolveLambdaClass(getter, lambda);
    Method getterMethod = resolveGetterMethod(implementationClass, methodName);
    String beanProperty = resolveBeanPropertyName(methodName);

    String jsonName = resolveJsonName(getterMethod, implementationClass, beanProperty);
    if (jsonName != null) {
      return jsonName;
    }

    Field field = findFieldByConventions(implementationClass, beanProperty);
    if (field != null) {
      return field.getName();
    }

    return beanProperty;
  }

  private static Method resolveGetterMethod(Class<?> implementationClass, String methodName) {
    Method method = ReflectionUtils.findMethod(implementationClass, methodName);
    if (method == null) {
      throw new IllegalArgumentException("Unable to resolve getter method '" + methodName + "'");
    }
    return method;
  }

  private static String resolveJsonName(
      Method getterMethod, Class<?> implementationClass, String beanProperty) {
    JsonGetter jsonGetter = getterMethod.getAnnotation(JsonGetter.class);
    if (jsonGetter != null && !jsonGetter.value().isEmpty()) {
      return jsonGetter.value();
    }

    JsonProperty getterJsonProperty = getterMethod.getAnnotation(JsonProperty.class);
    if (getterJsonProperty != null && !getterJsonProperty.value().isEmpty()) {
      return getterJsonProperty.value();
    }

    Field field = findFieldByConventions(implementationClass, beanProperty);
    if (field != null) {
      JsonProperty fieldJsonProperty = field.getAnnotation(JsonProperty.class);
      if (fieldJsonProperty != null && !fieldJsonProperty.value().isEmpty()) {
        return fieldJsonProperty.value();
      }
      return field.getName();
    }

    return null;
  }

  private static Field findFieldByConventions(Class<?> implementationClass, String beanProperty) {
    Field field = ReflectionUtils.findField(implementationClass, beanProperty);
    if (field != null) {
      return field;
    }

    String lowerCamel = toLowerCamel(beanProperty);
    if (!lowerCamel.equals(beanProperty)) {
      return ReflectionUtils.findField(implementationClass, lowerCamel);
    }

    return null;
  }

  private static String toLowerCamel(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }

    if (value.length() == 1) {
      return value.toLowerCase();
    }

    if (Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
      int index = 0;
      while (index < value.length() && Character.isUpperCase(value.charAt(index))) {
        index++;
      }

      if (index == value.length()) {
        return value.toLowerCase();
      }

      return value.substring(0, index - 1).toLowerCase() + value.substring(index - 1);
    }

    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
  }

  private static String resolveBeanPropertyName(String methodName) {
    if (methodName.startsWith("get") && methodName.length() > 3) {
      return Introspector.decapitalize(methodName.substring(3));
    }
    if (methodName.startsWith("is") && methodName.length() > 2) {
      return Introspector.decapitalize(methodName.substring(2));
    }
    if (methodName.startsWith("has") && methodName.length() > 3) {
      return Introspector.decapitalize(methodName.substring(3));
    }
    return methodName;
  }

  private static SerializedLambda extractSerializedLambda(TypedPropertyGetter<?, ?> getter) {
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

  private static Class<?> resolveLambdaClass(TypedPropertyGetter<?, ?> getter, SerializedLambda lambda) {
    String implementationClassName = lambda.getImplClass().replace('/', '.');
    try {
      return ClassUtils.forName(implementationClassName, getter.getClass().getClassLoader());
    } catch (ClassNotFoundException ex) {
      throw new IllegalArgumentException("Unable to resolve class for property getter: "
          + implementationClassName, ex);
    }
  }
}
