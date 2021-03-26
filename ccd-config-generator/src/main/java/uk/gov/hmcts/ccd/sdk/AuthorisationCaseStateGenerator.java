package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasCaseRole;
import uk.gov.hmcts.ccd.sdk.api.HasCaseTypePerm;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

class AuthorisationCaseStateGenerator {

  public static <T, S, R extends HasCaseTypePerm, C extends HasCaseRole> void generate(
      File root, ResolvedCCDConfig<T, S, R, C> config, Table<String, R,
      Set<Permission>> eventRolePermissions) {

    Table<S, R, Set<Permission>> stateRolePermissions = HashBasedTable.create();
    for (Event<T, R, S> event : config.events) {
      if (event.getPreState().equals(config.allStates)) {
        continue;
      }

      Map<R, Set<Permission>> rolePermissions = eventRolePermissions.row(event.getEventID());
      for (Entry<R, Set<Permission>> rolePermission : rolePermissions.entrySet()) {
        // For state transitions if you have C then you get both states.
        // Otherwise you only need permission for the destination state.
        if (event.getPreState() != event.getPostState()) {
          if (rolePermission.getValue().contains(Permission.C) && !event.getPreState().isEmpty()) {
            addPermissions(stateRolePermissions, event.getPreState(), rolePermission.getKey(),
                rolePermission.getValue());
            // They get R only on the destination state.
            addPermissions(stateRolePermissions, event.getPostState(), rolePermission.getKey(),
                Collections.singleton(Permission.R));
          }
        } else {
          addPermissions(stateRolePermissions, event.getPostState(), rolePermission.getKey(),
              rolePermission.getValue());
        }
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (Cell<S, R, Set<Permission>> stateRolePermission : stateRolePermissions.cellSet()) {
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
