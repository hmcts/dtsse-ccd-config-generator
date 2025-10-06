package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Event;

/**
 * Simple catalogue for resolved CCD configurations so other components can access
 * case-type metadata without reaching into controller internals.
 */
@Component
public class ResolvedConfigRegistry {

  private final ImmutableMap<String, ResolvedCCDConfig<?, ?, ?>> configsByCaseType;

  public ResolvedConfigRegistry(java.util.List<ResolvedCCDConfig<?, ?, ?>> configs) {
    this.configsByCaseType = Maps.uniqueIndex(configs, ResolvedCCDConfig::getCaseType);
  }

  public Collection<ResolvedCCDConfig<?, ?, ?>> getAll() {
    return configsByCaseType.values();
  }

  public Optional<ResolvedCCDConfig<?, ?, ?>> find(String caseType) {
    return Optional.ofNullable(configsByCaseType.get(caseType));
  }

  public ResolvedCCDConfig<?, ?, ?> getRequired(String caseType) {
    return find(caseType).orElseThrow(() -> new IllegalArgumentException("No config for case type " + caseType));
  }

  public Map<String, ResolvedCCDConfig<?, ?, ?>> asMap() {
    return configsByCaseType;
  }

  public Map<String, Object> applyPreEventHooks(String caseType, Map<String, Object> data) {
    ResolvedCCDConfig<?, ?, ?> config = getRequired(caseType);
    Function<Map<String, Object>, Map<String, Object>> pipeline =
        config.getPreEventHooks().stream().reduce(Function.identity(), Function::andThen);
    return pipeline.apply(data);
  }

  public Event<?, ?, ?> getRequiredEvent(String caseType, String eventId) {
    return find(caseType)
        .map(ResolvedCCDConfig::getEvents)
        .map(events -> events.get(eventId))
        .orElseThrow(() -> new IllegalArgumentException(
            "No event " + eventId + " defined for case type " + caseType
        ));
  }

  public Optional<String> labelForState(String caseType, String stateId) {
    ResolvedCCDConfig<?, ?, ?> config = configsByCaseType.get(caseType);
    if (config == null || stateId == null) {
      return Optional.empty();
    }

    Class<?> stateClass = config.getStateClass();
    Object[] constants = stateClass.getEnumConstants();
    if (constants == null) {
      return Optional.empty();
    }

    for (Object constant : constants) {
      if (stateId.equals(constant.toString())) {
        try {
          Field field = stateClass.getField(constant.toString());
          CCD ccd = field.getAnnotation(CCD.class);
          if (ccd != null && !ccd.label().isBlank()) {
            return Optional.of(ccd.label());
          }
        } catch (NoSuchFieldException ignored) {
          // Fall back to the enum name if reflection fails
        }
        return Optional.of(stateId);
      }
    }
    return Optional.empty();
  }
}
