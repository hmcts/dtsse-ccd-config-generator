package ccd.sdk.generator;

import ccd.sdk.types.*;
import com.google.common.base.Strings;
import com.google.common.collect.*;

import java.util.List;
import java.util.Map;

public class ConfigBuilderImpl<T, S, R extends Role> implements ConfigBuilder<T, S, R> {
    public final Map<String, Event.EventBuilder<T, Role, S>> events = Maps.newHashMap();
    public String caseType;
    public final Table<String, String, String> stateRoles = HashBasedTable.create();
    public final Multimap<String, String> stateRoleblacklist = ArrayListMultimap.create();
    public final Table<String, String, String> explicit = HashBasedTable.create();
    public final Map<String, String> statePrefixes = Maps.newHashMap();
    public final List<Map<String, Object>> explicitFields = Lists.newArrayList();

    private Class caseData;
    public ConfigBuilderImpl(Class caseData) {
        this.caseData = caseData;
    }

    @Override
    public EventTypeBuilder<T, S> event(final String id) {
        Event.EventBuilder<T, Role, S> e = Event.EventBuilder.builder(caseData);
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
    public void grant(S state, String permissions, R role) {
        stateRoles.put(state.toString(), role.getRole(), permissions);
    }

    @Override
    public void blacklist(S state, R... roles) {
        for (Role role : roles) {
            stateRoleblacklist.put(state.toString(), role.getRole());
        }
    }

    @Override
    public void explicitState(String eventId, R role, String crud) {
        explicit.put(eventId, role.getRole(), crud);

    }

    @Override
    public void prefix(S state, String prefix) {
        statePrefixes.put(state.toString(), prefix);
    }

    @Override
    public void caseField(String id, String showCondition, String type, String typeParam, String label) {
        Map<String, Object> data = Maps.newHashMap();
        explicitFields.add(data);
        data.put("ID", id);
        data.put("Label", label);
        data.put("FieldType", type);
        if (!Strings.isNullOrEmpty(typeParam)) {
            data.put("FieldTypeParameter", typeParam);
        }
    }

    @Override
    public void caseField(String id, String label, String type, String collectionType) {
        caseField(id, null, type, collectionType, label);
    }

    @Override
    public void caseField(String id, String label, String type) {
        caseField(id, label, type, null);
    }

    public List<Event.EventBuilder<T, Role, S>> getEvents() {
        return Lists.newArrayList(events.values());
    }
}
