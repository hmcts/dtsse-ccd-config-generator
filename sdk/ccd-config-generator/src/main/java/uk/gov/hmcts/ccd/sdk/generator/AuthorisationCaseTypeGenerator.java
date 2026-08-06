package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

@Component
class AuthorisationCaseTypeGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {

    Class<?> roleEnum = config.getRoleClass();
    List<Map<String, Object>> result = Lists.newArrayList();
    if (roleEnum.isEnum()) {
      for (Object enumConstant : roleEnum.getEnumConstants()) {
        if (enumConstant instanceof HasRole r) {
          // Add non case roles.
          if (!r.getRole().matches("\\[.+\\]")) {
            boolean shuttered =
                (config.isShutterService() || config.getShutterServiceForRoles().contains(r))
                    && !config.getShutterServiceExcludedRoles().contains(r);
            Map<String, Object> entry = JsonUtils.caseRow(config.getCaseType());
            entry.put("UserRole", r.getRole());
            entry.put("CRUD", shuttered ? "D" : r.getCaseTypePermissions());
            result.add(entry);
          }
        }

      }

      addGroupRolesGrantedOnEvents(config, result);

      Path output = Paths.get(root.getPath(), "AuthorisationCaseType.json");
      JsonUtils.mergeInto(output, result, new JsonUtils.AddMissing(), "CaseTypeID", "UserRole");
    }
  }

  /**
   * Roles granted on an event but not members of the case's role class — group roles referenced by
   * an access group — still need a case-type row. CCD checks the case type ACL before the event ACL
   * ({@code AccessControlServiceImpl.canAccessCaseTypeWithCriteria}), so without this the group role
   * gets AuthorisationCaseEvent and AuthorisationCaseField rows but is refused at the case-type gate,
   * and the access group grants nothing.
   *
   * <p>Permissions are the union of what the role was granted across all events, since the case-type
   * ACL has to be at least as permissive as the event ACLs it gates.
   */
  private void addGroupRolesGrantedOnEvents(
      ResolvedCCDConfig<T, S, R> config, List<Map<String, Object>> result) {
    Set<String> roleClassRoles = Arrays.stream(config.getRoleClass().getEnumConstants())
        .map(HasRole::getRole)
        .collect(Collectors.toSet());

    Map<String, Set<Permission>> grantedPermissions = new LinkedHashMap<>();
    for (Event<T, R, S> event : config.getEvents().values()) {
      event.getGrants().forEach((role, permission) -> {
        String name = role.getRole();
        if (!roleClassRoles.contains(name) && !name.matches("\\[.+\\]")) {
          grantedPermissions.computeIfAbsent(name, key -> EnumSet.noneOf(Permission.class))
              .add(permission);
        }
      });
    }

    grantedPermissions.forEach((role, permissions) -> {
      Map<String, Object> entry = JsonUtils.caseRow(config.getCaseType());
      entry.put("UserRole", role);
      entry.put("CRUD", Permission.toString(permissions));
      result.add(entry);
    });
  }
}
