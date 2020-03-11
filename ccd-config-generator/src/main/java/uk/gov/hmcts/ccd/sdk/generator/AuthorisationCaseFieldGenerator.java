package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Field;

public class AuthorisationCaseFieldGenerator {

  public static void generate(File root, String caseType, List<Event> events,
      Table<String, String, String> eventRolePermissions) {

    Table<String, String, String> fieldPermissions = HashBasedTable.create();
    for (Event event : events) {
      Map<String, String> eventPermissions = eventRolePermissions.row(event.getEventID());
      List<Field.FieldBuilder> fields = event.getFields().build().getFields();
      for (Field.FieldBuilder fb : fields) {

        for (Entry<String, String> rolePermission : eventPermissions.entrySet()) {
          fieldPermissions.put(fb.build().getId(), rolePermission.getKey(),
              rolePermission.getValue());
        }
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseField");
    folder.mkdir();
    for (String role : fieldPermissions.columnKeySet()) {

      List<Map<String, Object>> permissions = Lists.newArrayList();
      Map<String, String> perms = fieldPermissions.column(role);
      for (Entry<String, String> fieldPerm : perms.entrySet()) {

        Map<String, Object> permission = Maps.newHashMap();
        permissions.add(permission);
        permission.put("CaseTypeID", caseType);
        permission.put("LiveFrom", "01/01/2017");
        permission.put("UserRole", role);
        permission.put("CaseFieldID", fieldPerm.getKey());
        permission.put("CRUD", fieldPerm.getValue());
      }

      Path output = Paths.get(folder.getPath(), role + ".json");
      JsonUtils.mergeInto(output, permissions, "CaseFieldID", "UserRole");
    }
  }
}
