package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.types.HasRole;

public class AuthorisationCaseEventGenerator {

  public static <T, S, R extends HasRole> void generate(File root,
      Table<String, String, String> eventRolePermissions,
      String caseType) {
    List<Map<String, Object>> entries = Lists.newArrayList();

    for (Table.Cell<String, String, String> cell : eventRolePermissions.cellSet()) {
      if (cell.getValue().length() > 0) {
        Map<String, Object> entry = Maps.newHashMap();
        entries.add(entry);
        entry.put("LiveFrom", "01/01/2017");
        entry.put("CaseTypeID", caseType);
        entry.put("CaseEventID", cell.getRowKey());
        entry.put("UserRole", cell.getColumnKey());
        entry.put("CRUD", cell.getValue());
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseEvent");
    folder.mkdir();

    Path output = Paths.get(folder.getPath(), "AuthorisationCaseEvent.json");
    JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseEventID", "UserRole");
  }
}
