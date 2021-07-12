package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

@Component
class AuthorisationCaseStateGenerator<T, S, R extends HasRole> implements ConfigWriter<T, S, R> {

  @SneakyThrows
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {

    for (Event<T, R, S> event : config.events) {
      if (event.getPreState().equals(config.allStates)) {
        continue;
      }

      SetMultimap<R, Permission> grants = event.getGrants();
      for (R role : event.getGrants().keys()) {
        // For state transitions if you have C then you get both states.
        // Otherwise you only need permission for the destination state.
        if (event.getPreState() != event.getPostState()) {
          if (grants.get(role).contains(Permission.C) && !event.getPreState().isEmpty()) {
            addPermissions(config.builder.stateRolePermissions, event.getPreState(), role,
                grants.get(role));
            // They get R only on the destination state.
            addPermissions(config.builder.stateRolePermissions, event.getPostState(), role,
                Collections.singleton(Permission.R));
          }
        } else {
          addPermissions(config.builder.stateRolePermissions, event.getPostState(), role,
              grants.get(role));
        }
      }
    }

    Objenesis objenesis = new ObjenesisStd();
    for (S state : config.stateArg.getEnumConstants()) {
      String enumFieldName = ((Enum)state).name();
      CCD ccd = config.stateArg.getField(enumFieldName).getAnnotation(CCD.class);

      if (null != ccd) {
        for (var klass : ccd.access()) {
          HasAccessControl accessHolder = objenesis.newInstance(klass);
          SetMultimap<HasRole, Permission> roleGrants = accessHolder.getGrants();
          for (HasRole key : roleGrants.keys()) {
            addPermissions(config.builder.stateRolePermissions, Set.of(state), (R)key, roleGrants.get(key));
          }
        }
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (Cell<S, R, Set<Permission>> stateRolePermission : config.builder.stateRolePermissions.cellSet()) {
      if (stateRolePermission.getColumnKey().toString().matches("\\[.*?\\]")) {
        // Ignore CCD roles.
        continue;
      }
      Map<String, Object> permission = Maps.newHashMap();
      result.add(permission);
      permission.put("CaseTypeID", config.builder.caseType);
      permission.put("LiveFrom", "01/01/2017");
      permission.put("CaseStateID", stateRolePermission.getRowKey());
      permission.put("UserRole", stateRolePermission.getColumnKey().getRole());
      permission.put("CRUD", Permission.toString(stateRolePermission.getValue()));
    }

    Path output = Paths.get(root.getPath(), "AuthorisationCaseState.json");
    JsonUtils.mergeInto(output, result, new CRUDMerger(), "CaseStateID", "UserRole");
  }

  private static <R extends HasRole, S> void addPermissions(
      Table<S, R, Set<Permission>> stateRolePermissions, Set<S> states, R role,
      Set<Permission> permissions) {
    for (S state : states) {
      Set<Permission> existingPermissions = stateRolePermissions.get(state, role);
      existingPermissions = existingPermissions == null
          ? Sets.newHashSet()
          : Sets.newHashSet(existingPermissions);
      existingPermissions.addAll(permissions);

      stateRolePermissions.put(state, role, existingPermissions);
    }
  }
}
