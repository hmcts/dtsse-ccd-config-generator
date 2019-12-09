package ccd.sdk.generator;

import ccd.sdk.types.Event;
import ccd.sdk.types.Field;
import ccd.sdk.types.FieldCollection;
import com.google.common.collect.Lists;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class ComplexFieldGenerator {
    public static void writeEvents(File root, String caseType, List<Event> events) {
        for (Event event : events) {
            FieldCollection collection = event.getFields().build();
            List<Map<String, Object>> entries = Lists.newArrayList();
            FieldCollection.FieldCollectionBuilder complex = collection.getComplexFields();

            String rootFieldname = complex != null ? complex.build().getRootFieldname() : null;
            expand(complex, entries, event.getId(), rootFieldname, "", 1);
            if (entries.size() > 0) {
                File folder = new File(String.valueOf(Paths.get(root.getPath(), "CaseEventToComplexTypes", event.getId())));
                folder.mkdirs();
                Path output = Paths.get(folder.getPath(), event.getId() + ".json");
                Utils.writeFile(output, Utils.serialise(entries));
            }
        }
    }
    private static void expand(FieldCollection.FieldCollectionBuilder complexFields, List<Map<String, Object>> entries, String eventId, String rootFieldname, String fieldLocator, int count) {
        if (null != complexFields) {
            FieldCollection complex = complexFields.build();
            List<Field.FieldBuilder> fields = complex.getFields();
            for (Field.FieldBuilder complexField : fields) {
                String id = eventId;
                id = id.substring(0, 1).toUpperCase() + id.substring(1);
                Field field = complexField.build();
                Map<String, Object> data = Utils.getField(id);
                entries.add(data);

                data.put("ID", id);
                data.put("CaseEventID", eventId);
                data.put("CaseFieldID", rootFieldname);
                data.put("DisplayContext", field.getContext().toString().toUpperCase());
                data.put("ListElementCode", fieldLocator + field.getId());
                data.put("EventElementLabel", field.getLabel());
                data.put("FieldDisplayOrder", count++);
                if (null != field.getHint()) {
                    data.put("HintText", field.getHint());
                }
                if (null != field.getShowCondition()) {
                    data.put("FieldShowCondition", field.getShowCondition());
                }
            }
            if (null != complex.getComplexFields()) {
                String nextPropertyName = complex.getComplexFields().build().getRootFieldname();
                String locator = fieldLocator.length() == 0 ? nextPropertyName + "." : fieldLocator + "." + nextPropertyName;
                expand(complex.getComplexFields(), entries, eventId, rootFieldname, locator, count);
            }
        }
    }
}
