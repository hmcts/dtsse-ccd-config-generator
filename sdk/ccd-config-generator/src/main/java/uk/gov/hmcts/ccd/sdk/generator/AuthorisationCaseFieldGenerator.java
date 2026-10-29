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
import com.google.common.collect.Multimaps;
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
import java.util.TreeMap;
import java.util.function.BiConsumer;
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
    Set<String> fieldsDeclaringAccess = config.isTabsRespectDeclaredFieldAccess()
        ? fieldsDeclaringAccess(config.getCaseClass())
        : Set.of();

    // Add Permissions for all tabs.
    for (String role : ImmutableSet.copyOf(fieldRolePermissions.columnKeySet())) {
      if (!config.getRolesWithNoHistory().contains(role)) {
        fieldRolePermissions.put("caseHistory", role, CRU);
      }

      // Add read for any tab fields
      for (Tab<T, R> tab : config.getTabs()) {
        for (TabField field : tab.getFields()) {
          boolean giveReadPermission =
              tab.getForRolesAsString().contains(role) || tab.getForRoles().isEmpty();
          if (!giveReadPermission || fieldRolePermissions.contains(field.getId(), role)) {
            continue;
          }
          // omitHistoryForRoles is the declaration for caseHistory: CaseFieldGenerator synthesises
          // that field, so it can carry no @CCD(access), and a History tab naming no roles would
          // otherwise hand a role the read the declaration withheld from it.
          if (config.isTabsRespectDeclaredFieldAccess()
              && "caseHistory".equals(field.getId())
              && config.getRolesWithNoHistory().contains(role)) {
            readWithheld.put(field.getId(), role);
            continue;
          }
          // A field @CCD(access) authorises has named who may read it. Adding read here would
          // widen that, and this runs before addPermissionsFromFields, so the declaration could
          // not take it back.
          if (fieldsDeclaringAccess.contains(field.getId())) {
            readWithheld.put(field.getId(), role);
            continue;
          }
          fieldRolePermissions.put(field.getId(), role, Collections.singleton(Permission.R));
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

    addPermissionsFromFields(fieldRolePermissions, config.getCaseClass());

    reportWithheldTabRead(config, readWithheld, fieldRolePermissions);

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
   * Says what {@code tabsRespectDeclaredFieldAccess()} did, so adopting it is not blind: the read
   * it withheld and from which roles, and any field left readable by nobody, which means the access
   * classes it names grant no read at all.
   */
  private void reportWithheldTabRead(
      ResolvedCCDConfig<T, S, R> config,
      SetMultimap<String, String> readWithheld,
      Table<String, String, Set<Permission>> fieldRolePermissions
  ) {
    String caseType = config.getCaseType();
    // Only where the role ends with no read: a role the declaration grants anyway lost nothing.
    SetMultimap<String, String> lostRead = HashMultimap.create();
    readWithheld.forEach((field, role) -> {
      Set<Permission> granted = fieldRolePermissions.get(field, role);
      if (granted == null || !granted.contains(Permission.R)) {
        lostRead.put(field, role);
      }
    });

    if (lostRead.isEmpty()) {
      return;
    }

    if (log.isInfoEnabled()) {
      log.info("{}: tabsRespectDeclaredFieldAccess withheld tab read on {} field(s): {}",
          caseType, lostRead.keySet().size(), sorted(lostRead));
    }

    List<String> unreadable = lostRead.keySet().stream()
        .filter(field -> fieldRolePermissions.row(field).values().stream()
            .noneMatch(perms -> perms.contains(Permission.R)))
        .sorted()
        .collect(Collectors.toList());

    if (!unreadable.isEmpty()) {
      log.warn("{}: tabsRespectDeclaredFieldAccess leaves {} field(s) readable by no role, so the"
          + " access classes they name grant no read: {}", caseType, unreadable.size(), unreadable);
    }

    // A role left able to write a field it cannot read gets an event page with a field CCD strips
    // from the case data it returns.
    SetMultimap<String, String> writeWithoutRead = HashMultimap.create();
    lostRead.forEach((field, role) -> {
      Set<Permission> granted = fieldRolePermissions.get(field, role);
      if (null != granted && !granted.isEmpty()) {
        writeWithoutRead.put(field, role);
      }
    });

    if (!writeWithoutRead.isEmpty()) {
      log.warn("{}: tabsRespectDeclaredFieldAccess leaves {} field(s) granted to a role that"
          + " cannot read them: {}",
          caseType, writeWithoutRead.keySet().size(), sorted(writeWithoutRead));
    }

    reportTabsLeftEmpty(config, readWithheld, fieldRolePermissions);
  }

  /** Field to roles, ordered, so a report can be diffed between generations. */
  private static Map<String, List<String>> sorted(SetMultimap<String, String> fieldRoles) {
    Map<String, List<String>> ordered = new TreeMap<>();
    Multimaps.asMap(fieldRoles).forEach((field, roles) ->
        ordered.put(field, roles.stream().sorted().collect(Collectors.toList())));
    return ordered;
  }

  /**
   * Warns where a tab declaring {@code forRoles(...)} is left with nothing that role can read by
   * itself. The tab is still emitted and still shown to that role, and neither the tab nor the
   * fields on it say so. CCD unions a user's access profiles, so a user holding another role that
   * reads those fields still sees them; this reports the role the tab names, which is the only one
   * the definition says the tab is for.
   */
  private void reportTabsLeftEmpty(
      ResolvedCCDConfig<T, S, R> config,
      SetMultimap<String, String> readWithheld,
      Table<String, String, Set<Permission>> fieldRolePermissions
  ) {
    for (Tab<T, R> tab : config.getTabs()) {
      for (String role : tab.getForRolesAsString()) {
        boolean withheldOnThisTab = tab.getFields().stream()
            .anyMatch(field -> readWithheld.containsEntry(field.getId(), role));
        if (!withheldOnThisTab) {
          continue;
        }
        // Labels take read from the tab and always will, so a tab whose every case field is
        // withheld still shows that role its section headers and nothing else.
        boolean anyReadable = tab.getFields().stream()
            .filter(field -> null == field.getLabel())
            .anyMatch(field -> {
              Set<Permission> granted = fieldRolePermissions.get(field.getId(), role);
              return null != granted && granted.contains(Permission.R);
            });
        if (!anyReadable) {
          log.warn("{}: tab {} is declared for {}, and after tabsRespectDeclaredFieldAccess no"
              + " field on it is readable by that role alone, so it renders empty for a user"
              + " holding no other role that reads them",
              config.getCaseType(), tab.getTabID(), role);
        }
      }
    }
  }

  /**
   * Hands each field the id CCD knows it by and the access classes that apply to it, a
   * {@code @JsonUnwrapped} parent's passed down to its children. Both the grants and the decision
   * to withhold tab read are taken from this one walk, so they cannot disagree about which fields
   * {@code @CCD(access)} authorises.
   */
  private static void walkCaseFields(
      Class<?> parent,
      String prefix,
      Class<? extends HasAccessControl>[] defaultAccessControl,
      BiConsumer<String, Class<? extends HasAccessControl>[]> sink
  ) {
    for (java.lang.reflect.Field field : getCaseFields(parent)) {
      CCD ccdAnnotation = field.getAnnotation(CCD.class);
      Class<? extends HasAccessControl>[] access = mergeAccess(defaultAccessControl, ccdAnnotation);
      JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);

      if (null != unwrapped) {
        String newPrefix = isNullOrEmpty(prefix) ? unwrapped.prefix() : prefix.concat(capitalize(unwrapped.prefix()));
        walkCaseFields(field.getType(), newPrefix, access, sink);
      } else if (null != access) {
        sink.accept(getFieldId(field, prefix), access);
      }
    }
  }

  private static Set<String> fieldsDeclaringAccess(Class<?> caseClass) {
    Set<String> declaring = new HashSet<>();
    walkCaseFields(caseClass, null, null, (id, access) -> {
      if (access.length > 0) {
        declaring.add(id);
      }
    });
    return declaring;
  }

  private static void addPermissionsFromFields(
      Table<String, String, Set<Permission>> fieldRolePermissions,
      Class<?> caseClass
  ) {
    walkCaseFields(caseClass, null, null, (id, access) -> {
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
    });
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
