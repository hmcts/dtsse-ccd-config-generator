package uk.gov.hmcts.ccd.sdk;

import uk.gov.hmcts.ccd.sdk.types.Event;

import java.util.List;
import java.util.Map;

public class ResolvedCCDConfig {
    public final Class<?> typeArg;
    public final ConfigBuilderImpl builder;
    public final List<Event> events;
    public final Map<Class, Integer> types;

    public ResolvedCCDConfig(Class<?> typeArg, ConfigBuilderImpl builder, List<Event> events, Map<Class, Integer> types) {
        this.typeArg = typeArg;
        this.builder = builder;
        this.events = events;
        this.types = types;
    }
}
