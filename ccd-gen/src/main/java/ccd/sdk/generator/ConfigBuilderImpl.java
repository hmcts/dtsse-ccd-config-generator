package ccd.sdk.generator;

import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.Event;
import ccd.sdk.types.FieldBuilder;
import ccd.sdk.types.FieldType;
import com.google.common.collect.Lists;

import java.util.List;

public class ConfigBuilderImpl implements ConfigBuilder {
    public final List<FieldBuilder> fields = Lists.newArrayList();
    public final List<Event.EventBuilder> events = Lists.newArrayList();
    public String caseType;

    @Override
    public FieldBuilder caseField(String id, FieldType type) {
        FieldBuilder builder = new FieldBuilder(id, type);
        fields.add(builder);
        return builder;
    }

    @Override
    public Event.EventBuilder event(String id) {
        Event.EventBuilder e = Event.builder();
        events.add(e);
        e.id(id);
        return e;
    }

    @Override
    public void caseType(String caseType) {
        this.caseType = caseType;
    }
}
