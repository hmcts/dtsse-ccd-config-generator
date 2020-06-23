package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.jodah.typetools.TypeResolver;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import uk.gov.hmcts.ccd.sdk.generator.AuthorisationCaseEventGenerator;
import uk.gov.hmcts.ccd.sdk.generator.AuthorisationCaseFieldGenerator;
import uk.gov.hmcts.ccd.sdk.generator.AuthorisationCaseStateGenerator;
import uk.gov.hmcts.ccd.sdk.generator.CaseEventGenerator;
import uk.gov.hmcts.ccd.sdk.generator.CaseEventToComplexTypesGenerator;
import uk.gov.hmcts.ccd.sdk.generator.CaseEventToFieldsGenerator;
import uk.gov.hmcts.ccd.sdk.generator.CaseFieldGenerator;
import uk.gov.hmcts.ccd.sdk.generator.CaseTypeTabGenerator;
import uk.gov.hmcts.ccd.sdk.generator.ComplexTypeGenerator;
import uk.gov.hmcts.ccd.sdk.generator.FixedListGenerator;
import uk.gov.hmcts.ccd.sdk.generator.WorkBasketGenerator;
import uk.gov.hmcts.ccd.sdk.types.CCDConfig;
import uk.gov.hmcts.ccd.sdk.types.Event;

public class ConfigGenerator {

  private final Reflections reflections;
  private final String basePackage;

  public ConfigGenerator(Reflections reflections, String basePackage) {
    this.reflections = reflections;
    this.basePackage = basePackage;
  }

  public void resolveConfig(File outputFolder) {
    Set<Class<? extends CCDConfig>> configTypes =
        reflections.getSubTypesOf(CCDConfig.class).stream()
            .filter(x -> !Modifier.isAbstract(x.getModifiers())).collect(Collectors.toSet());

    if (configTypes.isEmpty()) {
      throw new RuntimeException("Expected at least one CCDConfig implementation but none found. "
          + "Scanned: " + basePackage);
    }

    for (Class<? extends CCDConfig> configType : configTypes) {
      Objenesis objenesis = new ObjenesisStd();
      CCDConfig config = objenesis.newInstance(configType);
      ResolvedCCDConfig resolved = resolveConfig(config);
      File destination = Strings.isNullOrEmpty(resolved.environment) ? outputFolder
          : new File(outputFolder, resolved.environment);
      writeConfig(destination, resolved);
    }
  }


  public ResolvedCCDConfig resolveConfig(CCDConfig config) {
    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CCDConfig.class, config.getClass());
    ConfigBuilderImpl builder = new ConfigBuilderImpl(typeArgs[0]);
    config.configure(builder);
    List<Event> events = builder.getEvents();
    Map<Class, Integer> types = resolve(typeArgs[0], basePackage);
    return new ResolvedCCDConfig(typeArgs[0], builder, events, types, builder.environment);
  }

  public void writeConfig(File outputfolder, ResolvedCCDConfig config) {
    outputfolder.mkdirs();
    CaseEventGenerator.writeEvents(outputfolder, config.builder.caseType, config.events);
    CaseEventToFieldsGenerator.writeEvents(outputfolder, config.events);
    ComplexTypeGenerator.generate(outputfolder, config.builder.caseType, config.types);
    CaseEventToComplexTypesGenerator.writeEvents(outputfolder, config.events);
    Table<String, String, String> eventPermissions = buildEventPermissions(config.builder,
        config.events);
    AuthorisationCaseEventGenerator.generate(outputfolder, eventPermissions,
        config.builder.caseType);
    AuthorisationCaseFieldGenerator.generate(outputfolder, config.builder.caseType, config.events,
        eventPermissions, config.builder.tabs, config.builder.workBasketInputFields,
        config.builder.workBasketResultFields, config.builder.roleHierarchy,
        config.builder.apiOnlyRoles, config.builder.explicitFields,
        config.builder.stateRoleHistoryAccess, config.builder.noFieldAuthRoles);
    CaseFieldGenerator
        .generateCaseFields(outputfolder, config.builder.caseType, config.typeArg, config.events,
            config.builder);
    FixedListGenerator.generate(outputfolder, config.types);
    CaseTypeTabGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    AuthorisationCaseStateGenerator.generate(outputfolder, config.builder.caseType, config.events,
        eventPermissions);
    WorkBasketGenerator.generate(outputfolder, config.builder.caseType, config.builder);
  }

  // Copied from jdk 9.
  public static String getPackageName(Class<?> c) {
    String pn;
    while (c.isArray()) {
      c = c.getComponentType();
    }
    if (c.isPrimitive()) {
      pn = "java.lang";
    } else {
      String cn = c.getName();
      int dot = cn.lastIndexOf('.');
      pn = (dot != -1) ? cn.substring(0, dot).intern() : "";
    }
    return pn;
  }

  public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
    Map<Class, Integer> result = Maps.newHashMap();
    resolve(dataClass, result, 0);
    System.out.println(result.size());
    System.out.println(basePackage);
    result = Maps.filterKeys(result, x -> getPackageName(x).startsWith(basePackage));
    return result;
  }

  private static void resolve(Class dataClass, Map<Class, Integer> result, int level) {
    for (java.lang.reflect.Field field : ReflectionUtils.getFields(dataClass)) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Class c = getComplexType(dataClass, field);
      if (null != c && !c.equals(dataClass)) {
        if (!result.containsKey(c) || result.get(c) < level) {
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

  Table<String, String, String> buildEventPermissions(
      ConfigBuilderImpl builder, List<Event> events) {


    Table<String, String, String> eventRolePermissions = HashBasedTable.create();
    for (Event event : events) {
      // Add any state based role permissions unless event permits only explicit grants.
      if (!event.isExplicitGrants()) {
        // If Event is for multi states, then apply each state's state level permissions.
        Set<String> keys = event.isMultiState()
            ? filterMultiStatesByEventStates(builder, event)
            : getStatesForEvent(event); //XXX: why this requires cast?
        for (String key : keys) {
          Map<String, String> roles = builder.stateRolePermissions.row(key);
          for (String role : roles.keySet()) {
            eventRolePermissions.put(event.getId(), role, roles.get(role));
          }
        }

        // Add any case history access
        Multimap<String, String> stateRoleHistoryAccess = builder.stateRoleHistoryAccess;
        if (stateRoleHistoryAccess.containsKey(event.getPostState())) {
          for (String role : stateRoleHistoryAccess.get(event.getPostState())) { //TODO that wouldn't work with multi states yet
            eventRolePermissions.put(event.getId(), role, "R");
          }
        }
      }

      // Set event level permissions, overriding state level where set.
      Map<String, String> grants = event.getGrants();
      for (String role : grants.keySet()) {
        eventRolePermissions.put(event.getId(), role, grants.get(role));
      }
    }
    return eventRolePermissions;
  }

  private Set<String> filterMultiStatesByEventStates(ConfigBuilderImpl builder, Event event) {
    if (event.isForAllStates()) {
      return builder.stateRolePermissions.rowKeySet();
    }
    else {
      return (Set<String>) builder.stateRolePermissions.rowKeySet().stream()
              .filter(state -> event.getPreState().contains(state)).collect(Collectors.toSet());
    }
  }

  private Set<String> getStatesForEvent(Event event) {
    return event.isInitial() ? Set.of(event.getPostState()) : (Set<String>) event.getPreState().stream().collect(Collectors.toSet());
  }
}
