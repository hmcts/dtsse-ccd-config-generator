package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
public final class ShowCondition {

  public static final ShowCondition NEVER = stateIs("NEVER_SHOW");
  public static final String NEVER_SHOW = NEVER.toString();

  private final String expression;
  private final Precedence precedence;

  private ShowCondition(String expression) {
    this(expression, Precedence.ATOM);
  }

  private ShowCondition(String expression, Precedence precedence) {
    this.expression = expression;
    this.precedence = precedence;
  }

  public static <T> FieldCondition<T> when(TypedPropertyGetter<T, ?> getter) {
    return new FieldCondition<>(getter);
  }

  public static <T, U> NamedFieldCondition when(TypedPropertyGetter<T, U> parentGetter,
      TypedPropertyGetter<U, ?> childGetter) {
    PropertyReference parent = resolvePropertyReference(parentGetter);
    PropertyReference child = resolvePropertyReference(childGetter);
    return new NamedFieldCondition(resolveNestedPropertyName(parent, child));
  }

  public static <T, U, V> NamedFieldCondition when(TypedPropertyGetter<T, U> parentGetter,
      TypedPropertyGetter<U, V> childGetter, TypedPropertyGetter<V, ?> grandChildGetter) {
    PropertyReference parent = resolvePropertyReference(parentGetter);
    PropertyReference child = resolvePropertyReference(childGetter);
    PropertyReference grandChild = resolvePropertyReference(grandChildGetter);
    return new NamedFieldCondition(resolveNestedPropertyName(parent, child, grandChild));
  }

  public static <T> FieldRef ref(TypedPropertyGetter<T, ?> getter) {
    return new FieldRef(resolvePropertyName(getter));
  }

  public static <T, U> FieldRef ref(TypedPropertyGetter<T, U> parentGetter, TypedPropertyGetter<U, ?> childGetter) {
    PropertyReference parent = resolvePropertyReference(parentGetter);
    PropertyReference child = resolvePropertyReference(childGetter);
    return new FieldRef(resolveNestedPropertyName(parent, child));
  }

  public static <T, U, V> FieldRef ref(TypedPropertyGetter<T, U> parentGetter,
      TypedPropertyGetter<U, V> childGetter, TypedPropertyGetter<V, ?> grandChildGetter) {
    PropertyReference parent = resolvePropertyReference(parentGetter);
    PropertyReference child = resolvePropertyReference(childGetter);
    PropertyReference grandChild = resolvePropertyReference(grandChildGetter);
    return new FieldRef(resolveNestedPropertyName(parent, child, grandChild));
  }

  public static FieldRef ref(String fieldId) {
    return new FieldRef(fieldId);
  }

  public static NamedFieldCondition field(String fieldId) {
    return new NamedFieldCondition(fieldId);
  }

  public static ShowCondition stateIs(Object value) {
    return new ShowCondition("[STATE]=\"" + escape(formatValue(value)) + "\"");
  }

  public static ShowCondition stateIsNot(Object value) {
    return new ShowCondition("[STATE]!=\"" + escape(formatValue(value)) + "\"");
  }

  public static ShowCondition is(NamedFieldCondition field, Object value) {
    return requireField(field).is(value);
  }

  public static ShowCondition isNot(NamedFieldCondition field, Object value) {
    return requireField(field).isNot(value);
  }

  public static ShowCondition isAnyOf(NamedFieldCondition field, Object... values) {
    return requireField(field).isAnyOf(values);
  }

  public static ShowCondition contains(NamedFieldCondition field, Object value) {
    return requireField(field).contains(value);
  }

  public static ShowCondition allOf(ShowCondition... conditions) {
    return combine(Precedence.AND, conditions);
  }

  public static ShowCondition anyOf(ShowCondition... conditions) {
    return combine(Precedence.OR, conditions);
  }

  public ShowCondition and(ShowCondition other) {
    ShowCondition rhs = Objects.requireNonNull(other, "other must not be null");
    return new ShowCondition(
        renderFor(Precedence.AND) + " AND " + rhs.renderFor(Precedence.AND),
        Precedence.AND
    );
  }

