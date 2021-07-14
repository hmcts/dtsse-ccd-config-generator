package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class AuthorisationCaseEventGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> entries = Lists.newArrayList();

    for (Event<T, R, S> event : config.getEvents().values()) {
      for (R role : event.getGrants().keys()) {
        Map<String, Object> entry = Maps.newHashMap();
        entries.add(entry);
        entry.put("LiveFrom", "01/01/2017");
        entry.put("CaseTypeID", config.getCaseType());
        entry.put("CaseEventID", event.getId());
        entry.put("UserRole", role.getRole());
        entry.put("CRUD", Permission.toString(event.getGrants().get(role)));
      }
    }

    File folder = new File(root.getPath(), "AuthorisationCaseEvent");
    folder.mkdir();

    Path output = Paths.get(folder.getPath(), "AuthorisationCaseEvent.json");
    JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseEventID", "UserRole");
  }
}
