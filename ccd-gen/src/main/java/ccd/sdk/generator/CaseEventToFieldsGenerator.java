package ccd.sdk.generator;

import ccd.sdk.types.DisplayContext;
import ccd.sdk.types.Event;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CaseEventToFieldsGenerator {
    public static void writeEvents(File root, String caseType, List<Event> events) {

        for (Event event : events) {
            if (event.getFields().size() > 0) {
                List<Map<String, Object>> entries = Lists.newArrayList();
                Map<String, DisplayContext> fields = event.getFields();
                for (String fieldName : fields.keySet()) {
                    Map<String, Object> info = Utils.getField("");
                    entries.add(info);
                    info.remove("ID");
                    info.put("CaseTypeID", "CARE_SUPERVISION_EPO");
                    info.put("CaseEventID", event.getId());
                    info.put("CaseFieldID", fieldName);
                    info.put("DisplayContext", fields.get(fieldName).toString().toUpperCase());
                    info.put("PageFieldDisplayOrder", 1);
                    info.put("PageID", 1);
                    info.put("PageDisplayOrder", 1);
                    info.put("PageColumnNumber", 1);
                }

                File folder = new File(root.getPath(), "CaseEventToFields");
                folder.mkdir();

                Path output = Paths.get(folder.getPath(), event.getId() + ".json");
                Utils.writeFile(output, Utils.serialise(entries));
            }
        }
    }
}
