package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.common.primitives.Chars;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

class AuthorisationCaseStateGenerator {

  public static <T, S, R extends HasRole> void generate(
      File root, ResolvedCCDConfig<T, S, R> config, Table<String, R, String> eventRolePermissions) {

    Table<S, R, String> stateRolePermissions = HashBasedTable.create();
    for (Event<T, R, S> event : config.events) {
      if (event.getPreState().equals(config.allStates)) {
        continue;
      }

      Map<R, String> rolePermissions = eventRolePermissions.row(event.getEventID());
      for (Entry<R, String> rolePermission : rolePermissions.entrySet()) {
        // For state transitions if you have C then you get both states.
        // Otherwise you only need permission for the destination state.
        if (event.getPreState() != event.getPostState()) {
          if (rolePermission.getValue().contains("C") && !event.getPreState().isEmpty()) {
            addPermissions(stateRolePermissions, event.getPreState(), rolePermission.getKey(),
                rolePermission.getValue());
            // They get R only on the destination state.
            addPermissions(stateRolePermissions, event.getPostState(), rolePermission.getKey(),
                "R");
          }
        } else {
          addPermissions(stateRolePermissions, event.getPostState(), rolePermission.getKey(),
              rolePermission.getValue());
        }
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (Cell<S, R, String> stateRolePermission : stateRolePermissions.cellSet()) {
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
      List<Character> perm = Chars.asList(stateRolePermission.getValue().toCharArray());
      Collections.sort(perm, Ordering.explicit('C', 'R', 'U', 'D'));
      permission.put("CRUD", perm.stream().map(String::valueOf).collect(Collectors.joining()));
    }

    Path output = Paths.get(root.getPath(), "AuthorisationCaseState.json");
    JsonUtils.mergeInto(output, result, new CRUDMerger(), "CaseStateID", "UserRole");

  }

  private static <R extends HasRole, S> void addPermissions(
      Table<S, R, String> stateRolePermissions, Set<S> states, R role, String permissions) {
    for (S state : states) {
      String existingPermissions = stateRolePermissions.get(state, role);
      existingPermissions = existingPermissions == null ? "" : existingPermissions;
      existingPermissions += permissions;
      // Remove any dupes.
      existingPermissions = Sets.newHashSet(Chars.asList(existingPermissions.toCharArray()))
          .stream().map(String::valueOf).collect(Collectors.joining());

      stateRolePermissions.put(state, role, existingPermissions);
    }
  }
}
