package uk.gov.hmcts.ccd.sdk.impl.json;

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.EventPostStateDefinition;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.PropertyUtils;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigAugmenter;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.ccd.sdk.impl.DefinitionRegistry;

@Component
@ConditionalOnProperty(name = "decentralisation.legacy-json-service", havingValue = "true")
class JsonResolvedConfigAugmenter implements ResolvedConfigAugmenter {

  private final DefinitionRegistry definitionRegistry;
  private final JsonCallbackAdapterFactory callbackAdapterFactory;
  private final List<CaseView<?, ?>> caseViews;

  JsonResolvedConfigAugmenter(DefinitionRegistry definitionRegistry,
                              JsonCallbackAdapterFactory callbackAdapterFactory,
                              List<CaseView<?, ?>> caseViews) {
    this.definitionRegistry = definitionRegistry;
    this.callbackAdapterFactory = callbackAdapterFactory;
    this.caseViews = caseViews;
  }

  @Override
  public List<ResolvedCCDConfig<?, ?, ?>> augment(List<ResolvedCCDConfig<?, ?, ?>> configs) {
    Map<String, CaseTypeDefinition> definitions = definitionRegistry.loadDefinitions();
    if (definitions.isEmpty()) {
      throw new IllegalStateException(
          "JSON callback runtime is enabled but no definition snapshots were found. Run dumpCCDDefinitions first.");
    }

    List<ResolvedCCDConfig<?, ?, ?>> resolvedConfigs = new ArrayList<>(configs);
    addJsonOnlyConfigs(resolvedConfigs, definitions);

    for (ResolvedCCDConfig<?, ?, ?> config : resolvedConfigs) {
      CaseTypeDefinition definition = definitions.get(config.getCaseType());
      if (definition != null) {
        addJsonEvents(config, definition);
      }
    }
    return resolvedConfigs;
  }

