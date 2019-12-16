package ccd.sdk.generator;

import ccd.sdk.types.*;
import com.google.common.collect.Lists;
import net.jodah.typetools.TypeResolver;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.Reflections;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ConfigGenerator {
    private final File outputfolder;
    private final Reflections reflections;

    public ConfigGenerator(Reflections reflections, File outputFolder) {
        this.reflections = reflections;
        this.outputfolder = outputFolder;
    }

    public void generate(String caseTypeId) {
        Set<Class<? extends BaseCCDConfig>> types = reflections.getSubTypesOf(BaseCCDConfig.class);
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

        CaseEventGenerator.writeEvents(outputfolder, builder.caseType, events);
        CaseEventToFieldsGenerator.writeEvents(outputfolder, builder.caseType, events);
        ComplexTypeGenerator.generate(outputfolder, reflections, builder.caseType, events);
        CaseEventToComplexTypesGenerator.writeEvents(outputfolder, builder.caseType, events);
        AuthorisationCaseEventGenerator.generate(outputfolder, events, builder);
    }
}
