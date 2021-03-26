package uk.gov.hmcts.ccd.sdk;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.JsonUtils.mergeInto;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.HasCaseRole;
import uk.gov.hmcts.ccd.sdk.api.HasCaseTypePerm;

public class CaseRoleGenerator {

  public static <S, R extends HasCaseTypePerm, T, C extends HasCaseRole> void generate(
      final File rootOutputfolder,
      final ResolvedCCDConfig<T, S, R, C> config) {

    final Path path = Paths.get(rootOutputfolder.getPath(), "CaseRoles.json");
    final String caseType = config.builder.caseType;
    final Class<C> caseRoleType = config.caseRoleType;

    if (caseRoleType.isEnum()) {

      final List<Map<String, Object>> caseRoles = stream(caseRoleType.getEnumConstants())
          .map(caseRole -> createCaseRole(caseType, caseRole))
          .collect(toList());

      mergeInto(path, caseRoles, new AddMissing(), "ID");
    }
  }

  private static <C extends Enum<C> & HasCaseRole> Map<String, Object> createCaseRole(final String caseType,
                                                                                      final HasCaseRole caseRole) {

    Map<String, Object> result = new Hashtable<>();
    result.put("LiveFrom", "01/01/2017");
    result.put("CaseTypeID", caseType);
    result.put("ID", caseRole.getRole());
    result.put("Name", caseRole.getName());
    result.put("Description", caseRole.getDescription());
    return result;
  }
}
