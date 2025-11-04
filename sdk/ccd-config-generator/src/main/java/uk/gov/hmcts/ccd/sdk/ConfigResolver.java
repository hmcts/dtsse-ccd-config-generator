package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

class ConfigResolver<T, S, R extends HasRole> {

  private static final String basePackage = "uk.gov.hmcts";

  private final Collection<CCDConfig<T, S, R>> configs;

  public ConfigResolver(Collection<CCDConfig<T, S, R>> configs) {
    if (configs.isEmpty()) {
      throw new RuntimeException("Expected at least one CCDConfig implementation but none found.");
    }
    this.configs = configs;
  }


  @SneakyThrows
  public ResolvedCCDConfig<T, S, R> resolveCCDConfig() {
    CCDConfig<T, S, R> config = this.configs.iterator().next();
    Class<?> userClass = ClassUtils.getUserClass(config);
    ResolvableType configType = ResolvableType.forClass(userClass).as(CCDConfig.class);
    @SuppressWarnings("unchecked")
    Class<T> caseType = (Class<T>) resolveGenericArgument(configType, 0, userClass);
    @SuppressWarnings("unchecked")
    Class<S> stateType = (Class<S>) resolveGenericArgument(configType, 1, userClass);
    @SuppressWarnings("unchecked")
    Class<R> roleType = (Class<R>) resolveGenericArgument(configType, 2, userClass);

    ImmutableSet<S> allStates = ImmutableSet.copyOf(stateType.getEnumConstants());
    Map<Class, Integer> types = resolve(caseType, basePackage);
    ConfigBuilderImpl<T, S, R> builder = new ConfigBuilderImpl(
        new ResolvedCCDConfig(caseType, stateType, roleType, types, allStates)
    );

    for (CCDConfig<T, S, R> c : configs) {
      c.configureDecentralised(builder);
    }

    return builder.build();
  }


  public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
    Map<Class, Integer> result = Maps.newHashMap();
    resolve(dataClass, result, 0);
    result = Maps.filterKeys(result, x -> x.getPackageName().startsWith(basePackage));
    return result;
  }

  private static void resolve(Class dataClass, Map<Class, Integer> result, int level) {
    ReflectionUtils.doWithFields(
        dataClass,
        field -> {
          Class c = getComplexType(dataClass, field);
          if (null != c && !c.equals(dataClass)) {
            JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);

            // unwrapped properties are automatically ignored as complex types
            if (null == unwrapped && (!result.containsKey(c) || result.get(c) < level)) {
              result.put(c, level);
            }
            resolve(c, result, level + 1);
          }
        },
        field -> !Modifier.isStatic(field.getModifiers()));
  }

  public static Class getComplexType(Class c, Field field) {
    if (Collection.class.isAssignableFrom(field.getType())) {
      ResolvableType fieldType = ResolvableType.forField(field, c);
      ResolvableType elementType = fieldType.getGeneric(0);
      if (elementType.hasGenerics()) {
        elementType = elementType.getGeneric(0);
      }
      Class<?> resolved = elementType.resolve();
      if (resolved == null) {
        throw new IllegalStateException("Unable to resolve collection element type for %s.%s"
            .formatted(c.getName(), field.getName()));
      }
      return resolved;
    }
    return field.getType();
  }

  private static Class<?> resolveGenericArgument(
      ResolvableType type, int index, Class<?> sourceClass) {
    Class<?> resolved = type.getGeneric(index).resolve();
    if (resolved == null) {
      throw new IllegalStateException(
          "Unable to resolve generic argument %d for %s".formatted(index, sourceClass.getName()));
    }
    return resolved;
  }
}
