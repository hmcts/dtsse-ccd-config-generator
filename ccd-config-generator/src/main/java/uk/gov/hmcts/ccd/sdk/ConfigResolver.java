package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchCases;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

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
    ConfigBuilderImpl<T, S, R> builder = new ConfigBuilderImpl(typeArgs[0], allStates);
    for (CCDConfig<T, S, R> c : configs) {
      c.configure(builder);
    }

    List<Event<T, R, S>> events = builder.getEvents();
    Map<String, AboutToStart> aboutToStartCallbacks = Maps.newHashMap();
    Map<String, AboutToSubmit> aboutToSubmitCallbacks = Maps.newHashMap();
    Map<String, Submitted> submittedCallbacks = Maps.newHashMap();
    Table<String, String, MidEvent> midEventCallbacks = HashBasedTable.create();
    for (Event event : events) {
      if (event.getAboutToStartCallback() != null) {
        aboutToStartCallbacks.put(event.getId(), event.getAboutToStartCallback());
      }
      if (event.getAboutToSubmitCallback() != null) {
        aboutToSubmitCallbacks.put(event.getId(), event.getAboutToSubmitCallback());
      }
      if (event.getSubmittedCallback() != null) {
        submittedCallbacks.put(event.getId(), event.getSubmittedCallback());
      }
      for (Map.Entry<String, MidEvent> midEvent : event.getFields().build()
          .getPagesToMidEvent().entrySet()) {
        midEventCallbacks.put(event.getId(), midEvent.getKey(), midEvent.getValue());
      }
    }

    Map<Class, Integer> types = resolve(typeArgs[0], basePackage);
    return new ResolvedCCDConfig(builder.caseType, builder.callbackHost, builder.caseName,
        builder.caseDesc, builder.jurId, builder.jurName, builder.jurDesc,
        typeArgs[0], typeArgs[1], typeArgs[2], events, types,
        allStates, aboutToStartCallbacks, aboutToSubmitCallbacks, submittedCallbacks,
        midEventCallbacks, builder.stateRolePermissions,
        builder.tabs.stream().map(Tab.TabBuilder::build).collect(Collectors.toList()),
        builder.workBasketResultFields.stream().map(WorkBasket.WorkBasketBuilder::build).collect(
            Collectors.toList()),
        builder.workBasketInputFields.stream().map(WorkBasket.WorkBasketBuilder::build).collect(
            Collectors.toList()),
        builder.searchResultFields.stream().map(Search.SearchBuilder::build).collect(
            Collectors.toList()),
        builder.searchInputFields.stream().map(Search.SearchBuilder::build).collect(
            Collectors.toList()),
        builder.searchCaseResultFields.stream().map(SearchCases.SearchCasesBuilder::build).collect(
            Collectors.toList()),
        ImmutableMap.copyOf(builder.roleHierarchy),
        builder.explicitFields.stream().map(uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder::build).collect(
            Collectors.toList())
        );
  }


  public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
    Map<Class, Integer> result = Maps.newHashMap();
    resolve(dataClass, result, 0);
    result = Maps.filterKeys(result, x -> x.getPackageName().startsWith(basePackage));
    return result;
  }

  private static void resolve(Class dataClass, Map<Class, Integer> result, int level) {
    for (java.lang.reflect.Field field : ReflectionUtils.getFields(dataClass)) {
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
