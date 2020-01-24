package uk.gov.hmcts.ccd.sdk.generator;

import uk.gov.hmcts.ccd.sdk.Utils;
import uk.gov.hmcts.ccd.sdk.types.HasLabel;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class FixedListGenerator {
    public static void generate(File root, Map<Class, Integer> types) {
        File dir = root.toPath().resolve("FixedLists").toFile();
        dir.mkdir();

        for (Class aClass : types.keySet()) {
            if (aClass.isEnum()) {
                List<Map<String, Object>> fields = Lists.newArrayList();

                int order = 1;
                for (Object enumConstant : aClass.getEnumConstants()) {
                    Map<String, Object> value = Maps.newHashMap();
                    fields.add(value);
                    value.put("LiveFrom", "01/01/2017");
                    value.put("ID", aClass.getSimpleName());
                    value.put("ListElementCode", enumConstant);
                    if (enumConstant instanceof HasLabel) {
                        value.put("ListElement", ((HasLabel) enumConstant).getLabel());
                    } else {
                        value.put("ListElement", enumConstant);
                    }
                    value.put("DisplayOrder", order++);
                }

                Path path = Paths.get(dir.getPath(), aClass.getSimpleName() + ".json");
                Utils.mergeInto(path, fields, "ListElementCode");
            }
        }
    }
}
