package ccd.sdk.generator;

import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.Event;
import com.google.common.collect.Lists;

import java.util.List;

public class ConfigBuilderImpl implements ConfigBuilder {
    public final List<Event.EventBuilder> events = Lists.newArrayList();
    public String caseType;

    private Class caseData;
    public ConfigBuilderImpl(Class caseData) {
        this.caseData = caseData;
    }

    @Override
    public Event.EventBuilder event(String id) {
        Event.EventBuilder e = Event.builder(caseData);
        events.add(e);
        e.id(id);
        return e;
    }

    @Override
    public void caseType(String caseType) {
        this.caseType = caseType;
    }
}
