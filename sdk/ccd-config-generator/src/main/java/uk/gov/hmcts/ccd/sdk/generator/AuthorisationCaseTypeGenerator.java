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
        if (enumConstant instanceof HasRole r) {
          // Add non case roles. Case roles are opt-in because most CCD definitions authorise them
          // through case-role assignment rather than static case-type authorisation.
          if (!r.getRole().matches("\\[.+\\]")
              || config.getCaseRolesWithCaseTypeAuthorisation().contains(r)) {
            boolean shuttered =
                (config.isShutterService() || config.getShutterServiceForRoles().contains(r))
                    && !config.getShutterServiceExcludedRoles().contains(r);
            String crud = shuttered ? "D" : r.getCaseTypePermissions();
            // CRUD is a required column on this sheet, so the importer fails the whole definition
            // on a row that leaves it blank (DefinitionDataItem.findAttribute throws
            // "Couldn't find the column CRUD in the sheet AuthorisationCaseType"). A role with no
            // case-type grant is simply absent from a hand-written definition; emit nothing.
            if (crud == null || crud.isBlank()) {
              continue;
            }
            Map<String, Object> entry = JsonUtils.caseRow(config.getCaseType());
            entry.put("UserRole", r.getRole());
            entry.put("CRUD", crud);
            result.add(entry);
          }
        }

      }

      Path output = Paths.get(root.getPath(), "AuthorisationCaseType.json");
      JsonUtils.mergeInto(output, result, new JsonUtils.AddMissing(), "CaseTypeID", "UserRole");
    }
  }
}
