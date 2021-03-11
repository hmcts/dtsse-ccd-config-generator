package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

class AuthorisationCaseEventGenerator {

  public static <R extends HasRole> void generate(
      File root, Table<String, R, Set<Permission>> eventRolePermissions, String caseType) {
    List<Map<String, Object>> entries = Lists.newArrayList();

    for (Table.Cell<String, R, Set<Permission>> cell : eventRolePermissions.cellSet()) {
      if (!cell.getValue().isEmpty()) {
        Map<String, Object> entry = Maps.newHashMap();
        entries.add(entry);
        entry.put("LiveFrom", "01/01/2017");
        entry.put("CaseTypeID", caseType);
        entry.put("CaseEventID", cell.getRowKey());
        entry.put("UserRole", cell.getColumnKey().getRole());
        entry.put("CRUD", Permission.toString(cell.getValue()));
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseEvent");
    folder.mkdir();

    Path output = Paths.get(folder.getPath(), "AuthorisationCaseEvent.json");
    JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseEventID", "UserRole");
  }
}
