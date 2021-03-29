package uk.gov.hmcts.ccd.sdk;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.JsonUtils.CRUDMerger;
import uk.gov.hmcts.ccd.sdk.api.Access;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
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
      File root, ResolvedCCDConfig<T, S, R> config, Table<String, R,
      Set<Permission>> eventRolePermissions) {

    Table<String, String, Set<Permission>> fieldRolePermissions = HashBasedTable.create();
    // Add field permissions based on event permissions.
    for (Event event : config.events) {
      Map<R, Set<Permission>> eventPermissions = eventRolePermissions.row(event.getEventID());
      List<Field.FieldBuilder> fields = event.getFields().build().getFields();
      for (Field.FieldBuilder fb : fields) {

        for (Entry<R, Set<Permission>> rolePermission : eventPermissions.entrySet()) {
          if (event.getHistoryOnlyRoles().contains(rolePermission.getKey())) {
            continue;
          }
          if (config.builder.stateRoleHistoryAccess.containsEntry(event.getPostState(),
              rolePermission.getKey())) {
            continue;
          }
          Set<Permission> perm = fb.build().isImmutable()
              ? Collections.singleton(Permission.R)
              : rolePermission.getValue();
          if (!perm.contains(Permission.D) && fb.build().isMutableList()) {
            perm.add(Permission.D);
          }
          if (fb.build().isImmutable() || fb.build().isImmutableList()) {
            perm.remove(Permission.C);
          }
          fieldRolePermissions.put(fb.build().getId(), rolePermission.getKey().getRole(),
              perm);
        }
      }
    }

    // Add Permissions for all tabs.
    for (String role : ImmutableSet.copyOf(fieldRolePermissions.columnKeySet())) {

      if (!config.builder.apiOnlyRoles.contains(role)) {
        fieldRolePermissions.put("caseHistory", role, CRU);

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
                fieldRolePermissions.put(field.getId(), role, Collections.singleton(Permission.R));
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
              fieldRolePermissions.put(field.getId(), role, Collections.singleton(Permission.R));
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
              fieldRolePermissions.put(field.getId(), role, Collections.singleton(Permission.R));
            }
          }
        }
      }
    }

    // Add permissions added to the model with @Access annotation
    for (java.lang.reflect.Field fieldWithAccess : ReflectionUtils.getAllFields(config.typeArg)) {
      Access access = fieldWithAccess.getAnnotation(Access.class);
      if (null != access) {
        JsonProperty j = fieldWithAccess.getAnnotation(JsonProperty.class);
        String id = j != null ? j.value() : fieldWithAccess.getName();

        Objenesis objenesis = new ObjenesisStd();
        HasAccessControl<HasRole> accessHolder = objenesis.newInstance(access.value());
        SetMultimap<HasRole, Permission> roleGrants = accessHolder.getGrants();

        for (HasRole key : roleGrants.keys()) {
          fieldRolePermissions.put(id, key.getRole(), roleGrants.get(key));
        }
      }
    }

    // Subtract any blacklisted permissions
    for (Event<T, R, S> event : config.events) {
      for (FieldBuilder fb : event.getFields().build().getFields()) {
        Field field = fb.build();
        Map<String, Set<Permission>> entries = field.getBlacklistedRolePermissions();
        for (Entry<String, Set<Permission>> roleBlacklist : entries.entrySet()) {
          Set<Permission> perm = fieldRolePermissions.get(field.getId(),
              roleBlacklist.getKey());
          if (null != perm) {
            perm.removeAll(roleBlacklist.getValue());
            fieldRolePermissions.put(field.getId(), roleBlacklist.getKey(), perm);
          }
        }
      }
    }

    // Plus explicit field blacklists.
    // TODO: refactor!
    for (FieldBuilder fb : config.builder.explicitFields) {
      Field field = fb.build();
      Map<String, Set<Permission>> entries = field.getBlacklistedRolePermissions();
      for (Entry<String, Set<Permission>> roleBlacklist : entries.entrySet()) {
        Set<Permission> perm = fieldRolePermissions.get(field.getId(),
            roleBlacklist.getKey());
        if (null != perm) {
          perm.removeAll(roleBlacklist.getValue());
          fieldRolePermissions.put(field.getId(), roleBlacklist.getKey(), perm);
        }
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseField");
    folder.mkdir();
    for (String role : fieldRolePermissions.columnKeySet()) {
      List<Map<String, Object>> permissions = Lists.newArrayList();
      Map<String, Set<Permission>> rolePermissions = fieldRolePermissions.column(role);

      for (Entry<String, Set<Permission>> fieldPerm : rolePermissions.entrySet()) {
        if (fieldPerm.getKey().equals("[STATE]")) {
          continue;
        }

        String field = fieldPerm.getKey();
        Set<Permission> inheritedPermission = getInheritedPermission(fieldRolePermissions,
            config.builder.roleHierarchy, role, field);
        Set<Permission> fieldPermission = fieldPerm.getValue();
        if (inheritedPermission != null) {
          Set<Permission> newPermissions = Sets.newHashSet(fieldPerm.getValue());
          newPermissions.removeAll(inheritedPermission);
          fieldPermission = newPermissions;
        }
        if (!fieldPermission.isEmpty()) {
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
          permission.put("CRUD", Permission.toString(fieldPermission));
        }
      }

      String filename = role.replace("[", "").replace("]", "");
      Path output = Paths.get(folder.getPath(), filename + ".json");
      JsonUtils.mergeInto(output, permissions, new CRUDMerger(), "CaseFieldID",
          "UserRole");
    }
  }

  private static Set<Permission> getInheritedPermission(
      Table<String, String, Set<Permission>> fieldRolePermissions,
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
