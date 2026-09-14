package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.join;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class RoleToAccessProfilesGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {

    final Path path = Paths.get(outputFolder.getPath(), "RoleToAccessProfiles.json");

    final List<Map<String, Object>> rows = config.getCaseRoleToAccessProfiles().stream()
        .map(o -> toJson(config.getCaseType(), o))
        .collect(toList());
    mergeInto(path, rows, new AddMissing(), "RoleName");
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, CaseRoleToAccessProfile caseRoleToAccessProfile) {
    Map<String, Object> field = JsonUtils.caseRow(caseType);

    // A mapping declared against a plain string (organisational / IDAM roles that are not case-type
    // UserRoles) carries its RoleName directly; the typed API resolves it from the HasRole constant.
    String roleName = caseRoleToAccessProfile.getRole() != null
        ? caseRoleToAccessProfile.getRole().getRole()
        : caseRoleToAccessProfile.getRoleName();
    if (caseRoleToAccessProfile.isLegacyIdamRole()) {
      field.put("RoleName", "idam:" + roleName);
    } else {
      field.put("RoleName", roleName);
    }
    field.put("CaseAccessCategories", join(caseRoleToAccessProfile.getCaseAccessCategories(), ","));
    field.put("Authorisation", join(caseRoleToAccessProfile.getAuthorisation(), ","));
    field.put("ReadOnly", JsonUtils.yn(caseRoleToAccessProfile.isReadonly()));
    field.put("AccessProfiles", join(caseRoleToAccessProfile.getAccessProfiles(), ","));
    field.put("Disabled", JsonUtils.yn(caseRoleToAccessProfile.isDisabled()));

    return field;
  }

}
