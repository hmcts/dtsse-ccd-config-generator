package ccd.sdk.generator;

import ccd.sdk.types.*;
import ccd.sdk.types.DisplayContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import de.cronn.reflection.util.PropertyUtils;
import de.cronn.reflection.util.TypedPropertyGetter;
import net.jodah.typetools.TypeResolver;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
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
        events = expandEvents(events);

        EventGenerator.writeEvents(outputfolder, builder.caseType, events);
        CaseEventToFieldsGenerator.writeEvents(outputfolder, builder.caseType, events);
        generateComplexTypes();
        ComplexFieldGenerator.writeEvents(outputfolder, builder.caseType, events);
    }

    private List<Event> expandEvents(List<Event> events) {
        List<Event> sharedEvents = Lists.newArrayList();
        for (Event event : events) {
            if (event.getStates() != null && event.getStates().length > 1) {
                for (int t = 1; t < event.getStates().length; t++) {
                    String state = event.getStates()[t];
                    Event clone = event.withId(event.getId() + state);
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


    public void generateComplexTypes() {
        File complexTypes = new File(outputfolder, "ComplexTypes");
        complexTypes.mkdir();

        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        for (Class<?> type : types) {
            Path path = Paths.get(complexTypes.getPath(), type.getSimpleName() + ".json");
            Utils.writeFile(path, toComplexType(type));
        }
    }

    public static String toComplexType(Class c) {
        Map<String, Object> label = getField(c.getSimpleName());
        label.put("ListElementCode", c.getSimpleName().toLowerCase() + "Label");
        ComplexType a = (ComplexType) c.getAnnotation(ComplexType.class);
        label.put("FieldType", "Label");
        label.put("ElementLabel", a.label());

        List<Map<String, Object>> fields = Lists.newArrayList();
        fields.add(label);
        for (Field field : ReflectionUtils.getFields(c)) {
            Map<String, Object> fieldInfo = getField(c.getSimpleName());
            fieldInfo.put("ListElementCode", field.getName());
            fieldInfo.put("FieldType", "Text");

            CaseField f = field.getAnnotation(CaseField.class);
            fieldInfo.put("ElementLabel", f.label());
            if (f.hint().length() > 0) {
                fieldInfo.put("HintText", f.hint());
            }

            if (f.type() != FieldType.Unspecified) {
                fieldInfo.put("FieldType", f.type().toString());
            }

            fields.add(fieldInfo);
        }

        return Utils.serialise(fields);
    }


}
