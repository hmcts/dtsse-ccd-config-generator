package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.primitives.Chars;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchField;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.TabField;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket.WorkBasketBuilder;
import uk.gov.hmcts.ccd.sdk.api.WorkBasketField;

class AuthorisationCaseFieldGenerator {

  public static <T, S, R extends HasRole> void generate(
      File root, ResolvedCCDConfig<T, S, R> config, Table<String, R, String> eventRolePermissions) {

    Table<String, String, String> fieldRolePermissions = HashBasedTable.create();
    // Add field permissions based on event permissions.
    for (Event event : config.events) {
      Map<R, String> eventPermissions = eventRolePermissions.row(event.getEventID());
      List<Field.FieldBuilder> fields = event.getFields().build().getFields();
      for (Field.FieldBuilder fb : fields) {

        for (Entry<R, String> rolePermission : eventPermissions.entrySet()) {
          if (event.getHistoryOnlyRoles().contains(rolePermission.getKey())) {
            continue;
          }
          if (config.builder.stateRoleHistoryAccess.containsEntry(event.getPostState(),
              rolePermission.getKey())) {
            continue;
          }
          String perm = fb.build().isImmutable() ? "R" : rolePermission.getValue();
          if (!perm.contains("D") && fb.build().isMutableList()) {
            perm += "D";
          }
          if (fb.build().isImmutable() || fb.build().isImmutableList()) {
            perm = perm.replaceAll("C", "");
          }
          fieldRolePermissions.put(fb.build().getId(), rolePermission.getKey().getRole(),
              perm);
        }
      }
    }

    // Add Permissions for all tabs.
    for (String role : ImmutableSet.copyOf(fieldRolePermissions.columnKeySet())) {

      if (!config.builder.apiOnlyRoles.contains(role)) {
        fieldRolePermissions.put("caseHistory", role, "CRU");

        // Add read for any tab fields
        for (TabBuilder tb : config.builder.tabs) {
          Tab tab = tb.build();
          if (!tab.getExcludedRoles().contains(role)) {
            for (TabField field : tab.getFields()) {

              HasRole[] roles = tab.getFieldsExcludedByRole().get(field.getId());
              if (roles != null) {
                if (Arrays.stream(roles).anyMatch(x -> x.getRole().equals(role))) {
                  continue;
                }
              }
              if (!fieldRolePermissions.contains(field.getId(), role)) {
                fieldRolePermissions.put(field.getId(), role, "R");
              }
            }
          }
        }

        // Add read for WorkBaskets
        for (WorkBasketBuilder workBasketInputField :
            Iterables.concat(config.builder.workBasketInputFields,
                config.builder.workBasketResultFields)) {
          WorkBasket basket = workBasketInputField.build();
          for (WorkBasketField field : basket.getFields()) {
            if (!fieldRolePermissions.contains(field.getId(), role)) {
              fieldRolePermissions.put(field.getId(), role, "R");
            }
          }
        }

        // Add read for Search Input fields
        for (SearchBuilder searchInputField :
                Iterables.concat(config.builder.searchInputFields,
                    config.builder.searchResultFields)) {
          Search search = searchInputField.build();
          for (SearchField field : search.getFields()) {
            if (!fieldRolePermissions.contains(field.getId(), role)) {
              fieldRolePermissions.put(field.getId(), role, "R");
            }
          }
        }
      }
    }

    // Subtract any blacklisted permissions
    for (Event event : config.events) {
      for (FieldBuilder fb : (List<Field.FieldBuilder>) event.getFields().build().getFields()) {
        Field field = fb.build();
        Map<String, String> entries = field.getBlacklistedRolePermissions();
        for (Map.Entry<String, String> roleBlacklist : entries.entrySet()) {
          String perm = fieldRolePermissions.get(field.getId(), roleBlacklist.getKey());
          if (null != perm) {
            String regex = "[" + roleBlacklist.getValue() + "]";
            perm = perm.replaceAll(regex, "");
            fieldRolePermissions.put(field.getId(), roleBlacklist.getKey(), perm);
          }
        }
      }
    }

    // Plus explicit field blacklists.
    // TODO: refactor!
    for (FieldBuilder fb : config.builder.explicitFields) {
      Field field = fb.build();
      Map<String, String> entries = field.getBlacklistedRolePermissions();
      for (Map.Entry<String, String> roleBlacklist : entries.entrySet()) {
        String perm = fieldRolePermissions.get(field.getId(), roleBlacklist.getKey());
        if (null != perm) {
          String regex = "[" + roleBlacklist.getValue() + "]";
          perm = perm.replaceAll(regex, "");
          fieldRolePermissions.put(field.getId(), roleBlacklist.getKey(), perm);
        }
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseField");
    folder.mkdir();
    for (String role : fieldRolePermissions.columnKeySet()) {
      List<Map<String, Object>> permissions = Lists.newArrayList();
      Map<String, String> rolePermissions = fieldRolePermissions.column(role);

      for (Entry<String, String> fieldPerm : rolePermissions.entrySet()) {
        if (fieldPerm.getKey().equals("[STATE]")) {
          continue;
        }

        String field = fieldPerm.getKey();
        String inheritedPermission = getInheritedPermission(fieldRolePermissions,
            config.builder.roleHierarchy, role, field);
        String fieldPermission = fieldPerm.getValue();
        if (inheritedPermission != null) {
          Set<Character> newPermissions = Sets
              .newHashSet(Chars.asList(fieldPerm.getValue().toCharArray()));
          Set<Character> existingPermissions = Sets
              .newHashSet(Chars.asList(inheritedPermission.toCharArray()));
          newPermissions.removeAll(existingPermissions);
          fieldPermission = newPermissions.stream().map(String::valueOf)
              .collect(Collectors.joining());
        }
        if (!Strings.isNullOrEmpty(fieldPermission)) {
          // Don't export metadata fields.
          if (field.matches("\\[.+\\]")) {
            continue;
          }
          Map<String, Object> permission = new Hashtable<>();
          permissions.add(permission);
          permission.put("CaseTypeID", config.builder.caseType);
          permission.put("LiveFrom", "01/01/2017");
          permission.put("UserRole", role);
          permission.put("CaseFieldID", field);
          permission.put("CRUD", fieldPermission);
        }
      }

      String filename = role.replace("[", "").replace("]", "");
      Path output = Paths.get(folder.getPath(), filename + ".json");
      JsonUtils.mergeInto(output, permissions, new CRUDMerger(), "CaseFieldID", "UserRole");
    }
  }

  private static String getInheritedPermission(
      Table<String, String, String> fieldRolePermissions,
      Map<String, String> roleHierarchy, String role,
      String field) {
    if (roleHierarchy.containsKey(role)) {
      String parentRole = roleHierarchy.get(role);
      if (fieldRolePermissions.contains(field, parentRole)) {
        return fieldRolePermissions.get(field, parentRole);
      }
      return getInheritedPermission(fieldRolePermissions, roleHierarchy, parentRole, field);
    }
    return null;
  }
}
