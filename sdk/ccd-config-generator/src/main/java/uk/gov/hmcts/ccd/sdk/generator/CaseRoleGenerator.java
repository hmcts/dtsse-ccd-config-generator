package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class CaseRoleGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File rootOutputfolder, ResolvedCCDConfig<T, S, R> config) {

    final Path path = Paths.get(rootOutputfolder.getPath(), "CaseRoles.json");

    final List<Map<String, Object>> caseRoles = Arrays.stream(config.getRoleClass().getEnumConstants())
        .filter(x -> x.getRole().matches("^\\[.+\\]$"))
        .map(o -> enumToJsonMap(config.getCaseType(), config.getRoleClass(), o, o.getRole()))
        .collect(toList());

    mergeInto(path, caseRoles, new AddMissing(), "ID");
  }


  @SneakyThrows
  private static Map<String, Object> enumToJsonMap(String caseType, Class<?> enumType,
                                                   Object enumConstant, String id) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("ID", id);

    CCD ccd = enumType.getField(enumConstant.toString()).getAnnotation(CCD.class);
    String name = ccd != null && !Strings.isNullOrEmpty(ccd.label()) ? ccd.label() :
        enumConstant.toString();
    String desc = ccd != null ? ccd.hint() : "";
    field.put("Name", name);
    field.put("Description", desc);

    return field;
  }
}
