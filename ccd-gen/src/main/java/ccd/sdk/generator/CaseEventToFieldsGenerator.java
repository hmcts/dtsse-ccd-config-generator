package ccd.sdk.generator;

import ccd.sdk.Utils;
import ccd.sdk.types.Event;
import ccd.sdk.types.Field;
import ccd.sdk.types.FieldCollection;
import com.google.common.collect.Lists;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class CaseEventToFieldsGenerator {
    public static void writeEvents(File root, List<Event> events) {

        for (Event event : events) {
            FieldCollection collection = event.getFields().build();
            if (collection.getFields().size() > 0) {
                List<Map<String, Object>> entries = Lists.newArrayList();
                List<Field.FieldBuilder> fields = collection.getFields();
                boolean first = true;
                for (Field.FieldBuilder fb : fields) {
                    Field field = fb.build();
                    Map<String, Object> info = Utils.getField("");
                    entries.add(info);
                    info.remove("ID");
                    info.put("CaseTypeID", "CARE_SUPERVISION_EPO");
                    info.put("CaseEventID", event.getId());
                    info.put("CaseFieldID", field.getId());
                    String context = field.getContext() == null ? "COMPLEX" : field.getContext().toString().toUpperCase();
                    info.put("DisplayContext", context);
                    info.put("PageFieldDisplayOrder", field.getPageFieldDisplayOrder());
                    info.put("PageID", field.getPage() == null ? 1 : field.getPage());
                    info.put("PageDisplayOrder", field.getPageDisplayOrder());
                    info.put("PageColumnNumber", 1);
                    if (field.getShowCondition() != null) {
                        info.put("FieldShowCondition", field.getShowCondition());
                    }

                    if (first && event.getMidEventURL() != null) {
                        info.put("CallBackURLMidEvent", "${CCD_DEF_CASE_SERVICE_BASE_URL}/callback" + event.getMidEventURL());
                        first = false;
                    }

                    if (field.isShowSummary()) {
                        info.put("ShowSummaryChangeOption", "Y");
                    }

                    if (field.getPageLabel() != null) {
                        info.put("PageLabel", field.getPageLabel());
                    }
                }

                File folder = new File(root.getPath(), "CaseEventToFields");
                folder.mkdir();

                Path output = Paths.get(folder.getPath(), event.getId() + ".json");
                Utils.writeFile(output, Utils.serialise(entries));
            }
        }
    }
}
