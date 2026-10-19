package uk.gov.hmcts.ccd.sdk.api;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedField;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

@Builder
@Data
public class FieldCollection {

  @ToString.Exclude
  private List<Field.FieldBuilder> fields;
  @ToString.Exclude
  private List<FieldCollectionBuilder> complexFields;
  @ToString.Exclude
  private List<Field.FieldBuilder> explicitFields;

  @ToString.Exclude
  private Map<String, String> pageShowConditions;

  @ToString.Exclude
  private Map<String, String> pageLabels;

  private Map<String, MidEvent> pagesToMidEvent;

  // Mid-event callbacks served at the service's own endpoints rather than by an SDK handler.
  private Map<String, String> pagesToMidEventUrl;

  private Map<String, String> pagesToMidEventRetries;

  private String rootFieldname;

  private String unwrappedParentPrefix;

  public static class FieldCollectionBuilder<Type, StateType, Parent> {

    Class dataClass;

    private String pageId;
    private IntRef order = new IntRef();
    private IntRef pageDisplayOrder = new IntRef();
    private IntRef fieldDisplayOrder = new IntRef();
    @ToString.Exclude
    private Parent parent;
    private PropertyUtils propertyUtils;
    private EventBuilder event;
    // The size of {@link FieldCollection#fields} when a member scope that registers no field of its
    // own was last opened from this builder, or -1: a fluent setter reaching lastField() with no
    // field placed since would otherwise apply to whichever field happened to precede the scope.
    private int scopeOpenedAt = -1;

