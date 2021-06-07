package uk.gov.hmcts.ccd.sdk;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.JsonUtils.mergeInto;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class CaseRoleGenerator {

  @SneakyThrows
  public static void generate(final File rootOutputfolder, String caseType,
                              final Class<?> roleClass) {

    final Path path = Paths.get(rootOutputfolder.getPath(), "CaseRoles.json");

    final List<Map<String, Object>> caseRoles = Arrays.stream(roleClass.getEnumConstants())
        .filter(x -> ((HasRole)x).getRole().matches("^\\[.+\\]$"))
        .map(o -> enumToJsonMap(caseType, roleClass, o, ((HasRole) o).getRole()))
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
    String name = ccd != null && !Strings.isNullOrEmpty(ccd.name()) ? ccd.name() :
        enumConstant.toString();
    String desc = ccd != null ? ccd.label() : "";
    field.put("Name", name);
    field.put("Description", desc);

    return field;
  }
}
