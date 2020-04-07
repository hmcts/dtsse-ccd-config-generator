package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.types.Event;
import uk.gov.hmcts.ccd.sdk.types.Field;
import uk.gov.hmcts.ccd.sdk.types.FieldCollection;

public class CaseEventToFieldsGenerator {

  public static void writeEvents(File root, List<Event> events) {

    for (Event event : events) {
      FieldCollection collection = event.getFields().build();
      if (collection.getFields().size() > 0) {
        List<Map<String, Object>> entries = Lists.newArrayList();
        List<Field.FieldBuilder> fields = collection.getFields();
        for (Field.FieldBuilder fb : fields) {
          Map<String, Object> info = Maps.newHashMap();
          entries.add(info);
          info.put("LiveFrom", "01/01/2017");
          info.put("CaseEventID", event.getId());
          info.put("CaseTypeID", "CARE_SUPERVISION_EPO");
          Field field = fb.build();
          info.put("CaseFieldID", field.getId());
          String context =
              field.getContext() == null ? "COMPLEX" : field.getContext().toString().toUpperCase();
          info.put("DisplayContext", context);
          info.put("PageFieldDisplayOrder", field.getPageFieldDisplayOrder());

          Object pageId = field.getPage();
          if (pageId == null) {
            pageId = 1;
          } else {
            Integer id = Ints
                .tryParse(pageId.toString());
            pageId = id != null ? id : pageId;
          }
          info.put("PageID", pageId);
          info.put("PageDisplayOrder", field.getPageDisplayOrder());
          info.put("PageColumnNumber", 1);
          if (field.getShowCondition() != null) {
            info.put("FieldShowCondition", field.getShowCondition());
          }

          if (collection.getMidEventWebhooks().containsKey(field.getPage())) {
            info.put("CallBackURLMidEvent",
                collection.getMidEventWebhooks().remove(field.getPage()));
          }

          if (collection.getPageShowConditions().containsKey(field.getPage())) {
            info.put("PageShowCondition",
                collection.getPageShowConditions().remove(field.getPage()));
          }

          if (field.isShowSummary()) {
            info.put("ShowSummaryChangeOption", "Y");
          }

          if (collection.getPageLabels().containsKey(field.getPage())) {
            info.put("PageLabel", collection.getPageLabels().remove(field.getPage()));
          }

          if (null != field.getCaseEventFieldLabel()) {
            info.put("CaseEventFieldLabel", field.getCaseEventFieldLabel());
          }
        }

        File folder = new File(root.getPath(), "CaseEventToFields");
        folder.mkdir();

        Path output = Paths.get(folder.getPath(), event.getId() + ".json");
        JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseFieldID");
      }
    }
  }
}
