package uk.gov.hmcts.ccd.sdk.generator;


import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DisplayContext;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class CaseEventToFieldsGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    File folder = new File(root.getPath(), "CaseEventToFields");
    folder.mkdir();

    for (Event<T, R, S> event : config.getEvents().values()) {
      List<Map<String, Object>> entries = buildEntries(config, event);
      if (!entries.isEmpty()) {
        Path output = Paths.get(folder.getPath(), event.getId() + ".json");
        JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseFieldID");
      }
    }
  }

  private List<Map<String, Object>> buildEntries(ResolvedCCDConfig<T, S, R> config,
                                                 Event<T, R, S> event) {
    FieldCollection collection = event.getFields();
    if (collection.getFields().isEmpty()) {
      return List.of();
    }

    Multimap<String, String> writtenCallbacks = HashMultimap.create();
    List<Map<String, Object>> entries = Lists.newArrayList();
    for (Field.FieldBuilder builder : collection.getFields()) {
      Field field = builder.build();
      Map<String, Object> row = JsonUtils.caseRow(config.getCaseType());
      entries.add(row);
      populateCoreColumns(row, event, field);
      applyPublishFlag(row, event, field);

      Object pageId = resolvePageId(field.getPage());
      row.put("PageID", pageId);
      row.put("PageDisplayOrder", field.getPageDisplayOrder());
      row.put("PageColumnNumber", 1);
      applyFieldShowCondition(row, field);
      applyMidEventCallback(row, event, config, collection, pageId, writtenCallbacks);
      applyPageShowCondition(row, collection, field);
      applySummaryFlag(row, field);
      applyPageLabel(row, collection, field);
      applyDisplayContextParameter(row, field);
      applyFieldLabel(row, field);
      applyFieldHint(row, field);
      applyRetainHiddenValue(row, field);
    }

    return entries;
  }

  private void populateCoreColumns(Map<String, Object> row, Event<T, R, S> event, Field field) {
    row.put("CaseEventID", event.getId());
    row.put("CaseFieldID", field.getId());
    row.put("DisplayContext", resolveDisplayContext(field));
    row.put("PageFieldDisplayOrder", field.getPageFieldDisplayOrder());
  }

  private String resolveDisplayContext(Field field) {
    return Optional.ofNullable(field.getContext())
        .map(dc -> dc.toString().toUpperCase())
        .orElse("COMPLEX");
  }

  private void applyPublishFlag(Map<String, Object> row, Event<T, R, S> event, Field field) {
    if (!event.isPublishToCamunda()) {
      return;
    }
    String context = row.get("DisplayContext").toString();
    if (!context.equals(DisplayContext.Complex.toString().toUpperCase())) {
      row.put("Publish", "Y");
    }
  }

  private Object resolvePageId(Object rawPageId) {
    if (rawPageId == null) {
      return 1;
    }
    Integer parsed = Ints.tryParse(rawPageId.toString());
    return parsed != null ? parsed : rawPageId;
  }

  private void applyFieldShowCondition(Map<String, Object> row, Field field) {
    if (field.getShowCondition() != null) {
      row.put("FieldShowCondition", field.getShowCondition());
    }
  }

  private void applyMidEventCallback(Map<String, Object> row,
                                     Event<T, R, S> event,
                                     ResolvedCCDConfig<T, S, R> config,
                                     FieldCollection collection,
                                     Object pageId,
                                     Multimap<String, String> writtenCallbacks) {
    String pageKey = pageId.toString();
    if (!collection.getPagesToMidEvent().containsKey(pageKey)) {
      return;
    }
    if (writtenCallbacks.containsEntry(event.getId(), pageKey)) {
      return;
    }

    String url = config.getCallbackHost() + "/callbacks/mid-event?page="
        + URLEncoder.encode(pageKey, StandardCharsets.UTF_8)
        + "&eventId="
        + event.getId();
    row.put("CallBackURLMidEvent", url);
    writtenCallbacks.put(event.getId(), pageKey);
  }

  private void applyPageShowCondition(Map<String, Object> row,
                                      FieldCollection collection,
                                      Field field) {
    if (collection.getPageShowConditions().containsKey(field.getPage())) {
      row.put("PageShowCondition",
          collection.getPageShowConditions().remove(field.getPage()));
    }
  }

  private void applySummaryFlag(Map<String, Object> row, Field field) {
    if (field.isShowSummary()) {
      row.put("ShowSummaryChangeOption", "Y");
    }
  }

  private void applyPageLabel(Map<String, Object> row,
                              FieldCollection collection,
                              Field field) {
    if (collection.getPageLabels().containsKey(field.getPage())) {
      row.put("PageLabel", collection.getPageLabels().get(field.getPage()));
    }
  }

  private void applyDisplayContextParameter(Map<String, Object> row, Field field) {
    if (field.getDisplayContextParameter() != null) {
      row.put("DisplayContextParameter", field.getDisplayContextParameter());
    }
  }

  private void applyFieldLabel(Map<String, Object> row, Field field) {
    if (field.getCaseEventFieldLabel() != null) {
      row.put("CaseEventFieldLabel", field.getCaseEventFieldLabel());
    }
  }

  private void applyFieldHint(Map<String, Object> row, Field field) {
    if (field.getCaseEventFieldHint() != null) {
      row.put("CaseEventFieldHint", field.getCaseEventFieldHint());
    }
  }

  private void applyRetainHiddenValue(Map<String, Object> row, Field field) {
    if (field.isRetainHiddenValue()) {
      row.put("RetainHiddenValue", "Y");
    }
  }
}
