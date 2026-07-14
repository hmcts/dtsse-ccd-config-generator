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
  String pageShowCondition;
  String page;
  String caseEventFieldLabel;
  String caseEventFieldHint;
  Type defaultValue;
  boolean showSummary;
  boolean showSummaryColumn;
  int fieldDisplayOrder;
  int pageFieldDisplayOrder;
  int pageDisplayOrder;
  String pageLabel;
  String externalMidEventCallbackUrl;
  String type;
  String fieldTypeParameter;
  boolean mutableList;
  boolean immutableList;
  boolean immutable;
  boolean readOnly;
  private MidEvent midEventCallback;
  boolean retainHiddenValue;
  String retainHiddenValueValue;
  boolean publish;
  String eventFieldPublish;
  boolean includePageColumnNumber;

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
      result.includePageColumnNumber = true;
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
      this.showSummaryColumn = true;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> showSummary(boolean b) {
      this.showSummary = b;
      this.showSummaryColumn = b;
      return this;
    }

    /** Emits an explicit Y or N ShowSummaryChangeOption value. */
    public FieldBuilder<Type, StateType, Parent, Grandparent> showSummaryChangeOption(boolean value) {
      this.showSummary = value;
      this.showSummaryColumn = true;
      return this;
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

    public FieldBuilder<Type, StateType, Parent, Grandparent> externalMidEventCallbackUrl(
        String url) {
      this.externalMidEventCallbackUrl = url;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> retainHiddenValue(String value) {
      this.retainHiddenValue = true;
      this.retainHiddenValueValue = value;
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> retainHiddenValue(boolean value) {
      this.retainHiddenValue = value;
      this.retainHiddenValueValue = null;
      return this;
    }

    /** Publishes this event-complex element to the downstream event message. */
    public FieldBuilder<Type, StateType, Parent, Grandparent> publish() {
      this.publish = true;
      this.eventFieldPublish = "Y";
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> doNotPublish() {
      this.eventFieldPublish = "N";
      return this;
    }

    public FieldBuilder<Type, StateType, Parent, Grandparent> omitPublish() {
      this.eventFieldPublish = "";
      return this;
    }

    /** Omits PageColumnNumber for this event field only. */
    public FieldBuilder<Type, StateType, Parent, Grandparent> omitPageColumnNumber() {
      this.includePageColumnNumber = false;
      return this;
    }
  }
}
