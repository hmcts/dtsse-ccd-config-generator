package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.Map;
import lombok.SneakyThrows;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

class ConfigResolver<T, S, R extends HasRole> {

  private static final String basePackage = "uk.gov.hmcts";

  private Collection<CCDConfig<T, S, R>> configs;

  public ConfigResolver(Collection<CCDConfig<T, S, R>> configs) {
    if (configs.isEmpty()) {
      throw new RuntimeException("Expected at least one CCDConfig implementation but none found.");
    }
    this.configs = configs;
  }


  @SneakyThrows
  public ResolvedCCDConfig<T, S, R> resolveCCDConfig() {
    CCDConfig<T, S, R> config = this.configs.iterator().next();
    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CCDConfig.class, config.getClass());
    ImmutableSet<S> allStates = ImmutableSet.copyOf(((Class<S>)typeArgs[1]).getEnumConstants());
    Map<Class, Integer> types = resolve(typeArgs[0], basePackage);
    ConfigBuilderImpl<T, S, R> builder = new ConfigBuilderImpl(
        new ResolvedCCDConfig(typeArgs[0], typeArgs[1], typeArgs[2], types, allStates)
    );

    for (CCDConfig<T, S, R> c : configs) {
      c.configure(builder);
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
    for (java.lang.reflect.Field field : ReflectionUtils.getAllFields(dataClass)) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class c = getComplexType(dataClass, field);
      if (null != c && !c.equals(dataClass)) {
        JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);

        // unwrapped properties are automatically ignored as complex types
        if (null == unwrapped && (!result.containsKey(c) || result.get(c) < level)) {
          result.put(c, level);
        }
        resolve(c, result, level + 1);
      }
    }
  }

  public static Class getComplexType(Class c, Field field) {
    if (Collection.class.isAssignableFrom(field.getType())) {
      ParameterizedType type = (ParameterizedType) TypeResolver.reify(field.getGenericType(), c);
      if (type.getActualTypeArguments()[0] instanceof ParameterizedType) {
        type = (ParameterizedType) type.getActualTypeArguments()[0];
      }
      return (Class) type.getActualTypeArguments()[0];
    }
    return field.getType();
  }
}
