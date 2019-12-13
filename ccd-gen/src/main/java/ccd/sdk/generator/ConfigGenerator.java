package ccd.sdk.generator;

import ccd.sdk.types.*;
import com.google.common.collect.Lists;
import net.jodah.typetools.TypeResolver;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ccd.sdk.generator.Utils.getField;

public class ConfigGenerator {
    private final File outputfolder;
    private final Reflections reflections;

    public ConfigGenerator(Reflections reflections, File outputFolder) {
        this.reflections = reflections;
        this.outputfolder = outputFolder;
    }

    public void generate(String caseTypeId) {
        Set<Class<? extends CCDConfig>> types = reflections.getSubTypesOf(CCDConfig.class);
        if (types.size() != 1) {
            throw new RuntimeException("Expected 1 CCDConfig class but found " + types.size());
        }

        Class<? extends CCDConfig> theirs = types.iterator().next();
        Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CCDConfig.class, theirs);

        Objenesis objenesis = new ObjenesisStd();
        CCDConfig config = objenesis.newInstance(types.iterator().next());
        ConfigBuilderImpl builder = new ConfigBuilderImpl(typeArgs[0]);
        config.configure(builder);
        List<Event.EventBuilder> builders = builder.getEvents();
        List<Event> events = builders.stream().map(x -> x.build()).collect(Collectors.toList());
        events = expandEvents(events, builder);

        EventGenerator.writeEvents(outputfolder, builder.caseType, events);
        CaseEventToFieldsGenerator.writeEvents(outputfolder, builder.caseType, events);
        ComplexTypeGenerator.generate(outputfolder, reflections, builder.caseType, events);
        CaseEventToComplexTypesGenerator.writeEvents(outputfolder, builder.caseType, events);
        AuthorisationCaseEventGenerator.generate(outputfolder, events, builder);
    }

    private List<Event> expandEvents(List<Event> events, ConfigBuilderImpl builder) {
        List<Event> sharedEvents = Lists.newArrayList();
        for (Event event : events) {
            if (event.getStates() != null && event.getStates().length > 1) {
                for (int t = 1; t < event.getStates().length; t++) {
                    String state = event.getStates()[t];
                    String prefix = (String) builder.statePrefixes.getOrDefault(state, "");
                    Event clone = event.withId(event.getId() + prefix + state);
                    // Mark the clone as deriving from this event.
                    clone.setEventID(event.getId());
                    clone.setPreState(state);
                    clone.setPostState(state);
                    sharedEvents.add(clone);
                }
            }
        }
        events.addAll(sharedEvents);
        return events;
    }




}
