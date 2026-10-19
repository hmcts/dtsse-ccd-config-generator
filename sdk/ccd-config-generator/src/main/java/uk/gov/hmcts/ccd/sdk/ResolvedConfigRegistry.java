package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.Event;

/**
 * Simple catalogue for resolved CCD configurations so other components can access
 * case-type metadata without reaching into controller internals.
 */
@Component
public class ResolvedConfigRegistry {

  private static final String DOCUMENT_UPDATED_EVENT_ID = "DocumentUpdated";

  private final Supplier<ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>>> configsByCaseType;

  /**
   * Resolves the configs when first asked for them: they are built from every case type's config, and a
   * config may depend on a runtime service that holds this registry, such as one running system events.
   */
  @Autowired
  public ResolvedConfigRegistry(ObjectProvider<List<ResolvedCCDConfig<?, ?, ?>>> configs) {
    this.configsByCaseType = Suppliers.memoize(() -> index(configs.getObject()));
  }

  public ResolvedConfigRegistry(List<ResolvedCCDConfig<?, ?, ?>> configs) {
    ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>> indexed = index(configs);
    this.configsByCaseType = () -> indexed;
  }

  private static ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>> index(List<ResolvedCCDConfig<?, ?, ?>> configs) {
    return Maps.uniqueIndex(configs, ResolvedCCDConfig::getCaseType);
  }

  public Collection<ResolvedCCDConfig<?, ?, ?>> getAll() {
    return configsByCaseType.get().values();
  }

  public Optional<ResolvedCCDConfig<?, ?, ?>> find(String caseType) {
    return Optional.ofNullable(configsByCaseType.get().get(caseType));
  }

  public ResolvedCCDConfig<?, ?, ?> getRequired(String caseType) {
    return find(caseType).orElseThrow(() -> new IllegalArgumentException("No config for case type " + caseType));
  }

  public Map<String, ResolvedCCDConfig<?, ?, ?>> asMap() {
    return configsByCaseType.get();
  }

  public Map<String, Object> applyPreEventHooks(String caseType, Map<String, Object> data) {
    ResolvedCCDConfig<?, ?, ?> config = getRequired(caseType);
    Function<Map<String, Object>, Map<String, Object>> pipeline =
        config.getPreEventHooks().stream().reduce(Function.identity(), Function::andThen);
    return pipeline.apply(data);
  }

  public Event<?, ?, ?> getRequiredEvent(String caseType, String eventId) {
    var config = getRequired(caseType);
    var events = config.getEvents();
    var resolved = events.get(eventId);
    if (resolved != null) {
      return resolved;
    }

    if (DOCUMENT_UPDATED_EVENT_ID.equals(eventId)) {
      return synthesiseDocumentUpdatedEvent(config);
    }

    throw new IllegalArgumentException("No event " + eventId + " defined for case type " + caseType);
  }

  public Optional<String> labelForState(String caseType, String stateId) {
    ResolvedCCDConfig<?, ?, ?> config = configsByCaseType.get().get(caseType);
    if (config == null || stateId == null) {
      return Optional.empty();
    }
    return config.labelForState(stateId);
  }

  private Event<?, ?, ?> synthesiseDocumentUpdatedEvent(ResolvedCCDConfig<?, ?, ?> config) {
    // CCD orchestrates DocumentUpdated as a system event; mirror that here so services
    // do not need to declare it explicitly in decentralised runtimes.
    @SuppressWarnings({"rawtypes", "unchecked"})
    Event.EventBuilder builder = Event.EventBuilder.builder(
        DOCUMENT_UPDATED_EVENT_ID,
        config.getCaseClass(),
        new PropertyUtils(),
        (Set) config.getAllStates(),
        (Set) config.getAllStates()
    );
    builder.name("Document updated");
    Event event = builder.doBuild();
    event.setDescription("Synthetic system event handled automatically by the decentralised runtime.");
    return event;
  }
}
