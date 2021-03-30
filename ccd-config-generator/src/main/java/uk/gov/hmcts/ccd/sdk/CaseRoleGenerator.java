package uk.gov.hmcts.ccd.sdk;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.JsonUtils.mergeInto;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class CaseRoleGenerator {

  @SneakyThrows
  public static void generate(final File rootOutputfolder, String caseType,
                              final Class<?> roleClass) {

    final Path path = Paths.get(rootOutputfolder.getPath(), "CaseRoles.json");

    final List<Map<String, Object>> caseRoles = Arrays.stream(roleClass.getEnumConstants())
        .filter(x -> ((HasRole)x).getRole().matches("^\\[.+\\]$"))
        .map(o -> StateGenerator.enumToJsonMap(caseType, roleClass, o, ((HasRole) o).getRole()))
        .collect(toList());

    mergeInto(path, caseRoles, new AddMissing(), "ID");
  }

}
