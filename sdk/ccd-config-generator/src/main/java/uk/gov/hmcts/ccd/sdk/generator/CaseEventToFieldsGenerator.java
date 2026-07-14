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
import java.util.HashMap;
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
    File folder = GeneratorUtils.ensureDirectory(root, "CaseEventToFields");

    for (Event<T, R, S> event : config.getEvents().values()) {
      List<Map<String, Object>> entries = buildEntries(config, event);
      if (!entries.isEmpty()) {
        Path output = Paths.get(folder.getPath(), event.getId() + ".json");
        JsonUtils.mergeInto(output, entries, new AddMissing(), "CaseFieldID");
      }
    }
  }

  private List<Map<String, Object>> buildEntries(
      ResolvedCCDConfig<T, S, R> config, Event<T, R, S> event) {
    FieldCollection collection = event.getFields();
    if (collection.getFields().isEmpty()) {
      return List.of();
    }

    Multimap<String, String> writtenCallbacks = HashMultimap.create();
    Map<String, String> pageShowConditions = new HashMap<>(collection.getPageShowConditions());
    List<Map<String, Object>> entries = Lists.newArrayList();
    for (Field.FieldBuilder builder : collection.getFields()) {
      Field field = builder.build();
      Map<String, Object> row = JsonUtils.caseRow(config.getCaseType());
      entries.add(row);
      populateCoreColumns(row, event, field);
      applyPublishFlag(row, event, field);

      Object pageId = resolvePageId(field.getPage());
      row.put("PageID", pageId);
      if (collection.isIncludePageDisplayOrder() && field.isIncludePageDisplayOrder()) {
        row.put("PageDisplayOrder", field.getPageDisplayOrder());
      }
      if (collection.isIncludePageColumnNumber() && field.isIncludePageColumnNumber()) {
        row.put("PageColumnNumber", 1);
      }
      applyMetadata(row, field, "CaseEventFieldLabel", "CaseEventFieldHint");
      applyMidEventCallback(row, event, config, collection, field, pageId, writtenCallbacks);
      applyPageShowCondition(row, pageShowConditions, field);
      applySummaryFlag(row, field);
      applyPageLabel(row, collection, field);
      applyDisplayContextParameter(row, field);
    }

    return entries;
  }

  private void populateCoreColumns(Map<String, Object> row, Event<T, R, S> event, Field field) {
    row.put("CaseEventID", event.getId());
    row.put("CaseFieldID", field.getId());
    row.put("DisplayContext", resolveDisplayContext(field));
    if (field.isIncludePageFieldDisplayOrder()) {
      row.put("PageFieldDisplayOrder", field.getPageFieldDisplayOrder());
    }
  }

  private String resolveDisplayContext(Field field) {
    return Optional.ofNullable(field.getContext())
        .map(dc -> dc.toString().toUpperCase())
        .orElse("COMPLEX");
  }

  private void applyPublishFlag(Map<String, Object> row, Event<T, R, S> event, Field field) {
    if (field.getEventFieldPublish() != null) {
      if (!field.getEventFieldPublish().isEmpty()) {
        row.put("Publish", field.getEventFieldPublish());
      }
      return;
    }
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

  static void applyMetadata(
      Map<String, Object> target, Field field, String labelColumn, String hintColumn) {
    if (field.getShowCondition() != null) {
      target.put("FieldShowCondition", field.getShowCondition());
    }

    if (labelColumn != null && field.getCaseEventFieldLabel() != null) {
      target.put(labelColumn, field.getCaseEventFieldLabel());
    }

    if (hintColumn != null && field.getCaseEventFieldHint() != null) {
      target.put(hintColumn, field.getCaseEventFieldHint());
    }

    if (field.isRetainHiddenValue()) {
      target.put(
          "RetainHiddenValue",
          field.getRetainHiddenValueValue() == null ? "Y" : field.getRetainHiddenValueValue());
    }
  }

  private void applyMidEventCallback(
      Map<String, Object> row,
      Event<T, R, S> event,
      ResolvedCCDConfig<T, S, R> config,
      FieldCollection collection,
      Field field,
      Object pageId,
      Multimap<String, String> writtenCallbacks) {
    if (field.getExternalMidEventCallbackUrl() != null) {
      row.put("CallBackURLMidEvent", field.getExternalMidEventCallbackUrl());
      return;
    }
    String pageKey = pageId.toString();
    boolean hasHandler = collection.getPagesToMidEvent().containsKey(pageKey);
    String externalUrl = collection.getPagesToExternalMidEvent().get(pageKey);
    if (!hasHandler && externalUrl == null) {
      return;
    }
    if (writtenCallbacks.containsEntry(event.getId(), pageKey)) {
      return;
    }

    String url =
        externalUrl == null
            ? config.getCallbackHost()
                + "/callbacks/mid-event?page="
                + URLEncoder.encode(pageKey, StandardCharsets.UTF_8)
                + "&eventId="
                + event.getId()
            : externalUrl;
    row.put("CallBackURLMidEvent", url);
    writtenCallbacks.put(event.getId(), pageKey);
  }

  private void applyPageShowCondition(
      Map<String, Object> row, Map<String, String> pageShowConditions, Field field) {
    if (field.getPageShowCondition() != null) {
      row.put("PageShowCondition", field.getPageShowCondition());
      pageShowConditions.remove(field.getPage());
      return;
    }
    if (pageShowConditions.containsKey(field.getPage())) {
      row.put("PageShowCondition", pageShowConditions.remove(field.getPage()));
    }
  }

  private void applySummaryFlag(Map<String, Object> row, Field field) {
    if (field.isShowSummaryColumn()) {
      row.put("ShowSummaryChangeOption", field.isShowSummary() ? "Y" : "N");
    }
  }

  private void applyPageLabel(Map<String, Object> row, FieldCollection collection, Field field) {
    if (field.getPageLabel() != null) {
      row.put("PageLabel", field.getPageLabel());
    } else if (collection.getPageLabels().containsKey(field.getPage())) {
      row.put("PageLabel", collection.getPageLabels().get(field.getPage()));
    }
  }

  private void applyDisplayContextParameter(Map<String, Object> row, Field field) {
    if (field.getDisplayContextParameter() != null) {
      row.put("DisplayContextParameter", field.getDisplayContextParameter());
    }
  }
}
