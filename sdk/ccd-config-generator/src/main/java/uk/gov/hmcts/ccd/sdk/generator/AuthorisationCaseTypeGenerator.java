package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
class AuthorisationCaseTypeGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {

    Class<?> roleEnum = config.getRoleClass();
    List<Map<String, Object>> result = Lists.newArrayList();
    if (roleEnum.isEnum()) {
      for (Object enumConstant : roleEnum.getEnumConstants()) {
        if (enumConstant instanceof HasRole) {
          HasRole r = (HasRole) enumConstant;
          // Add non case roles.
          if (!r.getRole().matches("\\[.+\\]")) {
            boolean shuttered = config.isShutterService() || config.getShutterServiceForRoles().contains(r);
            result.add(Map.of(
                "LiveFrom", "01/01/2017",
                "CaseTypeID", config.getCaseType(),
                "UserRole", r.getRole(),
                "CRUD", shuttered ? "D" : r.getCaseTypePermissions()
            ));
          }
        }

      }

      Path output = Paths.get(root.getPath(), "AuthorisationCaseType.json");
      JsonUtils.mergeInto(output, result, new JsonUtils.CRUDMerger(), "CaseTypeID", "UserRole");
    }
  }
}
