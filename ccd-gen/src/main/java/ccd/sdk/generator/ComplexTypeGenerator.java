package ccd.sdk.generator;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ComplexType;
import ccd.sdk.types.Event;
import ccd.sdk.types.FieldType;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ccd.sdk.generator.Utils.getField;

public class ComplexTypeGenerator {
    public static void generate(File root, Reflections reflections, String caseType, List<Event> events) {

        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        for (Class<?> type : types) {
            toComplexType(root, type);
        }
    }

    public static void toComplexType(File root, Class c) {
        ComplexType a = (ComplexType) c.getAnnotation(ComplexType.class);
        String id = a.name().length() > 0 ? a.name() : c.getSimpleName();
        List<Map<String, Object>> fields = Lists.newArrayList();

        if (a.label().length() > 0) {
            Map<String, Object> label = getField(id);

            String labelId = a.labelId().length() > 0 ? a.labelId() : c.getSimpleName().toLowerCase() + "Label";
            label.put("ListElementCode", labelId);
            label.put("FieldType", "Label");
            label.put("ElementLabel", a.label());

            fields.add(label);
        }

        for (Field field : ReflectionUtils.getFields(c)) {
            Map<String, Object> fieldInfo = getField(id);
            fieldInfo.put("ListElementCode", field.getName());
            fieldInfo.put("FieldType", "Text");

            CaseField f = field.getAnnotation(CaseField.class);
            if (null != f) {
                fieldInfo.put("ElementLabel", f.label());
                if (f.hint().length() > 0) {
                    fieldInfo.put("HintText", f.hint());
                }

                if (f.type() != FieldType.Unspecified) {
                    fieldInfo.put("FieldType", f.type().toString());
                }


                if (!StringUtils.isEmpty(f.showCondition())) {
                    fieldInfo.put("FieldShowCondition", f.showCondition());
                }

                if (!StringUtils.isEmpty(f.typeParameter())) {
                    fieldInfo.put("FieldType", "MultiSelectList");
                    fieldInfo.put("FieldTypeParameter", f.typeParameter());
                }
            }

            fields.add(fieldInfo);
        }

        File complexTypes = new File(root, "ComplexTypes");
        complexTypes.mkdir();
        Path path = Paths.get(complexTypes.getPath(), id + ".json");
        Utils.writeFile(path, Utils.serialise(fields));
    }
}
