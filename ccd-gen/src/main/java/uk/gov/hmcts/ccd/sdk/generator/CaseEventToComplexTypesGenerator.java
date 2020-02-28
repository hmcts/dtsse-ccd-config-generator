package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.Utils;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Field;
import uk.gov.hmcts.ccd.sdk.types.FieldCollection;

public class CaseEventToComplexTypesGenerator {

  public static void writeEvents(File root, List<Event> events) {
    for (Event event : events) {
      FieldCollection collection = event.getFields().build();
      List<Map<String, Object>> entries = Lists.newArrayList();
      List<FieldCollection.FieldCollectionBuilder> complexFields = collection.getComplexFields();
      expand(complexFields, entries, event.getId(), null, "");
      if (entries.size() > 0) {
        File folder = new File(String
            .valueOf(Paths.get(root.getPath(), "CaseEventToComplexTypes", event.getEventID())));
        folder.mkdirs();
        Path output = Paths.get(folder.getPath(), event.getId() + ".json");
        Utils.mergeInto(output, entries, "CaseEventID", "CaseFieldID", "ListElementCode");
      }
    }
  }

  private static void expand(List<FieldCollection.FieldCollectionBuilder> complexFieldsCollection,
      List<Map<String, Object>> entries, String eventId, final String rootFieldName,
      final String fieldLocator) {
    if (null != complexFieldsCollection) {
      for (FieldCollection.FieldCollectionBuilder complexFields : complexFieldsCollection) {
        FieldCollection complex = complexFields.build();
        String rfn = rootFieldName;
        String locator = fieldLocator;
        if (null == rootFieldName) {
          // This is a root complex
          rfn = complex.getRootFieldname();
        } else {
          // This is a nested complex
          locator += complex.getRootFieldname() + ".";
        }
        List<Field.FieldBuilder> fields = complex.getFields();
        for (Field.FieldBuilder complexField : fields) {
          String id = eventId;
          id = id.substring(0, 1).toUpperCase() + id.substring(1);
          Map<String, Object> data = Utils.getField(id);
          entries.add(data);

          data.put("ID", id);
          data.put("CaseEventID", eventId);
          data.put("CaseFieldID", rfn);
          Field field = complexField.build();
          data.put("DisplayContext", field.getContext().toString().toUpperCase());
          data.put("ListElementCode", locator + field.getId());
          data.put("EventElementLabel", field.getLabel());
          data.put("FieldDisplayOrder", field.getFieldDisplayOrder());
          if (null != field.getHint()) {
            data.put("HintText", field.getHint());
          }
          if (null != field.getShowCondition()) {
            data.put("FieldShowCondition", field.getShowCondition());
          }
        }
        if (null != complex.getComplexFields()) {
          expand(complex.getComplexFields(), entries, eventId, rfn, locator);
        }
      }
    }
  }
}
