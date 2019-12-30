package ccd.sdk.generator;

import ccd.sdk.Utils;
import ccd.sdk.types.ComplexType;
import com.google.common.base.Strings;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ComplexTypeGenerator {

    public static void generate(File root, String caseType, Map<Class, Integer> types) {

        File complexTypes = new File(root, "ComplexTypes");
        complexTypes.mkdir();
        types = types.entrySet().stream().filter(x -> !x.getKey().isEnum())
                .collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue()));

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

}
