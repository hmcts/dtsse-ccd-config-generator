package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.api.CCD;

class StateGenerator {

  @SneakyThrows
  public static void generate(File root, String caseType, Class<?> statetype) {
    List<Map<String, Object>> result = Lists.newArrayList();
    int i = 1;
    if (statetype.isEnum()) {
      for (Object enumConstant : statetype.getEnumConstants()) {
        Map<String, Object> field = Maps.newHashMap();
        result.add(field);
        field.put("LiveFrom", "01/01/2017");
        field.put("CaseTypeID", caseType);
        field.put("ID", enumConstant);

        CCD ccd = statetype.getField(enumConstant.toString()).getAnnotation(CCD.class);
        String name = ccd != null && !Strings.isNullOrEmpty(ccd.name()) ? ccd.name() :
            enumConstant.toString();
        String desc = ccd != null ? ccd.label() : "";
        field.put("Name", name);
        field.put("Description", desc);
        field.put("DisplayOrder", i++);
      }
    }

    Path output = Paths.get(root.getPath(), "State.json");
    JsonUtils.mergeInto(output, result, new JsonUtils.AddMissing(), "ID");
  }
}
