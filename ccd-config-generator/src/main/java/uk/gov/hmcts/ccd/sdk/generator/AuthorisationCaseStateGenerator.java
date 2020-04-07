package uk.gov.hmcts.ccd.sdk.generator;

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
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.types.Event;

public class AuthorisationCaseStateGenerator {

  public static void generate(File root, String caseType, List<Event> events,
      Table<String, String, String> eventRolePermissions) {

    Table<String, String, String> stateRolePermissions = HashBasedTable.create();
    for (Event event : events) {
      String state = event.getPreState() == null ? event.getPostState() : event.getPreState();
      Map<String, String> rolePermissions = eventRolePermissions.row(event.getEventID());
      for (Entry<String, String> rolePermission : rolePermissions.entrySet()) {
        // For state transitions if you have C then you get both states.
        // Otherwise you only need permission for the destination state.
        if (event.getPreState() != event.getPostState()) {
          if (rolePermission.getValue().contains("C") && event.getPreState() != null) {
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
    for (Cell<String, String, String> stateRolePermission : stateRolePermissions.cellSet()) {
      if (stateRolePermission.getRowKey().equals("*")) {
        continue;
      }
      if (stateRolePermission.getColumnKey().matches("\\[.*?\\]")) {
        // Ignore CCD roles.
        continue;
      }
      Map<String, Object> permission = Maps.newHashMap();
      result.add(permission);
      permission.put("CaseTypeID", caseType);
      permission.put("LiveFrom", "01/01/2017");
      permission.put("CaseStateID", stateRolePermission.getRowKey());
      permission.put("UserRole", stateRolePermission.getColumnKey());
      List<Character> perm = Chars.asList(stateRolePermission.getValue().toCharArray());
      Collections.sort(perm, Ordering.explicit('C', 'R', 'U', 'D'));
      permission.put("CRUD", perm.stream().map(String::valueOf).collect(Collectors.joining()));
    }

    Path output = Paths.get(root.getPath(), "AuthorisationCaseState.json");
    JsonUtils.mergeInto(output, result, new CRUDMerger(), "CaseStateID", "UserRole");

  }

  private static void addPermissions(Table<String, String, String> stateRolePermissions,
      String state, String role, String permissions) {
    String existingPermissions = stateRolePermissions.get(state, role);
    existingPermissions = existingPermissions == null ? "" : existingPermissions;
    existingPermissions += permissions;
    // Remove any dupes.
    existingPermissions = Sets.newHashSet(Chars.asList(existingPermissions.toCharArray()))
        .stream().map(String::valueOf).collect(Collectors.joining());

    if (existingPermissions != null) {
      stateRolePermissions.put(state, role, existingPermissions);
    }
  }

}
