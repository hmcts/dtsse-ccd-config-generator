package uk.gov.hmcts.ccd.sdk.generator;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getFieldId;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedField;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchField;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.TabField;

@Slf4j
@Component
class AuthorisationCaseFieldGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(
      File root, ResolvedCCDConfig<T, S, R> config) {

    Table<String, String, Set<Permission>> fieldRolePermissions = HashBasedTable.create();
    // Add field permissions based on event permissions.
    for (Event<T, R, S> event : config.getEvents().values()) {
      List<Field.FieldBuilder> fields = event.getFields().getFields();
      for (Field.FieldBuilder fb : fields) {

        for (R role : event.getGrants().keys()) {
          if (event.getHistoryOnlyRoles().contains(role)) {
            continue;
          }
          Set<Permission> perm = new HashSet<>(fb.build().isImmutable()
              ? Permission.CR
              : event.getGrants().get(role));
          if (!perm.contains(Permission.D) && fb.build().isMutableList()) {
            perm.add(Permission.D);
          }

          String id = fb.build().getId();

          // if explicit grants is enabled field permissions will be derived from ccd annotation on case field
          // and not the event permissions
          if (event.isExplicitGrants()) {
            // deal with immutable fields such as labels
            if (fb.build().isImmutable()) {
              if (fieldRolePermissions.contains(id, role.getRole())) {
                fieldRolePermissions.get(id, role.getRole()).addAll(perm);
              } else {
                fieldRolePermissions.put(id, role.getRole(), new HashSet<>(perm));
              }
            } else {
              fieldRolePermissions.put(id, role.getRole(), new HashSet<>());
            }
          } else {
            if (fieldRolePermissions.contains(id, role.getRole())) {
              fieldRolePermissions.get(id, role.getRole()).addAll(perm);
            } else {
              fieldRolePermissions.put(id, role.getRole(), new HashSet<>(perm));
            }
          }
        }
      }
    }
    SetMultimap<String, String> readWithheld = HashMultimap.create();
    Set<String> fieldsDeclaringAccess = config.isUnscopedTabsRespectDeclaredFieldAccess()
        ? fieldsDeclaringAccess(config.getCaseClass(), null)
        : Set.of();

    // Add Permissions for all tabs.
    for (String role : ImmutableSet.copyOf(fieldRolePermissions.columnKeySet())) {
      if (!config.getRolesWithNoHistory().contains(role)) {
        fieldRolePermissions.put("caseHistory", role, CRU);
      }

      // Add read for any tab fields
      for (Tab<T, R> tab : config.getTabs()) {
        // A field on a tab open to every role, which has named who may read it: adding read here
        // would widen that, and this runs before addPermissionsFromFields so the declaration
        // could not take it back. A tab naming roles is already scoped, so it still grants.
        boolean respectDeclared = tab.getForRoles().isEmpty();
        for (TabField field : tab.getFields()) {
          if (respectDeclared && fieldsDeclaringAccess.contains(field.getId())) {
            readWithheld.put(field.getId(), role);
            continue;
          }
          boolean giveReadPermission =
              tab.getForRolesAsString().contains(role) || tab.getForRoles().isEmpty();
          if (giveReadPermission && !fieldRolePermissions.contains(field.getId(), role)) {
            fieldRolePermissions.put(field.getId(), role, Collections.singleton(Permission.R));
          }
        }
      }

      // Add read for WorkBaskets
      for (Search<T, R> basket :
          Iterables.concat(config.getWorkBasketInputFields(),
              config.getWorkBasketResultFields())) {
        for (SearchField<R> searchField : basket.getFields()) {
          if (searchField.availableToRole(role) && !fieldRolePermissions.contains(searchField.getId(), role)) {
            fieldRolePermissions.put(searchField.getId(), role, Collections.singleton(Permission.R));
          }
        }
      }

      // Add read for Search Input fields
      for (Search<T, R> search :
          Iterables.concat(config.getSearchInputFields(),
              config.getSearchResultFields())) {
        for (SearchField<R> searchField : search.getFields()) {
          if (searchField.availableToRole(role) && !fieldRolePermissions.contains(searchField.getId(), role)) {
            fieldRolePermissions.put(searchField.getId(), role, Collections.singleton(Permission.R));
          }
        }
      }
    }

    addPermissionsFromFields(fieldRolePermissions, config.getCaseClass(), null, null);

    reportWithheldTabRead(readWithheld, fieldRolePermissions);

    reportWithheldTabRead(readWithheld, fieldRolePermissions);

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
        Set<Permission> fieldPermission = fieldPerm.getValue();
        if (!fieldPermission.isEmpty()) {
          // Don't export metadata fields.
          if (field.matches("\\[.+\\]")) {
            continue;
          }
          Map<String, Object> permission = JsonUtils.caseRow(config.getCaseType());
          permission.put("UserRole", role);
          permission.put("CaseFieldID", field);
          permission.put("CRUD", Permission.toString(fieldPermission));

          Optional<JsonUnwrapped> unwrapped = isUnwrappedField(config.getCaseClass(), field);

          if (unwrapped.isEmpty()) {
            permissions.add(permission);
          }
        }
      }

      String filename = role.replace("[", "").replace("]", "");
      Path output = Paths.get(folder.getPath(), filename + ".json");
      JsonUtils.mergeInto(output, permissions, new JsonUtils.AddMissing(), "CaseFieldID",
          "UserRole");
    }
  }

  /**
   * Says what {@code unscopedTabsRespectDeclaredFieldAccess()} took away, so adopting it is not blind: the
   * fields whose tab read was withheld, and separately any left readable by nobody, which means the
   * access classes they name grant no read at all.
   */
  private void reportWithheldTabRead(
      SetMultimap<String, String> readWithheld,
      Table<String, String, Set<Permission>> fieldRolePermissions
  ) {
    if (readWithheld.isEmpty()) {
      return;
    }
    List<String> unreadable = readWithheld.keySet().stream()
        .filter(field -> fieldRolePermissions.row(field).values().stream()
            .noneMatch(perms -> perms.contains(Permission.R)))
        .sorted()
        .collect(Collectors.toList());

    log.info("unscopedTabsRespectDeclaredFieldAccess withheld tab read on {} field(s): {}",
        readWithheld.keySet().size(), new TreeSet<>(readWithheld.keySet()));

    if (!unreadable.isEmpty()) {
      log.warn("unscopedTabsRespectDeclaredFieldAccess leaves {} field(s) readable by no role, so the"
          + " access classes they name grant no read: {}", unreadable.size(), unreadable);
    }
  }

  /**
   * Ids of the fields whose own {@code @CCD} names an access class. A {@code @JsonUnwrapped}
   * parent passes its access down to its children for the grants themselves, but a child that
   * names nothing has not said who may read it, so it is not listed here.
   */
  private static Set<String> fieldsDeclaringAccess(Class<?> parent, String prefix) {
    Set<String> declaring = new HashSet<>();
    for (java.lang.reflect.Field field : getCaseFields(parent)) {
      JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
      if (null != unwrapped) {
        String newPrefix = isNullOrEmpty(prefix)
            ? unwrapped.prefix() : prefix.concat(capitalize(unwrapped.prefix()));
        declaring.addAll(fieldsDeclaringAccess(field.getType(), newPrefix));
        continue;
      }
      CCD ccdAnnotation = field.getAnnotation(CCD.class);
      if (null != ccdAnnotation && ccdAnnotation.access().length > 0) {
        declaring.add(getFieldId(field, prefix));
      }
    }
    return declaring;
  }

  private static void addPermissionsFromFields(
      Table<String, String, Set<Permission>> fieldRolePermissions,
      Class parent,
      String prefix,
      Class<? extends HasAccessControl>[] defaultAccessControl
  ) {

    for (java.lang.reflect.Field field : getCaseFields(parent)) {
      CCD ccdAnnotation = field.getAnnotation(CCD.class);
      Class<? extends HasAccessControl>[] access = mergeAccess(defaultAccessControl, ccdAnnotation);
      JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);

      if (null != unwrapped) {
        String newPrefix = isNullOrEmpty(prefix) ? unwrapped.prefix() : prefix.concat(capitalize(unwrapped.prefix()));
        addPermissionsFromFields(fieldRolePermissions, field.getType(), newPrefix, access);
      } else if (null != access) {
        String id = getFieldId(field, prefix);

        for (Class<? extends HasAccessControl> klass : access) {
          HasAccessControl accessHolder = BeanUtils.instantiateClass(klass);
          SetMultimap<HasRole, Permission> roleGrants = accessHolder.getGrants();
          for (HasRole key : roleGrants.keys()) {
            Set<Permission> perms = Sets.newHashSet();
            perms.addAll(roleGrants.get(key));

            if (fieldRolePermissions.contains(id, key.getRole())) {
              perms.addAll(fieldRolePermissions.get(id, key.getRole()));
            }

            fieldRolePermissions.put(id, key.getRole(), perms);
          }
        }
      }
    }
  }

  private static Class<? extends HasAccessControl>[] mergeAccess(
      Class<? extends HasAccessControl>[] defaultAccessControl, CCD ccdAnnotation) {
    return null == ccdAnnotation || ccdAnnotation.access().length == 0
        ? defaultAccessControl
        : ccdAnnotation.inheritAccessFromParent()
            ? ArrayUtils.addAll(defaultAccessControl, ccdAnnotation.access())
            : ccdAnnotation.access();
  }
}
