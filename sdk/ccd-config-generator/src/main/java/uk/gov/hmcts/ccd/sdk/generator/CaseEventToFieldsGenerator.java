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

  private List<Map<String, Object>> buildEntries(ResolvedCCDConfig<T, S, R> config,
                                                 Event<T, R, S> event) {
    FieldCollection collection = event.getFields();
    if (collection.getFields().isEmpty()) {
      return List.of();
    }

    Multimap<String, String> writtenCallbacks = HashMultimap.create();
    Map<String, String> pageShowConditions = new HashMap<>(collection.getPageShowConditions());
    List<Map<String, Object>> entries = Lists.newArrayList();
    for (Field.FieldBuilder builder : collection.getFields()) {
      Field field = builder.build();
      // A field placed on this event via a typed getter still compiles when it is gated off (the
      // Java member always exists), but its CaseField row is suppressed — so skip the placement to
      // avoid a dangling CaseEventToFields row referencing a field that was not emitted.
      if (config.getGatedOffFieldIds().contains(field.getId())) {
        continue;
      }
      Map<String, Object> row = JsonUtils.caseRow(config.getCaseType());
      entries.add(row);
      populateCoreColumns(row, event, field);
      applyPublishFlag(row, event, field);

      Object pageId = resolvePageId(field.getPage());
      row.put("PageID", pageId);
      row.put("PageDisplayOrder", field.getPageDisplayOrder());
      row.put("PageColumnNumber", 1);
      applyMetadata(row, field, "CaseEventFieldLabel", "CaseEventFieldHint");
      applyMidEventCallback(row, event, config, collection, pageId, writtenCallbacks);
      applyPageShowCondition(row, pageShowConditions, field);
      applySummaryFlag(row, field);
      applyPageLabel(row, collection, field);
      applyDisplayContextParameter(row, field);
      applyShowSummaryContentOption(row, field);
      applyNullifyByDefault(row, field);
      applyDefaultValue(row, field);
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

  /**
   * Resolves {@code Publish}/{@code PublishAs} for a single field. An explicit
   * {@code field.publish(boolean)} overrides the event-level {@code publishToCamunda()} cascade:
   * {@code publish(false)} opts the field out of a publishing event, while {@code publish(true)}
   * (or {@code publishAs}, which implies it) publishes the field even on a non-publishing event.
   * With no explicit value the cascade applies as before. The definition store rejects a
   * {@code Publish} column on {@code COMPLEX} fields, so neither the cascade nor an explicit
   * override is written there.
   */
  private void applyPublishFlag(Map<String, Object> row, Event<T, R, S> event, Field field) {
    String context = row.get("DisplayContext").toString();
    if (context.equals(DisplayContext.Complex.toString().toUpperCase())) {
      return;
    }
    Boolean explicit = field.getPublish();
    boolean publish = explicit != null ? explicit : event.isPublishToCamunda();
    if (!publish) {
      return;
    }
    row.put("Publish", "Y");
    if (field.getPublishAs() != null) {
      row.put("PublishAs", field.getPublishAs());
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
                                      Map<String, String> pageShowConditions,
                                      Field field) {
    if (pageShowConditions.containsKey(field.getPage())) {
      row.put("PageShowCondition",
          pageShowConditions.remove(field.getPage()));
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

  private void applyShowSummaryContentOption(Map<String, Object> row, Field field) {
    if (field.getShowSummaryContentOption() != null) {
      row.put("ShowSummaryContentOption", field.getShowSummaryContentOption());
    }
  }

  private void applyNullifyByDefault(Map<String, Object> row, Field field) {
    if (field.isNullifyByDefault()) {
      row.put("NullifyByDefault", "Y");
    }
  }

  private void applyDefaultValue(Map<String, Object> row, Field field) {
    // Only the opt-in fluent defaultValue(String) setter populates caseEventDefaultValue; the
    // long-standing positional optional/mandatory defaultValue argument sets Field.defaultValue,
    // which feeds CaseEventToComplexTypes alone. Reading the dedicated carrier here keeps this
    // sheet byte-identical for every consumer that never calls the new setter.
    if (field.getCaseEventDefaultValue() == null) {
      return;
    }
    row.put("DefaultValue", field.getCaseEventDefaultValue());
  }

}
