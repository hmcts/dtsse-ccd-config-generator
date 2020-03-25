package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
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

      ImmutableListMultimap<String, Map<String, Object>> entriesByCaseField = Multimaps
          .index(entries, x -> x.get("CaseFieldID").toString());

      if (entriesByCaseField.size() > 0) {
        File folder = new File(String
            .valueOf(Paths.get(root.getPath(), "CaseEventToComplexTypes", event.getEventID())));
        folder.mkdirs();
        for (String fieldID : entriesByCaseField.keySet()) {
          Path output = Paths.get(folder.getPath(), fieldID + event.getNamespace() + ".json");
          JsonUtils.mergeInto(output, entriesByCaseField.get(fieldID),
              "CaseEventID", "CaseFieldID", "ListElementCode");
        }
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
          Map<String, Object> data = Maps.newHashMap();
          entries.add(data);
          data.put("LiveFrom", "01/01/2017");
          data.put("CaseEventID", eventId);
          data.put("CaseFieldID", rfn);
          Field field = complexField.build();
          data.put("DisplayContext", field.getContext().toString().toUpperCase());
          data.put("ListElementCode", locator + field.getId());
          if (field.getLabel() != null) {
            data.put("EventElementLabel", field.getLabel());
          }
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
