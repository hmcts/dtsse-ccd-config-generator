package ccd.sdk.generator;

import ccd.sdk.types.CaseField;
import ccd.sdk.types.ComplexType;
import ccd.sdk.types.Event;
import com.google.common.base.Strings;
import org.reflections.Reflections;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ComplexTypeGenerator {
    public static void generate(File root, Reflections reflections, String caseType, List<Event> events) {

        File complexTypes = new File(root, "ComplexTypes");
        complexTypes.mkdir();
        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        for (Class<?> c : types) {


            ComplexType complex = c.getAnnotation(ComplexType.class);
            String id = complex.name().length() > 0 ? complex.name() : c.getSimpleName();
            Path path = Paths.get(complexTypes.getPath(), id + ".json");

            List<Map<String, Object>> fields = CaseFieldGenerator.toComplex(c, caseType);

            for (Map<String, Object> info : fields) {
                info.put("ListElementCode", info.get("ID"));
                info.put("ElementLabel", info.get("Label"));
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
            Utils.writeFile(path, Utils.serialise(fields));
        }
    }
}
