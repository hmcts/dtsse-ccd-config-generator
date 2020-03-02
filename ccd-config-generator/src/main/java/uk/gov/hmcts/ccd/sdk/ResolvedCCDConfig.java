package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.types.Event;

public class ResolvedCCDConfig {

  public final Class<?> typeArg;
  public final ConfigBuilderImpl builder;
  public final List<Event> events;
  public final Map<Class, Integer> types;
  public final String environment;

  public ResolvedCCDConfig(Class<?> typeArg, ConfigBuilderImpl builder, List<Event> events,
      Map<Class, Integer> types, String environment) {
    this.typeArg = typeArg;
    this.builder = builder;
    this.events = events;
    this.types = types;
    this.environment = environment;
  }
}
