package ccd.sdk.generator;

import ccd.sdk.types.CCDConfig;
import ccd.sdk.types.CaseData;
import ccd.sdk.types.Label;
import com.google.common.collect.Lists;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CaseFieldGenerator {
//    public static void generateCaseFields(Reflections reflections, String caseTypeId) {
//
//        Set<Class<?>> types = reflections.getTypesAnnotatedWith(CaseData.class);
//        if (types.size() != 1) {
//            throw new RuntimeException("Expected 1 @CaseData class but found " + types.size());
//        }
//
//        Class c = types.iterator().next();
//        List<Map<String, Object>> fields = Lists.newArrayList();
//
//        Map<String, Object> history = getField(c.getSimpleName());
//        history.put("ID", "caseHistory");
//        history.put("Label", " ");
//        history.put("FieldType", "CaseHistoryViewer");
//        fields.add(history);
//
//        Map<String, Object> ref = getField(c.getSimpleName());
//        history.put("ID", "caseIDReference");
//        history.put("Label", "Case ID");
//        history.put("FieldType", "Text");
//        fields.add(ref);
//
//        addConfigFields(fields);
//
//        for (Field field : ReflectionUtils.getFields(c)) {
//
//            Label label = field.getAnnotation(Label.class);
//            if (null != label) {
//                Map<String, Object> labelInfo = getField(label.id());
//                labelInfo.put("FieldType", "Label");
//                fields.add(labelInfo);
//            }
//            Map<String, Object> fieldInfo = getField(c.getSimpleName());
//            fields.add(fieldInfo);
//            fieldInfo.put("ID", field.getName());
//            fieldInfo.put("CaseTypeID", caseTypeId);
//        }
//
//        Path path = Paths.get(outputfolder.getPath(), "CaseField.json");
//        writeFile(path, serialise(fields));
//    }
//
//    private void addConfigFields(List<Map<String, Object>> fields) {
//        Set<Class<? extends CCDConfig>> types = reflections.getSubTypesOf(CCDConfig.class);
//        if (types.size() != 1) {
//            throw new RuntimeException("Expected 1 CCDConfig class but found " + types.size());
//        }
//
//        Objenesis objenesis = new ObjenesisStd(); // or ObjenesisSerializer
//        CCDConfig config = objenesis.newInstance(types.iterator().next());
//        ConfigBuilderImpl builder = new ConfigBuilderImpl();
//        config.configure(builder);
//
//        for (FieldBuilder field : builder.fields) {
//            for (List<String> label : field.labels) {
//                Map<String, Object> labelInfo = getField(label.get(0));
//                fields.add(labelInfo);
//                labelInfo.put("Label", label.get(1));
//            }
//            Map<String, Object> fieldInfo = getField(field.id);
//            fieldInfo.put("FieldType", field.type);
//            fields.add(fieldInfo);
//        }
//    }
}
