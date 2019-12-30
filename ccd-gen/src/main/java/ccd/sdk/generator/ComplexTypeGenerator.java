package ccd.sdk.generator;

import ccd.sdk.types.ComplexType;
import ccd.sdk.types.Event;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import net.jodah.typetools.TypeResolver;
import org.reflections.ReflectionUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;

public class ComplexTypeGenerator {
    private static final Set<Class> knownTypes = Set.of(
            String.class,
            Date.class,
            LocalDate.class,
            List.class,
            boolean.class,
            long.class
    );

    public static void generate(File root, Class dataClass, String caseType, List<Event> events, String basepackage) {

        File complexTypes = new File(root, "ComplexTypes");
        complexTypes.mkdir();
        Map<Class, Integer> types = resolve(dataClass, basepackage);
        int maxDepth = types.values().stream().mapToInt(Integer::intValue).max().getAsInt();

        for (Class<?> c : types.keySet()) {
            ComplexType complex = c.getAnnotation(ComplexType.class);
            String id = null != complex && complex.name().length() > 0 ? complex.name() : c.getSimpleName();
            if (null != complex && !complex.generate()) {
                continue;
            }

            List<Map<String, Object>> fields = CaseFieldGenerator.toComplex(c, caseType);

            for (Map<String, Object> info : fields) {
                info.put("ListElementCode", info.get("ID"));
                info.put("ElementLabel", info.remove("Label"));
                info.put("ID", id);
            }

            if (null != complex && !Strings.isNullOrEmpty(complex.label())) {
                Map<String, Object> fieldInfo = CaseFieldGenerator.getField(caseType, id);
                fieldInfo.put("ElementLabel", complex.label());
                fieldInfo.put("FieldType", "Label");
                String labelId = complex.labelId().length() > 0 ? complex.labelId() : c.getSimpleName().toLowerCase() + "Label";
                fieldInfo.put("ListElementCode", labelId);
                fields.add(0, fieldInfo);
            }

            if (null != complex && !Strings.isNullOrEmpty(complex.border())) {
                Map<String, Object> fieldInfo = CaseFieldGenerator.getField(caseType, id);
                fieldInfo.put("ElementLabel", complex.border());
                fieldInfo.put("FieldType", "Label");
                fieldInfo.put("ListElementCode", complex.borderId());
                fields.add(fieldInfo);
            }

            int depth = types.get(c);
            Path path;
            if (0 == depth) {
                path = Paths.get(complexTypes.getPath(), id + ".json");
            } else {
                String prefix = maxDepth - depth + "_";
                path = Paths.get(complexTypes.getPath(), prefix + id + ".json");
            }
            Utils.mergeInto(path, fields, "ListElementCode");
        }
    }
    public static Map<Class, Integer> resolve(Class dataClass, String basePackage) {
        Map<Class, Integer> result = Maps.newHashMap();
        resolve(dataClass, result, 0);
        System.out.println(result.size());
        result = Maps.filterKeys(result, x -> x.getPackageName().startsWith(basePackage));
        return result;
    }

    private static void resolve(Class dataClass, Map<Class, Integer> result, int level) {
        for (Field field : ReflectionUtils.getFields(dataClass)) {
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
        if (knownTypes.contains(field.getType())) {
            return null;
        }
        return field.getType();
    }
}
