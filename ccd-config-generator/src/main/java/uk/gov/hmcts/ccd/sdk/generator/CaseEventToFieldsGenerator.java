package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
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
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class CaseEventToFieldsGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    for (Event<T, R, S> event : config.getEvents().values()) {
      // For use in tracking which callbacks have been written.
      Multimap<String, String> writtenCallbacks = HashMultimap.create();
      FieldCollection collection = event.getFields();
      if (collection.getFields().size() > 0) {
        List<Map<String, Object>> entries = Lists.newArrayList();
        List<Field.FieldBuilder> fields = collection.getFields();
        for (Field.FieldBuilder fb : fields) {
          Map<String, Object> info = Maps.newHashMap();
          entries.add(info);
          info.put("LiveFrom", "01/01/2017");
          info.put("CaseEventID", event.getId());
          info.put("CaseTypeID", config.getCaseType());
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

          // Include any mid-event callbacks only on the first page's entry
          if (collection.getPagesToMidEvent().containsKey(pageId.toString())
              && !writtenCallbacks.containsEntry(event.getId(), pageId.toString())) {
            info.put("CallBackURLMidEvent", config.getCallbackHost() + "/callbacks/mid-event?page="
                + URLEncoder.encode(pageId.toString(), StandardCharsets.UTF_8)
                + "&eventId="
                + event.getId());
            writtenCallbacks.put(event.getId(), pageId.toString());
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

          if (null != field.getDisplayContextParameter()) {
            info.put("DisplayContextParameter", field.getDisplayContextParameter());
          }

          if (null != field.getCaseEventFieldLabel()) {
            info.put("CaseEventFieldLabel", field.getCaseEventFieldLabel());
          }

          if (null != field.getCaseEventFieldHint()) {
            info.put("CaseEventFieldHint", field.getCaseEventFieldHint());
          }

          if (field.isRetainHiddenValue()) {
            info.put("RetainHiddenValue", "Y");
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