  private void addJsonOnlyConfigs(List<ResolvedCCDConfig<?, ?, ?>> configs,
                                  Map<String, CaseTypeDefinition> definitions) {
    Set<String> existingCaseTypes = configs.stream()
        .map(ResolvedCCDConfig::getCaseType)
        .collect(Collectors.toSet());

    for (CaseView<?, ?> caseView : caseViews) {
      Set<String> caseTypeIds = caseView.caseTypeIds();
      if (caseTypeIds == null || caseTypeIds.isEmpty()) {
        continue;
      }

      Class<?> caseDataType = resolveCaseDataType(caseView);
      Class<? extends Enum<?>> stateType = resolveStateType(caseView);
      for (String caseTypeId : caseTypeIds) {
        if (existingCaseTypes.contains(caseTypeId) || !definitions.containsKey(caseTypeId)) {
          continue;
        }
        configs.add(jsonConfig(caseTypeId, caseDataType, stateType));
        existingCaseTypes.add(caseTypeId);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ResolvedCCDConfig<?, ?, ?> jsonConfig(String caseTypeId,
                                                Class<?> caseDataType,
                                                Class<? extends Enum<?>> stateType) {
    ResolvedCCDConfig config = new ResolvedCCDConfig(
        caseDataType,
        stateType,
        JsonRole.class,
        Map.of(),
        ImmutableSet.copyOf(stateType.getEnumConstants())
    );
    config.configureCaseType(caseTypeId);
    return config;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, S, R extends HasRole> void addJsonEvents(ResolvedCCDConfig<?, ?, ?> rawConfig,
                                                       CaseTypeDefinition definition) {
    ResolvedCCDConfig<T, S, R> config = (ResolvedCCDConfig<T, S, R>) rawConfig;
    Map<String, Event<T, R, S>> additionalEvents = new LinkedHashMap<>();
    List<CaseEventDefinition> events = definition.getEvents() == null ? List.of() : definition.getEvents();
    Map<String, Event<T, R, S>> existingEvents = config.getEvents() == null ? Map.of() : config.getEvents();
    for (CaseEventDefinition eventDefinition : events) {
      if (existingEvents.containsKey(eventDefinition.getId())) {
        continue;
      }
      additionalEvents.put(eventDefinition.getId(), buildEvent(config, eventDefinition));
    }
    config.addEvents(additionalEvents);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, S, R extends HasRole> Event<T, R, S> buildEvent(ResolvedCCDConfig<T, S, R> config,
                                                             CaseEventDefinition definition) {
    Event.EventBuilder<T, R, S> builder = Event.EventBuilder.builder(
        definition.getId(),
        config.getCaseClass(),
        new PropertyUtils(),
        preStates(config, definition),
        postStates(config, definition)
    );
    builder.name(definition.getName() == null ? definition.getId() : definition.getName());
    if (definition.getCallBackURLAboutToSubmitEvent() != null) {
      builder.aboutToSubmitCallback(callbackAdapterFactory.aboutToSubmit(
          definition.getCallBackURLAboutToSubmitEvent(),
          definition.getId()
      ));
    }
    if (definition.getCallBackURLSubmittedEvent() != null) {
      builder.submittedCallback(callbackAdapterFactory.submitted(
          definition.getCallBackURLSubmittedEvent(),
          definition.getId()
      ));
    }

    Event<T, R, S> event = builder.doBuild();
    event.setDescription(definition.getDescription());
    event.setShowSummary(Boolean.TRUE.equals(definition.getShowSummary()));
    event.setShowEventNotes(Boolean.TRUE.equals(definition.getShowEventNotes()));
    event.setEndButtonLabel(definition.getEndButtonLabel());
    event.setRetries(retries(definition));
    return event;
  }

  private Map<Webhook, String> retries(CaseEventDefinition definition) {
    if (definition.getRetriesTimeoutURLSubmittedEvent() == null
        || definition.getRetriesTimeoutURLSubmittedEvent().isEmpty()) {
      return Map.of();
    }
    return Map.of(Webhook.Submitted, definition.getRetriesTimeoutURLSubmittedEvent().toString());
  }

  private <T, S, R extends HasRole> Set<S> preStates(ResolvedCCDConfig<T, S, R> config,
                                                     CaseEventDefinition definition) {
    List<String> preStates = definition.getPreStates();
    if (preStates == null || preStates.isEmpty() || preStates.contains("*")) {
      return config.getAllStates();
    }
    return preStates.stream().map(state -> toState(config, state)).collect(Collectors.toSet());
  }

  private <T, S, R extends HasRole> Set<S> postStates(ResolvedCCDConfig<T, S, R> config,
                                                      CaseEventDefinition definition) {
    List<EventPostStateDefinition> postStates = definition.getPostStates();
    if (postStates == null || postStates.isEmpty()) {
      return config.getAllStates();
    }

    Set<String> postStateIds = postStates.stream()
        .map(EventPostStateDefinition::getPostStateReference)
        .collect(Collectors.toSet());
    if (postStateIds.contains("*")) {
      return config.getAllStates();
    }
    return postStateIds.stream().map(state -> toState(config, state)).collect(Collectors.toSet());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, S, R extends HasRole> S toState(ResolvedCCDConfig<T, S, R> config, String state) {
    if (!Enum.class.isAssignableFrom(config.getStateClass())) {
      return (S) state;
    }
    return (S) Enum.valueOf((Class) config.getStateClass(), state);
  }

  private Class<?> resolveCaseDataType(CaseView<?, ?> caseView) {
    Class<?> userClass = ClassUtils.getUserClass(caseView);
    Class<?> resolvedCase = ResolvableType.forClass(userClass).as(CaseView.class).getGeneric(0).resolve();
    return resolvedCase == null ? Map.class : resolvedCase;
  }

  private Class<? extends Enum<?>> resolveStateType(CaseView<?, ?> caseView) {
    Class<?> userClass = ClassUtils.getUserClass(caseView);
    Class<?> resolvedState = ResolvableType.forClass(userClass).as(CaseView.class).getGeneric(1).resolve();
    if (resolvedState == null || !Enum.class.isAssignableFrom(resolvedState)) {
      throw new IllegalStateException(
          "CaseView implementations must declare an enum state type. Found: %s".formatted(resolvedState));
    }
    @SuppressWarnings("unchecked")
    Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) resolvedState;
    return enumClass;
  }

  private enum JsonRole implements HasRole {
    JSON;

    @Override
    public String getRole() {
      return "json";
    }

    @Override
    public String getCaseTypePermissions() {
      return "";
    }
  }
}
