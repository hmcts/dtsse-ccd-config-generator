package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection.FieldCollectionBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;

@Builder
@Data
public class Field<Type, StateType, Parent, Grandparent> {

  String id;
  String name;
  String description;
  String label;
  String hint;
  DisplayContext context;
  String displayContextParameter;
  String showCondition;
  String page;
  String caseEventFieldLabel;
  String caseEventFieldHint;
  Type defaultValue;
  String caseEventDefaultValue;
  boolean showSummary;
  int fieldDisplayOrder;
  int pageFieldDisplayOrder;
  int pageDisplayOrder;
  String type;
  String fieldTypeParameter;
  boolean mutableList;
  boolean immutableList;
  boolean immutable;
  boolean readOnly;
  private MidEvent midEventCallback;
  boolean retainHiddenValue;
  Boolean publish;
  String publishAs;
  Integer showSummaryContentOption;
  boolean nullifyByDefault;
  String eventComplexPageId;

  /**
   * Tri-state override for the {@code CaseEventToComplexTypes.HintText} of a complex-type member
   * placed inside a {@code .complex(...)} scope, distinct from {@link #hint} (the member's declared
   * {@code @CCD(hint)}, which {@code CaseEventToComplexTypesGenerator} otherwise cascades onto every
   * event row that places the member). When {@link #eventComplexHintTextOverridden} is {@code false}
   * (the default) that cascade applies unchanged, so output is byte-identical for every existing
   * consumer. When it is {@code true}, this value replaces the cascade: a non-null value is emitted
   * verbatim as the row's {@code HintText}; a null value suppresses the column entirely. Set via the
   * fluent {@code hintText(String)} / {@code noHintText()} builder methods.
   *
   * <p>This is NOT the same column as {@code caseEventFieldHint} (set via {@code eventHint(...)}),
   * which writes {@code CaseEventToComplexTypes.EventHintText} — the per-event hint override — rather
   * than {@code HintText}, the field-level hint the generator derives from {@code @CCD(hint)}.
   */
  String eventComplexHintText;

  /**
   * Whether {@link #eventComplexHintText} overrides the cascaded {@code @CCD(hint)} for this member's
   * {@code CaseEventToComplexTypes.HintText}. See {@link #eventComplexHintText}.
   */
  boolean eventComplexHintTextOverridden;

  Class<Type> clazz;
  @ToString.Exclude
  private FieldCollectionBuilder<Parent, StateType, Grandparent> parent;

  public static class FieldBuilder<Type, StateType, Parent, Grandparent> {

