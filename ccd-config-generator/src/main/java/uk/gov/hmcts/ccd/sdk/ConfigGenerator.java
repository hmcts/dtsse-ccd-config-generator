package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

@Configuration
class ConfigGenerator<T, S, R extends HasRole> {

  private static final String basePackage = "uk.gov.hmcts";

  private List<CCDConfig<T, S, R>> configs;

  @Autowired
  public ConfigGenerator(List<CCDConfig<T, S, R>> configs) {
    if (configs.isEmpty()) {
      throw new RuntimeException("Expected at least one CCDConfig implementation but none found.");
    }
    this.configs = configs;
  }

  public void resolveConfig(File outputFolder) {
    initOutputDirectory(outputFolder);
    ResolvedCCDConfig resolved = resolveCCDConfig();
    File destination = Strings.isNullOrEmpty(resolved.environment) ? outputFolder
        : new File(outputFolder, resolved.environment);
    writeConfig(destination, resolved);
  }

  @SneakyThrows
  @Bean
  public ResolvedCCDConfig<T, S, R> resolveCCDConfig() {
    CCDConfig<T, S, R> config = this.configs.iterator().next();
    Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CCDConfig.class, config.getClass());
    Set<S> allStates = Set.of(((Class<S>)typeArgs[1]).getEnumConstants());
    ConfigBuilderImpl builder = new ConfigBuilderImpl(typeArgs[0], allStates);
    for (CCDConfig<T, S, R> c : configs) {
      c.configure(builder);
    }

    List<Event> events = builder.getEvents();
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
        midEventCallbacks.put(event.getEventID(), midEvent.getKey(), midEvent.getValue());
      }
    }

    Map<Class, Integer> types = resolve(typeArgs[0], basePackage);
    return new ResolvedCCDConfig(typeArgs[0], typeArgs[1], typeArgs[2], builder, events, types,
        builder.environment, allStates, aboutToStartCallbacks, aboutToSubmitCallbacks, submittedCallbacks,
        midEventCallbacks);
  }

  private void writeConfig(File outputfolder, ResolvedCCDConfig<T, S, R> config) {
    outputfolder.mkdirs();
    new CaseEventGenerator<T, S, R>().writeEvents(outputfolder, config);
    CaseEventToFieldsGenerator.writeEvents(outputfolder, config.events, config.builder.caseType,
        config.builder.callbackHost, config.midEventCallbacks);
    ComplexTypeGenerator.generate(outputfolder, config.builder.caseType, config.types);
    CaseEventToComplexTypesGenerator.writeEvents(outputfolder, config.events);
    Table<String, R, Set<Permission>> eventPermissions = buildEventPermissions(config.builder,
        config.events, config.allStates);
    AuthorisationCaseEventGenerator.generate(outputfolder, eventPermissions,
        config.builder.caseType);
    AuthorisationCaseFieldGenerator.generate(outputfolder, config, eventPermissions);
    CaseFieldGenerator.generateCaseFields(outputfolder, config);
    generateJurisdiction(outputfolder, config.builder);
    generateCaseType(outputfolder, config.builder);
    FixedListGenerator.generate(outputfolder, config.types);
    StateGenerator.generate(outputfolder, config.builder.caseType, config.stateArg);
    AuthorisationCaseTypeGenerator.generate(outputfolder, config.builder.caseType, config.roleType);
    CaseTypeTabGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    AuthorisationCaseStateGenerator.generate(outputfolder, config, eventPermissions);
    WorkBasketGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    SearchFieldAndResultGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    CaseRoleGenerator.generate(outputfolder, config.builder.caseType, config.roleType);
  }

  @SneakyThrows
  private void initOutputDirectory(File outputfolder) {
    if (outputfolder.exists() && outputfolder.isDirectory()) {
      MoreFiles.deleteRecursively(outputfolder.toPath(), RecursiveDeleteOption.ALLOW_INSECURE);
    }
    outputfolder.mkdirs();
  }

  private void generateCaseType(File outputfolder, ConfigBuilderImpl builder) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(Map.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.caseType,
        "Name", builder.caseName,
        "Description", builder.caseDesc,
        "JurisdictionID", builder.jurId,
        "SecurityClassification", "Public"
    ));
    Path output = Paths.get(outputfolder.getPath(),"CaseType.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
  }

  private void generateJurisdiction(File outputfolder, ConfigBuilderImpl builder) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(ImmutableMap.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.jurId,
        "Name", builder.jurName,
        "Description", builder.jurDesc
    ));
    Path output = Paths.get(outputfolder.getPath(),"Jurisdiction.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
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

  Table<String, R, Set<Permission>> buildEventPermissions(
      ConfigBuilderImpl<T, S, R> builder, List<Event<T, R, S>> events, Set<S> allStates) {


    Table<String, R, Set<Permission>> eventRolePermissions = HashBasedTable.create();
    for (Event<T, R, S> event : events) {
      // Add any state based role permissions unless event permits only explicit grants.
      if (!event.isExplicitGrants()) {
        // If Event is for all states, then apply each state's state level permissions.
        Set<S> keys = event.getPreState().equals(allStates)
            ? builder.stateRolePermissions.rowKeySet()
            : event.getPostState();
        for (S key : keys) {
          Map<R, Set<Permission>> roles = builder.stateRolePermissions.row(key);
          for (R role : roles.keySet()) {
            eventRolePermissions.put(event.getId(), role, roles.get(role));
          }
        }

        // Add any case history access
        SetMultimap<S, R> stateRoleHistoryAccess = builder.stateRoleHistoryAccess;
        for (S s : event.getPostState()) {
          if (stateRoleHistoryAccess.containsKey(s)) {
            for (R role : stateRoleHistoryAccess.get(s)) {
              eventRolePermissions.put(event.getId(), role, Collections.singleton(Permission.R));
            }
          }
        }
      }
      // Set event level permissions, overriding state level where set.
      SetMultimap<R, Permission> grants = event.getGrants();
      for (R role : grants.keySet()) {
        eventRolePermissions.put(event.getId(), role,
            grants.get(role));
      }
    }
    return eventRolePermissions;
  }
}
