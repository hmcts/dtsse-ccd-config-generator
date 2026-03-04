package uk.gov.hmcts.ccd.sdk.generator;


import static org.apache.commons.lang3.StringUtils.capitalize;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
      applyPublishFlag(row, event);

      Object pageId = resolvePageId(field.getPage());
      row.put("PageID", pageId);
      row.put("PageDisplayOrder", field.getPageDisplayOrder());
      row.put("PageColumnNumber", 1);
      applyMetadata(row, field, "CaseEventFieldLabel", "CaseEventFieldHint", event);
      applyMidEventCallback(row, event, config, collection, pageId, writtenCallbacks);
      applyPageShowCondition(row, collection, field, event);
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
    row.put("PageFieldDisplayOrder", field.getPageFieldDisplayOrder());
  }

  private String resolveDisplayContext(Field field) {
    return Optional.ofNullable(field.getContext())
        .map(dc -> dc.toString().toUpperCase())
        .orElse("COMPLEX");
  }

  private void applyPublishFlag(Map<String, Object> row, Event<T, R, S> event) {
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

  static void applyMetadata(Map<String, Object> target,
                            Field field,
                            String labelColumn,
                            String hintColumn) {
    applyMetadata(target, field, labelColumn, hintColumn, null);
  }

  static void applyMetadata(Map<String, Object> target,
                            Field field,
                            String labelColumn,
                            String hintColumn,
                            Event<?, ?, ?> event) {
    if (field.getShowCondition() != null) {
      String condition = field.getShowCondition();
      if (event != null && event.isDtoEvent()) {
        condition = prefixShowCondition(condition, event.getDtoPrefix());
      }
      target.put("FieldShowCondition", condition);
    }

    if (labelColumn != null && field.getCaseEventFieldLabel() != null) {
      String label = field.getCaseEventFieldLabel();
      if (event != null && event.isDtoEvent()) {
        label = prefixLabelReferences(label, event.getDtoPrefix());
      }
      target.put(labelColumn, label);
    }

    if (hintColumn != null && field.getCaseEventFieldHint() != null) {
      String hint = field.getCaseEventFieldHint();
      if (event != null && event.isDtoEvent()) {
        hint = prefixLabelReferences(hint, event.getDtoPrefix());
      }
      target.put(hintColumn, hint);
    }

    if (field.isRetainHiddenValue()) {
      target.put("RetainHiddenValue", "Y");
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
                                      Field field,
                                      Event<?, ?, ?> event) {
    if (collection.getPageShowConditions().containsKey(field.getPage())) {
      String condition = collection.getPageShowConditions().remove(field.getPage());
      if (event.isDtoEvent()) {
        condition = prefixShowCondition(condition, event.getDtoPrefix());
      }
      row.put("PageShowCondition", condition);
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

  private static final Pattern SHOW_CONDITION_FIELD_PATTERN = Pattern.compile("(\\w+)(=\")");
  private static final Pattern LABEL_FIELD_PATTERN = Pattern.compile("\\$\\{(\\w+)\\}");

  static String prefixLabelReferences(String text, String prefix) {
    if (text == null || prefix == null || prefix.isEmpty()) {
      return text;
    }
    return LABEL_FIELD_PATTERN.matcher(text)
        .replaceAll(m -> Matcher.quoteReplacement(
            "${" + prefix + capitalize(m.group(1)) + "}"));
  }

  static String prefixShowCondition(String condition, String prefix) {
    if (condition == null || prefix == null || prefix.isEmpty()) {
      return condition;
    }
    return SHOW_CONDITION_FIELD_PATTERN.matcher(condition)
        .replaceAll(m -> prefix + capitalize(m.group(1)) + m.group(2));
  }

}
