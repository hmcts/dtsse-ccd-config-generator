package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedField;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
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
import java.util.Optional;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;

class CaseEventToFieldsGenerator {

  public static <T, R extends HasRole, S> void writeEvents(File root, ResolvedCCDConfig<T, S, R> config) {
    // Make a copy for use in tracking which callbacks have been written.
    Table<String, String, MidEvent<T, S>> midEventCallbacks = HashBasedTable.create(config.midEventCallbacks);
    for (Event<T, R, S> event : config.events) {
      writeFieldsForEvent(root, config, midEventCallbacks, event);
    }
  }

  private static <T, R extends HasRole, S> void writeFieldsForEvent(
      File root,
      ResolvedCCDConfig<T, S, R> config,
      Table<String, String, MidEvent<T, S>> midEventCallbacks,
      Event<T, R, S> event) {
    FieldCollection collection = event.getFields().build();
    if (collection.getFields().size() > 0 || collection.getComplexFields().size() > 0) {
      List<Map<String, Object>> entries = Lists.newArrayList();
      List<Field.FieldBuilder> fields = collection.getFields();
      for (Field.FieldBuilder fb : fields) {
        Field field = fb.build();
        Optional<JsonUnwrapped> unwrapped = isUnwrappedField(config.typeArg, field.getId());

        // skip unwrapped complex types as the nested field will be added
        if (unwrapped.isEmpty()) {
          entries.add(createField(midEventCallbacks, config, event, collection, entries, field));
        }

      }

      // add any unwrapped fields from a complex type
      for (FieldCollection.FieldCollectionBuilder fb : collection.getComplexFields()) {
        FieldCollection complexType = fb.build();
        Optional<JsonUnwrapped> unwrapped = isUnwrappedField(config.typeArg, complexType.getRootFieldname());

        if (unwrapped.isPresent()) {
          for (Field.FieldBuilder ctfb : complexType.getFields()) {
            entries.add(
                createField(
                    midEventCallbacks, config, event, collection, entries, ctfb.build(), unwrapped.get().prefix()));
          }
        }
      }
      File folder = new File(root.getPath(), "CaseEventToFields");
      folder.mkdir();

      Path output = Paths.get(folder.getPath(), event.getId() + ".json");
      JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseFieldID");
    }
  }

  private static <T, R extends HasRole, S> Map<String, Object> createField(
      Table<String, String, MidEvent<T, S>> midEventCallbacks,
      ResolvedCCDConfig<T, S, R> config,
      Event<T, R, S> event,
      FieldCollection collection,
      List<Map<String, Object>> entries,
      Field field) {
    return createField(midEventCallbacks, config, event, collection, entries, field, "");
  }

  private static <S, T, R extends HasRole> Map<String, Object> createField(
      Table<String, String, MidEvent<T,S>> midEventCallbacks,
      ResolvedCCDConfig<T,S,R> config,
      Event<T,R,S> event,
      FieldCollection collection,
      List<Map<String, Object>> entries,
      Field field,
      String prefix) {
    Map<String, Object> info = Maps.newHashMap();
    info.put("LiveFrom", "01/01/2017");
    info.put("CaseEventID", event.getId());
    info.put("CaseTypeID", config.builder.caseType);

    String fieldId = prefix.isEmpty() ? field.getId() : prefix.concat(capitalize(field.getId()));

    info.put("CaseFieldID", fieldId);
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
      info.put("CallBackURLMidEvent", config.builder.callbackHost + "/callbacks/mid-event?page="
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

    if (null != field.getCaseEventHintText()) {
      info.put("CaseEventHintText", field.getCaseEventHintText());
    }

    return info;
  }
}
