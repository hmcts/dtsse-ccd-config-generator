package ccd.sdk.generator;

import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.Event;
import ccd.sdk.types.EventTypeBuilder;
import com.google.common.collect.*;

import java.util.List;
import java.util.Map;

public class ConfigBuilderImpl<T> implements ConfigBuilder<T> {
    public final Map<String, Event.EventBuilder<T>> events = Maps.newHashMap();
    public String caseType;
    public final Table<String, String, String> stateRoles = HashBasedTable.create();
    public final Multimap<String, String> stateRoleblacklist = ArrayListMultimap.create();
    public final Table<String, String, String> explicit = HashBasedTable.create();
    public final Map<String, String> statePrefixes = Maps.newHashMap();

    private Class caseData;
    public ConfigBuilderImpl(Class caseData) {
        this.caseData = caseData;
    }

    @Override
    public EventTypeBuilder<T> event(final String id) {
        Event.EventBuilder<T> e = Event.EventBuilder.builder(caseData);
        return new EventTypeBuilder<>(e, state -> {
            String actualId = id;
            if (events.containsKey(actualId)) {
                actualId = id + statePrefixes.getOrDefault(state, "") + state;
                if (events.containsKey(actualId)) {
                    throw new RuntimeException("Duplicate event: " + actualId);
                }
            }
            e.eventId(id);
            e.id(actualId);
            events.put(actualId, e);
        });
    }

    @Override
    public void caseType(String caseType) {
        this.caseType = caseType;
    }

    @Override
    public void grant(String state, String permissions, String role) {
        stateRoles.put(state, role, permissions);
    }

    @Override
    public void blacklist(String state, String... roles) {
        for (String role : roles) {
            stateRoleblacklist.put(state, role);
        }
    }

    @Override
    public void explicitState(String eventId, String role, String crud) {
        explicit.put(eventId, role, crud);

    }

    @Override
    public void prefix(String state, String prefix) {
        statePrefixes.put(state, prefix);
    }

    public List<Event.EventBuilder<T>> getEvents() {
        return Lists.newArrayList(events.values());
    }
}
