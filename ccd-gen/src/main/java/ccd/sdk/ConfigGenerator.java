package ccd.sdk;

import ccd.sdk.generator.*;
import ccd.sdk.types.*;
import com.google.common.collect.Maps;
import net.jodah.typetools.TypeResolver;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
        Set<Class<? extends BaseCCDConfig>> configTypes = reflections.getSubTypesOf(BaseCCDConfig.class);
        if (configTypes.size() != 1) {
            throw new RuntimeException("Expected 1 CCDConfig class but found " + configTypes.size());
        }

        Objenesis objenesis = new ObjenesisStd();
        CCDConfig config = objenesis.newInstance(configTypes.iterator().next());
        generate(caseTypeId, config);
    }

    public void generate(String caseTypeId, CCDConfig config) {
        outputfolder.mkdirs();
        Class<?>[] typeArgs = TypeResolver.resolveRawArguments(CCDConfig.class, config.getClass());
        ConfigBuilderImpl builder = new ConfigBuilderImpl(typeArgs[0]);
        config.configure(builder);
        List<Event.EventBuilder> builders = builder.getEvents();
        List<Event> events = builders.stream().map(x -> x.build()).collect(Collectors.toList());
        Map<Class, Integer> types = resolve(typeArgs[0], config.getClass().getPackageName());

        CaseEventGenerator.writeEvents(outputfolder, builder.caseType, events);
        CaseEventToFieldsGenerator.writeEvents(outputfolder, events);
        ComplexTypeGenerator.generate(outputfolder, builder.caseType, types);
        CaseEventToComplexTypesGenerator.writeEvents(outputfolder, events);
        AuthorisationCaseEventGenerator.generate(outputfolder, events, builder);
        CaseFieldGenerator.generateCaseFields(outputfolder, caseTypeId, typeArgs[0], events, builder);
        FixedListGenerator.generate(outputfolder, types);
    }

    public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
        Map<Class, Integer> result = Maps.newHashMap();
        resolve(dataClass, result, 0);
        System.out.println(result.size());
        System.out.println(basePackage);
        result = Maps.filterKeys(result, x -> x.getPackageName().startsWith(basePackage));
        return result;
    }

    private static void resolve(Class dataClass, Map<Class, Integer> result, int level) {
        for (java.lang.reflect.Field field : ReflectionUtils.getFields(dataClass)) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class c = getComplexType(dataClass, field);
            if (null != c && !c.equals(dataClass)) {
                if (!result.containsKey(c) || result.get(c) < level) {
                    result.put(c, level);
                }
                resolve(c, result, level + 1);
            }
        }
    }

    public static Class getComplexType(Class c, Field field) {
        if (Collection.class.isAssignableFrom(field.getType())) {
            ParameterizedType pType = (ParameterizedType) TypeResolver.reify(field.getGenericType(), c);
            if (pType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                pType = (ParameterizedType) pType.getActualTypeArguments()[0];
            }
            return (Class) pType.getActualTypeArguments()[0];
        }
        return field.getType();
    }
}
