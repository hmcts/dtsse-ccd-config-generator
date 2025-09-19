package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
class StateGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> result = Lists.newArrayList();
    int i = 1;
    if (config.getStateClass().isEnum()) {
      for (Object enumConstant : config.getStateClass().getEnumConstants()) {
        Map<String, Object> field = enumToJsonMap(config.getCaseType(), config.getStateClass(), enumConstant,
            enumConstant.toString());
        field.put("DisplayOrder", i++);
        result.add(field);
      }
    }

    Path output = Paths.get(root.getPath(), "State.json");
    JsonUtils.mergeInto(output, result, new JsonUtils.AddMissing(), "ID");
  }

  @SneakyThrows
  public static Map<String, Object> enumToJsonMap(String caseType, Class<?> enumType,
                                                  Object enumConstant, String id) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("ID", id);

    CCD ccd = enumType.getField(enumConstant.toString()).getAnnotation(CCD.class);
    String name = ccd != null && !Strings.isNullOrEmpty(ccd.label()) ? ccd.label() :
        enumConstant.toString();
    field.put("Name", name);
    field.put("Description", name);
    String desc = ccd != null ? ccd.hint() : "";

    if (!Strings.isNullOrEmpty(desc)) {
      field.put("TitleDisplay", desc);
    }

    return field;
  }
}