  public ShowCondition or(ShowCondition other) {
    ShowCondition rhs = Objects.requireNonNull(other, "other must not be null");
    return new ShowCondition(
        renderFor(Precedence.OR) + " OR " + rhs.renderFor(Precedence.OR),
        Precedence.OR
    );
  }

  @Override
  public String toString() {
    return expression;
  }

  private static NamedFieldCondition requireField(NamedFieldCondition field) {
    return Objects.requireNonNull(field, "field must not be null");
  }

  private static ShowCondition combine(Precedence precedence, ShowCondition... conditions) {
    if (conditions == null || conditions.length == 0) {
      throw new IllegalArgumentException("conditions must not be empty");
    }

    ShowCondition result = Objects.requireNonNull(conditions[0], "conditions must not contain null");
    for (int i = 1; i < conditions.length; i++) {
      ShowCondition next = Objects.requireNonNull(conditions[i], "conditions must not contain null");
      result = precedence == Precedence.AND ? result.and(next) : result.or(next);
    }
    return result;
  }

  private abstract static class BaseFieldCondition {

    protected abstract String fieldId();

    public ShowCondition is(Object value) {
      return new ShowCondition(fieldId() + "=\"" + escape(formatValue(value)) + "\"");
    }

    public ShowCondition isNot(Object value) {
      return new ShowCondition(fieldId() + "!=\"" + escape(formatValue(value)) + "\"");
    }

    public ShowCondition isAnyOf(Object... values) {
      if (values == null || values.length == 0) {
        throw new IllegalArgumentException("values must not be empty");
      }

      String expression = Arrays.stream(values)
          .map(v -> fieldId() + "=\"" + escape(formatValue(v)) + "\"")
          .collect(Collectors.joining(" OR "));
      return new ShowCondition(expression, Precedence.OR);
    }

    public ShowCondition contains(Object value) {
      return new ShowCondition(fieldId() + "CONTAINS\"" + escape(formatValue(value)) + "\"");
    }
  }

  public static final class FieldCondition<T> extends BaseFieldCondition {

    private final TypedPropertyGetter<T, ?> getter;
    private String fieldName;

    private FieldCondition(TypedPropertyGetter<T, ?> getter) {
      this.getter = Objects.requireNonNull(getter, "getter must not be null");
    }

    @Override
    protected String fieldId() {
      if (fieldName == null) {
        fieldName = resolvePropertyName(getter);
      }
      return fieldName;
    }
  }

  public static class NamedFieldCondition extends BaseFieldCondition {

    private final String fieldId;

    protected NamedFieldCondition(String fieldId) {
      if (fieldId == null || fieldId.isBlank()) {
        throw new IllegalArgumentException("fieldId must not be blank");
      }
      this.fieldId = fieldId;
    }

    @Override
    protected String fieldId() {
      return fieldId;
    }
  }

  public static final class FieldRef extends NamedFieldCondition {
    private FieldRef(String fieldId) {
      super(fieldId);
    }
  }

