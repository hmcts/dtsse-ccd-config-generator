package ccd.sdk.generator;

import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.Event;
import ccd.sdk.types.EventTypeBuilder;
import com.google.common.collect.Lists;

import java.util.List;

public class ConfigBuilderImpl<T> implements ConfigBuilder<T> {
    public final List<Event.EventBuilder<T>> events = Lists.newArrayList();
    public String caseType;

    private Class caseData;
    public ConfigBuilderImpl(Class caseData) {
        this.caseData = caseData;
    }

    @Override
    public EventTypeBuilder<T> event(String id) {
        Event.EventBuilder e = Event.EventBuilder.builder(caseData);
        events.add(e);
        e.id(id);
        return new EventTypeBuilder<>(e);
    }

    @Override
    public void caseType(String caseType) {
        this.caseType = caseType;
    }

    public List<Event.EventBuilder<T>> getEvents() {
        return events;
    }
}
