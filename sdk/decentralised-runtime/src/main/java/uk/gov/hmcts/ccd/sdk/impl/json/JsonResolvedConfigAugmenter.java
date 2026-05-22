package uk.gov.hmcts.ccd.sdk.impl.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.domain.model.definition.CaseEventDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.CaseTypeDefinition;
import uk.gov.hmcts.ccd.domain.model.definition.EventPostStateDefinition;
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

  JsonResolvedConfigAugmenter(DefinitionRegistry definitionRegistry,
                              JsonCallbackAdapterFactory callbackAdapterFactory) {
    this.definitionRegistry = definitionRegistry;
    this.callbackAdapterFactory = callbackAdapterFactory;
  }

  @Override
  public List<ResolvedCCDConfig<?, ?, ?>> augment(List<ResolvedCCDConfig<?, ?, ?>> configs) {
    Map<String, CaseTypeDefinition> definitions = definitionRegistry.loadDefinitions();
    if (definitions.isEmpty()) {
      throw new IllegalStateException(
          "JSON callback runtime is enabled but no definition snapshots were found. Run dumpCCDDefinitions first.");
    }

    for (ResolvedCCDConfig<?, ?, ?> config : configs) {
      CaseTypeDefinition definition = definitions.get(config.getCaseType());
      if (definition != null) {
        addJsonEvents(config, definition);
      }
    }
    return configs;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, S, R extends HasRole> void addJsonEvents(ResolvedCCDConfig<?, ?, ?> rawConfig,
                                                       CaseTypeDefinition definition) {
    ResolvedCCDConfig<T, S, R> config = (ResolvedCCDConfig<T, S, R>) rawConfig;
    Map<String, Event<T, R, S>> additionalEvents = new LinkedHashMap<>();
    for (CaseEventDefinition eventDefinition : definition.getEvents()) {
      if (config.getEvents().containsKey(eventDefinition.getId()) || !hasJsonCallback(eventDefinition)) {
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

  private boolean hasJsonCallback(CaseEventDefinition definition) {
    return definition.getCallBackURLAboutToSubmitEvent() != null || definition.getCallBackURLSubmittedEvent() != null;
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
}