  private static String formatValue(Object value) {
    if (!(value instanceof Enum<?> enumValue)) {
      return String.valueOf(value);
    }

    try {
      Field enumField = enumValue.getClass().getField(enumValue.name());
      JsonProperty jsonProperty = enumField.getAnnotation(JsonProperty.class);
      // Keep explicit constant-level aliases stable even if the enum also exposes a JsonValue method.
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
    return resolvePropertyReference(getter).fieldName();
  }

  private static PropertyReference resolvePropertyReference(TypedPropertyGetter<?, ?> getter) {
    SerializedLambda lambda = extractSerializedLambda(getter);
    String methodName = lambda.getImplMethodName();
    Class<?> implementationClass = resolveLambdaClass(getter, lambda);
    Method getterMethod = resolveGetterMethod(implementationClass, methodName);
    String beanProperty = resolveBeanPropertyName(methodName);

    String jsonName = resolveJsonName(getterMethod, implementationClass, beanProperty);
    if (jsonName != null) {
      return new PropertyReference(jsonName, getterMethod, implementationClass, beanProperty);
    }

    Field field = findFieldByConventions(implementationClass, beanProperty);
    if (field != null) {
      return new PropertyReference(field.getName(), getterMethod, implementationClass, beanProperty);
    }

    return new PropertyReference(beanProperty, getterMethod, implementationClass, beanProperty);
  }

  private static String resolveNestedPropertyName(PropertyReference parent, PropertyReference child) {
    JsonUnwrapped unwrapped = findJsonUnwrapped(parent);
    if (unwrapped != null) {
      return composeUnwrappedPath(unwrapped.prefix(), child.fieldName(), unwrapped.suffix());
    }
    return parent.fieldName() + "." + child.fieldName();
  }

  private static String resolveNestedPropertyName(
      PropertyReference parent, PropertyReference child, PropertyReference grandChild) {
    String parentToChild = resolveNestedPropertyName(parent, child);
    JsonUnwrapped childUnwrapped = findJsonUnwrapped(child);
    if (childUnwrapped != null) {
      String fieldIdWithoutChild = parentToChild;
      // Flattened ids are built either with the raw child field id or a capitalized child suffix when
      // nested under an unwrapped prefix. If neither matches, keep the resolved path intact rather than
      // guessing a different strip rule for an unsupported naming shape.
      if (parentToChild.endsWith(child.fieldName())) {
        fieldIdWithoutChild = parentToChild.substring(0, parentToChild.length() - child.fieldName().length());
      } else {
        String capitalizedChild = capitalizeFirstChar(child.fieldName());
        if (parentToChild.endsWith(capitalizedChild)) {
          fieldIdWithoutChild = parentToChild.substring(0, parentToChild.length() - capitalizedChild.length());
        }
      }
      return composeUnwrappedPath(fieldIdWithoutChild + childUnwrapped.prefix(),
          grandChild.fieldName(), childUnwrapped.suffix());
    }
    return parentToChild + "." + grandChild.fieldName();
  }

  private static String composeUnwrappedPath(String prefix, String fieldName, String suffix) {
    String safePrefix = prefix == null ? "" : prefix;
    String safeSuffix = suffix == null ? "" : suffix;
    if (!safePrefix.isEmpty() && !safePrefix.endsWith(".")) {
      return safePrefix + capitalizeFirstChar(fieldName) + safeSuffix;
    }
    return safePrefix + fieldName + safeSuffix;
  }

  private static String capitalizeFirstChar(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static JsonUnwrapped findJsonUnwrapped(PropertyReference parent) {
    JsonUnwrapped getterAnnotation = parent.getterMethod().getAnnotation(JsonUnwrapped.class);
    if (getterAnnotation != null) {
      return getterAnnotation;
    }

    Field field = findFieldByConventions(parent.implementationClass(), parent.beanProperty());
    return field != null ? field.getAnnotation(JsonUnwrapped.class) : null;
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
      return applyNamingStrategy(implementationClass, field.getName());
    }

    return applyNamingStrategy(implementationClass, beanProperty);
  }

  private static String applyNamingStrategy(Class<?> implementationClass, String defaultName) {
    JsonNaming jsonNaming = implementationClass.getAnnotation(JsonNaming.class);
    if (jsonNaming == null) {
      return defaultName;
    }

    Class<? extends PropertyNamingStrategy> strategyClass = jsonNaming.value();
    if (strategyClass == null || strategyClass == PropertyNamingStrategy.class) {
      return defaultName;
    }

    try {
      PropertyNamingStrategy strategy = strategyClass.getDeclaredConstructor().newInstance();
      if (strategy instanceof PropertyNamingStrategies.NamingBase namingBase) {
        return namingBase.translate(defaultName);
      }
      return strategy.nameForField(null, null, defaultName);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalArgumentException("Unable to apply JsonNaming strategy " + strategyClass.getName(), ex);
    }
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

  private record PropertyReference(
      String fieldName,
      Method getterMethod,
      Class<?> implementationClass,
      String beanProperty) {
  }

  private String renderFor(Precedence target) {
    if (this.precedence.strength < target.strength) {
      return "(" + this.expression + ")";
    }
    return this.expression;
  }

  private enum Precedence {
    OR(1),
    AND(2),
    ATOM(3);

    private final int strength;

    Precedence(int strength) {
      this.strength = strength;
    }
  }
}
