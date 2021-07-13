package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.primitives.Ints;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class CaseEventToFieldsGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    // Make a copy for use in tracking which callbacks have been written.
    Table<String, String, MidEvent<T, S>> midEventCallbacks =
        HashBasedTable.create(config.midEventCallbacks);
    for (Event<T, R, S> event : config.events) {
      FieldCollection collection = event.getFields().build();
      if (collection.getFields().size() > 0) {
        List<Map<String, Object>> entries = Lists.newArrayList();
        List<Field.FieldBuilder> fields = collection.getFields();
        for (Field.FieldBuilder fb : fields) {
          Map<String, Object> info = Maps.newHashMap();
          entries.add(info);
          info.put("LiveFrom", "01/01/2017");
          info.put("CaseEventID", event.getId());
          info.put("CaseTypeID", config.caseType);
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

          if (midEventCallbacks.contains(event.getId(), pageId.toString())) {
            info.put("CallBackURLMidEvent", config.callbackHost + "/callbacks/mid-event?page="
                + URLEncoder.encode(pageId.toString(), StandardCharsets.UTF_8));
            midEventCallbacks.remove(event.getId(), pageId.toString());
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

          if (null != field.getCaseEventFieldHint()) {
            info.put("CaseEventFieldHint", field.getCaseEventFieldHint());
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