    public static <Type, StateType, Parent, Grandparent> FieldBuilder<Type, StateType, Parent, Grandparent> builder(
        Class<Type> clazz, FieldCollection.FieldCollectionBuilder<Parent, StateType, Grandparent> parent,
        String id) {
      FieldBuilder result = new FieldBuilder();
      result.clazz = clazz;
      result.parent = parent;
      result.context = DisplayContext.Complex;
      result.id = id;
      return result;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> optional() {
      context = DisplayContext.Optional;
      return this;
    }


    public FieldBuilder<Type, StateType, Parent, Grandparent> mandatory() {
      context = DisplayContext.Mandatory;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> type(String t) {
      this.type = t;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> immutable() {
      this.immutable = true;
      return this;
    }

    FieldBuilder<Type, StateType, Parent, Grandparent> immutableList() {
      this.immutableList = true;
      return this;
    }

    FieldBuilder<Type, StateType, Parent, Grandparent> mutableList() {
      this.mutableList = true;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> showSummary() {
      this.showSummary = true;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> showSummary(boolean b) {
      this.showSummary = b;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.DefaultValue} to a raw string, verbatim. Declaring
     * this overload alongside the {@code Type}-typed setter Lombok would otherwise generate means
     * that setter must be hand-written here too (Lombok skips generation for any property with an
     * existing same-named builder method, regardless of arity).
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> defaultValue(Type defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    /**
     * See {@link #defaultValue(Object)}. Accepts a plain string so a field whose {@code Type} is
     * not {@code String} (e.g. an enum) can still carry a literal {@code DefaultValue}, matching
     * the sheet column, which is untyped.
     *
     * <p>Unlike the {@code Type}-typed overload — whose value the long-standing positional
     * {@code optional}/{@code mandatory} builders route to {@code CaseEventToComplexTypes} only —
     * this opt-in setter writes the {@code CaseEventToFields.DefaultValue} column via a dedicated
     * carrier, so a config that never calls it produces no {@code DefaultValue} on that sheet.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> defaultValue(String defaultValue) {
      this.caseEventDefaultValue = defaultValue;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.RetainHiddenValue} flag: a value entered while the
     * field is visible survives it later being hidden by its {@code showCondition}. Declaring the
     * no-arg {@link #retainHiddenValue()} overload means this setter must be hand-written too
     * (Lombok skips generation for any property with an existing same-named builder method,
     * regardless of arity) — it is otherwise identical to the setter Lombok would have generated.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> retainHiddenValue(boolean retainHiddenValue) {
      this.retainHiddenValue = retainHiddenValue;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.RetainHiddenValue} flag: a value entered while the
     * field is visible survives it later being hidden by its {@code showCondition}. Composes with
     * every other fluent setter (unlike the {@code FieldCollectionBuilder} positional overloads
     * carrying {@code retainHiddenValue}, which have no combinable overload alongside a
     * {@code displayContextParameter}).
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> retainHiddenValue() {
      return retainHiddenValue(true);
    }

    public FieldCollectionBuilder<Type, StateType, FieldCollectionBuilder<Parent, StateType, Grandparent>> complex() {
      if (clazz == null) {
        throw new RuntimeException("Cannot infer type for field: " + id
            + ". Provide an explicit type.");
      }
      return parent.complex(this.id, clazz);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Parent, StateType, Grandparent>> complex(
        Class<U> c) {
      return parent.complex(this.id, c);
    }

    public FieldCollection.FieldCollectionBuilder<Parent, StateType, Grandparent> done() {
      return parent;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> readOnly() {
      this.context = DisplayContext.ReadOnly;
      return this;
    }

    /**
     * Explicitly sets this field's {@code CaseEventToFields.Publish} column, overriding the
     * event-level {@code publishToCamunda()} cascade for this field only: {@code false} opts the
     * field out of a publishing event, {@code true} publishes it on a non-publishing event.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> publish(boolean publish) {
      this.publish = publish;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.PublishAs} alias. The definition store's
     * {@code EventCaseFieldParser}/{@code PublishFieldsValidator} read and validate
     * {@code Publish} and {@code PublishAs} as unrelated columns, so this does not imply
     * {@code publish(true)}; call {@link #publish(boolean)} explicitly if the field should also
     * publish.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> publishAs(String publishAs) {
      this.publishAs = publishAs;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.ShowSummaryContentOption}, the display order of
     * this field's content within the event's check-your-answers summary. {@code null} (the
     * default) omits the column, matching output produced before this option existed.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> showSummaryContentOption(int order) {
      this.showSummaryContentOption = order;
      return this;
    }

    /**
     * Overrides this complex-type member's {@code CaseEventToComplexTypes.HintText} with the given
     * value, emitted verbatim on the member's event row instead of the {@code @CCD(hint)} the
     * generator otherwise cascades. Use inside a {@code .complex(...)} member scope; distinct from
     * {@link #hint} (the declared field hint) and from {@code caseEventFieldHint} (the
     * {@code EventHintText} column set by the {@code FieldCollectionBuilder}'s {@code eventHint}).
     * Passing {@code null} is equivalent to {@link #noHintText()}.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> hintText(String hintText) {
      this.eventComplexHintText = hintText;
      this.eventComplexHintTextOverridden = true;
      return this;
    }

    /**
     * Suppresses this complex-type member's {@code CaseEventToComplexTypes.HintText} entirely,
     * overriding the {@code @CCD(hint)} the generator would otherwise cascade onto the member's event
     * row. Use inside a {@code .complex(...)} member scope. See {@link #hintText(String)}.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> noHintText() {
      this.eventComplexHintText = null;
      this.eventComplexHintTextOverridden = true;
      return this;
    }

    /**
     * Sets this field's {@code CaseEventToFields.NullifyByDefault} flag: on submit the field is
     * cleared unless a value was provided, regardless of any prior value. The definition-store
     * importer rejects setting this together with a {@code DefaultValue} on the same field.
     */
    public FieldBuilder<Type, StateType, Parent, Grandparent> nullifyByDefault() {
      this.nullifyByDefault = true;
      return this;
    }
  }
}
