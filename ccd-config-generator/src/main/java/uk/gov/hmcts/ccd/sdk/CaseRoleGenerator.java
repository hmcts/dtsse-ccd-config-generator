package uk.gov.hmcts.ccd.sdk;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.JsonUtils.mergeInto;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.CaseRole;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class CaseRoleGenerator {

  public static <S, R extends HasRole, T> void generate(final File rootOutputfolder,
                                                        final ConfigBuilderImpl<T, S, R> configBuilder) {

    final Path path = Paths.get(rootOutputfolder.getPath(), "CaseRoles.json");
    final String caseType = configBuilder.caseType;

    final List<Map<String, Object>> caseRoles = configBuilder.caseRoles.stream()
        .map(caseRoleBuilder -> createCaseRole(caseType, caseRoleBuilder.build()))
        .collect(toList());

    mergeInto(path, caseRoles, new AddMissing(), "ID");
  }

  private static Map<String, Object> createCaseRole(final String caseType, final CaseRole caseRole) {

    Map<String, Object> result = new Hashtable<>();
    result.put("LiveFrom", "01/01/2017");
    result.put("CaseTypeID", caseType);
    result.put("ID", caseRole.getId());
    result.put("Name", caseRole.getName());
    result.put("Description", caseRole.getDescription());
    return result;
  }
}