    public static <Type, StateType, Parent> FieldCollectionBuilder<Type, StateType, Parent> builder(EventBuilder event,
        Parent parent, Class<Type> dataClass,
        PropertyUtils propertyUtils) {
      FieldCollectionBuilder<Type, StateType, Parent> result = new FieldCollectionBuilder<>();
      result.pageId = "1";
      result.event = event;
      result.parent = parent;
      result.dataClass = dataClass;
      result.fields = new ArrayList<>();
      result.complexFields = new ArrayList<>();
      result.explicitFields = new ArrayList<>();
      result.pageShowConditions = new Hashtable<>();
      result.pagesToMidEvent = new HashMap<>();
      result.pagesToMidEventUrl = new HashMap<>();
      result.pagesToMidEventRetries = new HashMap<>();
      result.pageLabels = new Hashtable<>();
      result.propertyUtils = propertyUtils;
      return result;
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, String caseEventFieldLabel, String caseEventFieldHint,
        String displayContextParameter) {
      return field(
        getter, DisplayContext.Optional, showCondition, defaultValue, caseEventFieldLabel,
        caseEventFieldHint, displayContextParameter);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, String caseEventFieldLabel, String caseEventFieldHint) {
      return field(
        getter, DisplayContext.Optional, showCondition, true, defaultValue, caseEventFieldLabel,
        caseEventFieldHint, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, defaultValue, null, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, boolean retainHiddenValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, defaultValue, null, null, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition, true, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, ?> getter,
        String showCondition, boolean retainHiddenValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, true);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optionalWithLabel(
        TypedPropertyGetter<Type, Value> getter,
        String caseEventFieldLabel) {
      return field(getter, DisplayContext.Optional, null, true, null, caseEventFieldLabel, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optionalWithoutDefaultValue(
        TypedPropertyGetter<Type, Value> getter,
        String showCondition,
        String caseEventFieldLabe) {
      return field(getter, DisplayContext.Optional, showCondition, true, null, caseEventFieldLabe, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optionalWithoutDefaultValue(
        TypedPropertyGetter<Type, Value> getter,
        String showCondition,
        String caseEventFieldLabe, boolean retainHiddenValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, null, caseEventFieldLabe,
        null, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter,
         String showCondition, String caseEventFieldLabel) {
      return field(getter, DisplayContext.Optional, showCondition, false, null, caseEventFieldLabel, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, String caseEventFieldLabel, String caseEventFieldHint,
        String displayContextParameter) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, defaultValue, caseEventFieldLabel, caseEventFieldHint,
          displayContextParameter);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, String caseEventFieldLabel, String caseEventFieldHint) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, true, defaultValue, caseEventFieldLabel, caseEventFieldHint,
        false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
         String showCondition, Value defaultValue, String caseEventFieldLabel, String caseEventFieldHint,
         boolean retainHiddenValue) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, true, defaultValue, caseEventFieldLabel, caseEventFieldHint,
        retainHiddenValue);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue, String caseEventFieldLabel) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, true, defaultValue, caseEventFieldLabel, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
                                                                  String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Mandatory, showCondition, true, defaultValue, null,
        null, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition, true, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, ?> getter,
        String showCondition, boolean retainHiddenValue) {
      return field(getter, DisplayContext.Mandatory, showCondition, true, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, true);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatoryWithDisplayContextParameter(
        TypedPropertyGetter<Type, Value> getter,
        String showCondition,
        String displayContextParameter) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, null, null, null, displayContextParameter);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatoryWithLabel(
        TypedPropertyGetter<Type, Value> getter,
        String caseEventFieldLabel) {
      return field(
        getter, DisplayContext.Mandatory, null, true, null, caseEventFieldLabel, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatoryWithoutDefaultValue(
        TypedPropertyGetter<Type, Value> getter,
        String showCondition,
        String caseEventFieldLabel) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, true, null, caseEventFieldLabel, null, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatoryWithoutDefaultValue(
        TypedPropertyGetter<Type, Value> getter,
        String showCondition,
        String caseEventFieldLabel,
        boolean retainHiddenValue) {
      return field(
        getter, DisplayContext.Mandatory, showCondition, true, null, caseEventFieldLabel, null, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition, String caseEventFieldLabel) {
      return field(getter, DisplayContext.Mandatory, showCondition, false, null,
        caseEventFieldLabel, null, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonly(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, true, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonly(TypedPropertyGetter<Type, ?> getter,
        String showCondition, boolean retainHiddenValue) {
      return field(getter, DisplayContext.ReadOnly, showCondition, true, retainHiddenValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonly(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, true);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> readonlyWithLabel(
        TypedPropertyGetter<Type, Value> getter, String caseEventFieldLabel) {
      return field(
        getter, DisplayContext.ReadOnly, null, false, null, caseEventFieldLabel, null, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, false, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, false);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> list(
        TypedPropertyGetter<Type, List<ListValue<U>>> getter) {
      return list(getter, null);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> list(
        TypedPropertyGetter<Type, List<ListValue<U>>> getter, String showCondition) {
      String id = propertyUtils.getPropertyName(dataClass, getter);
      Class<U> itemClass = propertyUtils.getListValueElementType(dataClass, getter);
      FieldBuilder<U, StateType, Type, Parent> fieldBuilder = createField(id, itemClass);
      fieldBuilder.showCondition(showCondition);
      fieldBuilder.showSummary();

      CCD cf = propertyUtils.getAnnotationOfProperty(dataClass, getter, CCD.class);
      if (null != cf) {
        fieldBuilder.label(cf.label());
        fieldBuilder.hint(cf.hint());
      }

      fieldBuilder.mutableList();
      return fieldBuilder.complex();
    }

    FieldBuilder<?, StateType, Type, Parent> field(String id) {
      FieldBuilder<?, StateType, Type, Parent> result = createField(id, null);
      explicitFields.add(result);
      return result;
    }

    <Value> FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, Value> getter,
                                                      DisplayContext context, String showCondition, Value defaultValue,
                                                      String caseEventFieldLabel, String caseEventFieldHint,
                                                      String displayContextParameter) {
      field(getter)
          .context(context)
          .showCondition(showCondition)
          .showSummary(true)
          .defaultValue(defaultValue)
          .displayContextParameter(displayContextParameter)
          .caseEventFieldLabel(caseEventFieldLabel)
          .caseEventFieldHint(caseEventFieldHint)
          .retainHiddenValue(false);
      return this;
    }

    <Value> FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, Value> getter,
        DisplayContext context, String showCondition, boolean showSummary, Value defaultValue,
        String caseEventFieldLabel, String caseEventFieldHint, boolean retainHiddenValue) {
      field(getter)
          .context(context)
          .showCondition(showCondition)
          .showSummary(showSummary)
          .defaultValue(defaultValue)
          .caseEventFieldLabel(caseEventFieldLabel)
          .caseEventFieldHint(caseEventFieldHint)
          .retainHiddenValue(retainHiddenValue);
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, String showCondition, boolean showSummary, boolean retainHiddenValue) {
      field(getter)
          .context(context)
          .showCondition(showCondition)
          .showSummary(showSummary)
          .retainHiddenValue(retainHiddenValue);
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, boolean showSummary) {
      var f = field(getter).context(context).showSummary(showSummary);
      if (context == DisplayContext.ReadOnly) {
        f.immutable();
      }
      return this;
    }

    <U> Field.FieldBuilder<U, StateType, Type, Parent> field(TypedPropertyGetter<Type, U> getter) {
      String id = propertyUtils.getPropertyName(dataClass, getter);
      Class<U> clazz = propertyUtils.getPropertyType(dataClass, getter);
      FieldBuilder<U, StateType, Type, Parent> f = createField(id, clazz);
      CCD cf = propertyUtils.getAnnotationOfProperty(dataClass, getter, CCD.class);
      if (null != cf) {
        f.label(cf.label());
        f.hint(cf.hint());
      }
      return f;
    }

    private <U> FieldBuilder<U, StateType, Type, Parent> createField(String id, Class<U> clazz) {
      String fieldId = null != unwrappedParentPrefix && !unwrappedParentPrefix.isEmpty()
          ? unwrappedParentPrefix.concat(capitalize(id))
          : id;

      FieldBuilder<U, StateType, Type, Parent> f = FieldBuilder.builder(clazz, this, fieldId);
      f.page(this.pageId);
      fields.add(f);
      f.fieldDisplayOrder(fieldDisplayOrder.increment());
      f.pageFieldDisplayOrder(order.increment());
      f.pageDisplayOrder(Math.max(1, pageDisplayOrder.get()));
      return f;
    }

    public Parent done() {
      return parent;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> showCondition(String condition) {
      pageShowConditions.put(this.pageId, condition);
      return this;
    }

    /**
     * Explicitly sets the most-recently-added field's {@code CaseEventToFields.Publish} column,
     * overriding the event-level {@code publishToCamunda()} cascade for that field only:
     * {@code false} opts the field out of a publishing event, {@code true} publishes it on a
     * non-publishing event.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> publish(boolean publish) {
      lastField().publish(publish);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.PublishAs} alias. Implies
     * {@code publish(true)} per the definition-store parser, which treats {@code PublishAs} as
     * meaningless without {@code Publish}.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> publishAs(String publishAs) {
      lastField().publishAs(publishAs);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.ShowSummaryContentOption},
     * the display order of that field's content within the event's check-your-answers summary.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> showSummaryContentOption(int order) {
      lastField().showSummaryContentOption(order);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.NullifyByDefault} flag: on
     * submit the field is cleared unless a value was provided.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> nullifyByDefault() {
      lastField().nullifyByDefault();
      return this;
    }

    /**
     * Sets the event-level label of the most-recently-added field or complex-type member. Emitted
     * as {@code CaseEventToFields.CaseEventFieldLabel} for a top-level field, and as
     * {@code CaseEventToComplexTypes.EventElementLabel} for a member placed inside a
     * {@code .complex(...)} scope.
     *
     * <p>Fluent equivalent of the trailing {@code caseEventFieldLabel} parameter on the positional
     * {@code optional}/{@code mandatory} overloads, usable after any placement that has no such
     * overload ({@code readonly}, {@code *NoSummary}, etc.). Default {@code null} omits the column.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> eventLabel(String label) {
      lastField().caseEventFieldLabel(label);
      return this;
    }

    /**
     * Overrides the most-recently-added complex-type member's {@code CaseEventToComplexTypes.HintText}
     * with the given value, emitted verbatim on the member's event row instead of the member's
     * declared {@code @CCD(hint)}, which {@code CaseEventToComplexTypesGenerator} otherwise cascades
     * onto every event row placing the member. Usable after a member placement inside a
     * {@code .complex(...)} scope ({@code optional}/{@code mandatory}/{@code readonly}).
     *
     * <p>DISTINCT from {@link #eventHint(String)}: that writes the {@code EventHintText} column (the
     * per-event hint override), whereas this writes {@code HintText} (the field-level hint the
     * generator derives from {@code @CCD(hint)}). Leaving it unset keeps the cascade. Passing
     * {@code null} is equivalent to {@link #noHintText()}.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> hintText(String hintText) {
      lastField().hintText(hintText);
      return this;
    }

    /**
     * Suppresses the most-recently-added complex-type member's {@code CaseEventToComplexTypes.HintText}
     * entirely, overriding the {@code @CCD(hint)} the generator would otherwise cascade onto the
     * member's event row. Usable after a member placement inside a {@code .complex(...)} scope. See
     * {@link #hintText(String)}.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> noHintText() {
      lastField().noHintText();
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.DefaultValue} to a raw string,
     * verbatim — usable after any context-selecting call ({@code readonly}, {@code *NoSummary},
     * etc.) that returns this builder rather than the field, matching the sheet column, which is
     * untyped.
     *
     * <p>Writes {@code CaseEventToFields.DefaultValue} for a top-level field and
     * {@code CaseEventToComplexTypes.DefaultValue} for a member placed inside a {@code .complex(...)}
     * scope, following the sheet the placement itself lands on. Both sheets carry the column, and the
     * raw form is the only one that reaches a member default the definition expresses as a case-role
     * literal rather than as a {@code HasRole} constant — finrem's {@code manageInterveners} places
     * {@code intervener1.intervenerOrganisation.OrgPolicyCaseAssignedRole} with
     * {@code DefaultValue=[INTVRSOLICITOR1]}, a role its {@code UserRole} enum does not declare.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> defaultValue(String defaultValue) {
      lastField().defaultValue(defaultValue);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.RetainHiddenValue} flag: a
     * value entered while the field is visible survives it later being hidden by its
     * {@code showCondition} — usable after any context-selecting call ({@code readonly},
     * {@code *NoSummary}, etc.) that returns this builder rather than the field.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> retainHiddenValue() {
      lastField().retainHiddenValue();
      return this;
    }

    /**
     * Alias of {@link #eventLabel(String)}.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> caseEventFieldLabel(String label) {
      return eventLabel(label);
    }

    /**
     * Sets the event-level hint text of the most-recently-added field or complex-type member.
     * Emitted as {@code CaseEventToFields.CaseEventFieldHint} for a top-level field, and as
     * {@code CaseEventToComplexTypes.EventHintText} for a member placed inside a
     * {@code .complex(...)} scope.
     *
     * <p>Fluent equivalent of the trailing {@code caseEventFieldHint} parameter on the positional
     * {@code optional}/{@code mandatory} overloads, usable after any placement that has no such
     * overload ({@code readonly}, {@code *NoSummary}, etc.). Default {@code null} omits the column.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> eventHint(String hint) {
      lastField().caseEventFieldHint(hint);
      return this;
    }

    /**
     * Alias of {@link #eventHint(String)}.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> caseEventFieldHint(String hint) {
      return eventHint(hint);
    }

    /**
     * Sets the most-recently-added complex-type member's {@code CaseEventToComplexTypes.PageID}, the
     * wizard page the member is shown on within the event. Unlike {@link #page(String)} — which
     * switches the page context for a top-level event field and drives {@code CaseEventToFields} —
     * this tags a single member row emitted by {@link #complex} expansion, which otherwise carries
     * no page. Default {@code null} omits the column.
     *
     * <p>Note: the definition-store importer parses complex-type rows without a page column, so this
     * value is carried purely for round-trip fidelity with hand-authored definitions; it does not
     * change how CCD renders the member.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> pageId(String pageId) {
      lastField().eventComplexPageId(pageId);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.FieldShowCondition} — usable
     * after any context-selecting call ({@code readonly}, {@code *NoSummary}, etc.) that returns
     * this builder rather than the field. Named to avoid colliding with {@link #showCondition}
     * above, which sets the enclosing page's show condition, not the field's.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> fieldShowCondition(String showCondition) {
      lastField().showCondition(showCondition);
      return this;
    }

    /**
     * Sets the most-recently-added field's {@code CaseEventToFields.DisplayContextParameter} —
     * usable after any context-selecting call ({@code readonly}, {@code *NoSummary}, etc.) that
     * returns this builder rather than the field.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> displayContextParameter(String displayContextParameter) {
      lastField().displayContextParameter(displayContextParameter);
      return this;
    }

    private FieldBuilder<?, StateType, Type, Parent> lastField() {
      if (fields.isEmpty() || fields.size() == scopeOpenedAt) {
        throw new IllegalStateException("No field to apply this setter to: place a field first. A"
            + " setter chained after .done() of a member scope that registered no field of its own"
            + " has no field to act on.");
      }
      return fields.get(fields.size() - 1);
    }

    /**
     * Opens a member scope on the element type of a {@code Collection} field <em>without</em>
     * registering the collection field itself, so per-member {@code CaseEventToComplexTypes}
     * overrides can be attached to the elements of a {@code List<ListValue<U>>} field the author has
     * already placed separately (via {@code .optional(getter)} / {@code .readonly(getter)} etc.).
     *
     * <p>This is the collection analogue of {@link #complex(TypedPropertyGetter)}: that overload
     * registers a scalar complex field as a root {@code field(...)} and opens a scope on it, whereas
     * a collection field's getter is typed {@code List<ListValue<U>>}, so a scope must be typed on the
     * <em>element</em> {@code U} rather than the list. Because the field is placed elsewhere, this
     * method registers no root field at all — mirroring how the {@code @JsonUnwrapped}-prefix branch
     * of {@link #complex(TypedPropertyGetter, boolean, String, String, String, boolean)} skips its own
     * {@code field(...)} call — so opening the scope has no effect on the collection field's own
     * {@code CaseEventToFields} row (no extra field registration, and none of the {@code showSummary}/
     * {@code mutableList} side effects {@link #list(TypedPropertyGetter)} applies). The
     * {@code CaseEventToComplexTypesGenerator} walks the opened scope and composes dotted
     * {@code ListElementCode}s from the member getters; collection elements need no index, so the rows
     * come out in exactly the input shape.
     *
     * <p>The {@code elementClass} argument alone determines the element type {@code U}: it both
     * types the returned member-scope builder and documents the element type at the call site. The
     * getter is deliberately typed to accept any {@code List} — services annotated in place carry
     * collections in their own wrapper idioms ({@code List<ListValue<U>>}, sscs's
     * {@code List<CcdValue<U>>}, civil/prl's {@code List<Element<U>>}, or a bare {@code List<U>}),
     * and the scope only needs the field's name plus the element class, never the wrapper. Nested
     * collection members inside the opened scope get the same treatment by calling this overload
     * again on the nested builder.
     *
     * @param getter the collection field's getter
     * @param elementClass the collection's element type {@code U}
     * @param <U> the collection element type the member scope is opened on
     * @return the member-scope builder for the element type
     */
    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, ? extends List<?>> getter, Class<U> elementClass) {
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      return complex(fieldName, elementClass);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, String showCondition, String eventFieldLabel, String eventFieldHint) {
      return complex(getter, true, showCondition, eventFieldLabel, eventFieldHint, false);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, String showCondition, String eventFieldLabel) {
      return complex(getter, true, showCondition, eventFieldLabel, null, false);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, String showCondition) {
      return complex(getter, true, showCondition, null, null, false);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, boolean summary) {
      return complex(getter, summary, null, null, null, false);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, String showCondition, String eventFieldLabel, String eventFieldHint,
        boolean retainHiddenValue) {
      return complex(getter, true, showCondition, eventFieldLabel, eventFieldHint, retainHiddenValue);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, ?> getter,
        boolean summary,
        String showCondition,
        String label,
        String hint,
        boolean retainHiddenValue) {

      Class<U> c = propertyUtils.getPropertyType(dataClass, getter);
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      Optional<JsonUnwrapped> isUnwrapped = isUnwrappedField(dataClass, fieldName);

      if (null == this.rootFieldname && isUnwrapped.isEmpty()) {
        // Register only the root complex as a field
        field(fieldName)
            .context(DisplayContext.Complex)
            .showSummary(summary)
            .showCondition(showCondition)
            .caseEventFieldLabel(label)
            .caseEventFieldHint(hint)
            .retainHiddenValue(retainHiddenValue);
      }

      FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> builder =
          complex(fieldName, c);
      if (null == this.rootFieldname && isUnwrapped.isEmpty()) {
        // The root field registered above is what a setter chained after .done() applies to.
        scopeOpenedAt = -1;
      }

      if (isUnwrapped.isPresent()) {
        String prefix = isUnwrapped.get().prefix();
        builder.unwrappedParentPrefix = isNullOrEmpty(unwrappedParentPrefix)
            ? prefix
            : unwrappedParentPrefix.concat(capitalize(prefix));
        builder.fields = fields;
        builder.explicitFields = explicitFields;
        builder.complexFields = complexFields;
        builder.rootFieldname = null;
        builder.order = order;
        builder.pageDisplayOrder = pageDisplayOrder;
        builder.fieldDisplayOrder = fieldDisplayOrder;
        complexFields.remove(builder);
      }

      return builder;
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter) {
      return complex(getter, true);
    }

    <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(String fieldName,
        Class<U> c) {
      FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> result =
          FieldCollectionBuilder.builder(event, this, c, propertyUtils);
      complexFields.add(result);
      scopeOpenedAt = fields.size();
      result.rootFieldname = !isNullOrEmpty(unwrappedParentPrefix)
          ? unwrappedParentPrefix.concat(capitalize(fieldName))
          : fieldName;
      result.pageId = this.pageId;
      // Nested builders inherit ordering state.
      if (null != parent) {
        result.fieldDisplayOrder = this.fieldDisplayOrder;
      }
      return result;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> label(String id, String value) {
      explicitFields.add(field(id).context(DisplayContext.ReadOnly).label(value).showSummary(false).immutable());
      return this;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> label(String id, String value, String showCondition) {
      explicitFields.add(field(id)
          .context(DisplayContext.ReadOnly)
          .label(value)
          .showCondition(showCondition)
          .showSummary(false)
          .immutable());
      return this;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> label(String id, String value,
                                                                 String showCondition, boolean showSummary) {
      explicitFields.add(field(id)
          .context(DisplayContext.ReadOnly)
          .label(value)
          .showCondition(showCondition)
          .showSummary(showSummary)
          .immutable());
      return this;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> page(String id, MidEvent<Type, StateType> callback) {
      if (callback != null && pagesToMidEventUrl.containsKey(id)) {
        throw bothMidEventHandlerAndUrl(id);
      }
      this.pagesToMidEvent.put(id, callback);
      return this.page(id);
    }

    /** Starts a page with a mid-event handler and its retry timeouts. */
    public FieldCollectionBuilder<Type, StateType, Parent> page(
        String id, MidEvent<Type, StateType> callback, int... retries) {
      page(id, callback);
      midEventRetries(id, retries);
      return this;
    }

    /**
     * Starts a page whose mid-event callback is an endpoint the service already serves. The URL is
     * written verbatim, so definition placeholders such as {@code ${CCD_DEF_URL}} are kept.
     */
    public FieldCollectionBuilder<Type, StateType, Parent> page(String id, String midEventUrl, int... retries) {
      if (pagesToMidEvent.get(id) != null) {
        throw bothMidEventHandlerAndUrl(id);
      }
      this.pagesToMidEventUrl.put(id, midEventUrl);
      midEventRetries(id, retries);
      return this.page(id);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> page(String id) {
      return this.pageObj(id);
    }

    private void midEventRetries(String pageId, int... retries) {
      if (retries.length > 0) {
        pagesToMidEventRetries.put(pageId, EventBuilder.joinRetries(retries));
      }
    }

    private IllegalStateException bothMidEventHandlerAndUrl(String pageId) {
      return EventBuilder.bothHandlerAndUrl(event.eventId(), "MidEvent on page '" + pageId + "'");
    }

    private FieldCollectionBuilder<Type, StateType, Parent> pageObj(String id) {
      this.pageId = id;
      this.order = new IntRef();
      this.fieldDisplayOrder = new IntRef();
      this.pageDisplayOrder.increment();
      return this;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> pageLabel(String label) {
      this.pageLabels.put(this.pageId, label);
      return this;
    }
  }
}
