package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.types.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.types.Field.FieldBuilder;

@Builder
@Data
public class FieldCollection<T, Parent> {

  @ToString.Exclude
  private List<Field.FieldBuilder> fields;
  @ToString.Exclude
  private List<FieldCollectionBuilder<T, Parent>> complexFields;
  @ToString.Exclude
  private List<Field.FieldBuilder> explicitFields;

  @ToString.Exclude
  private Map<String, String> midEventWebhooks;

  @ToString.Exclude
  private Map<String, String> pageShowConditions;

  @ToString.Exclude
  private Map<String, String> pageLabels;

  private String rootFieldname;

  public static class FieldCollectionBuilder<T, Parent> {

    Class dataClass;

    private String pageId;
    private int order;
    private int pageDisplayOrder;
    private int fieldDisplayOrder;
    private String pageLabel;
    @ToString.Exclude
    private FieldCollectionBuilder<Parent, ?> parent;
    private PropertyUtils propertyUtils;
    private EventBuilder event;
    private TypedPropertyGetter<T, ?> getter;

    public static <T, Parent> FieldCollectionBuilder<T, Parent> builder(EventBuilder event,
        FieldCollectionBuilder<Parent, ?> parent, Class<T> dataClass, PropertyUtils propertyUtils) {
      FieldCollectionBuilder<T, Parent> result = new FieldCollectionBuilder<T, Parent>();
      result.pageId = "1";
      result.event = event;
      result.parent = parent;
      result.dataClass = dataClass;
      result.fields = new ArrayList<>();
      result.complexFields = new ArrayList<>();
      result.explicitFields = new ArrayList<>();
      result.midEventWebhooks = new Hashtable<>();
      result.pageShowConditions = new Hashtable<>();
      result.pageLabels = new Hashtable<>();
      result.propertyUtils = propertyUtils;
      return result;
    }

    public FieldCollectionBuilder<T, Parent> optional(TypedPropertyGetter<T, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition);
    }

    public FieldCollectionBuilder<T, Parent> optional(TypedPropertyGetter<T, ?> getter) {
      return field(getter, DisplayContext.Optional);
    }

    public FieldCollectionBuilder<T, Parent> mandatory(TypedPropertyGetter<T, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition);
    }

    public FieldCollectionBuilder<T, Parent> mandatory(TypedPropertyGetter<T, ?> getter) {
      return field(getter, DisplayContext.Mandatory);
    }

    public FieldCollectionBuilder<T, Parent> readonly(TypedPropertyGetter<T, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition);
    }

    public FieldCollectionBuilder<T, Parent> readonly(TypedPropertyGetter<T, ?> getter) {
      return field(getter, DisplayContext.ReadOnly);
    }

    public FieldCollectionBuilder<T, Parent> field(String id, DisplayContext context,
        String showCondition, String type, String typeParam, String label) {
      explicitFields.add(field().id(id).context(context).showCondition(showCondition).type(type)
          .fieldTypeParameter(typeParam).label(label));
      return this;
    }

    public FieldCollectionBuilder<T, Parent> field(String id, DisplayContext context,
        String showCondition) {
      explicitFields.add(field().id(id).context(context).showCondition(showCondition));
      return this;
    }

    public FieldCollectionBuilder<T, Parent> field(String fieldName, DisplayContext context) {
      explicitFields.add(field().id(fieldName).context(context));
      return this;
    }

    public FieldBuilder<T, Parent> field(String id) {
      FieldBuilder<T, Parent> result = field().id(id);
      explicitFields.add(result);
      return result;
    }

    public <U> FieldBuilder<T, U> field(TypedPropertyGetter<T, U> getter) {
      return (FieldBuilder<T, U>) field().id(getter);
    }

    public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter,
        DisplayContext context) {
      return field(getter, context, false);
    }

    public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter,
        DisplayContext context, String showCondition) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field().id(getter).context(context).showCondition(showCondition);
      return this;
    }

    public FieldCollectionBuilder<T, Parent> field(TypedPropertyGetter<T, ?> getter,
        DisplayContext context, boolean showSummary) {
      field().id(getter).context(context).showSummary(showSummary);
      return this;
    }

    public Field.FieldBuilder<T, Parent> field() {
      Field.FieldBuilder<T, Parent> f = Field.FieldBuilder.builder(dataClass, this, propertyUtils);
      f.page(this.pageId);
      fields.add(f);
      f.fieldDisplayOrder(++fieldDisplayOrder);
      f.pageFieldDisplayOrder(++order);
      f.pageDisplayOrder(Math.max(1, this.pageDisplayOrder));
      return f;
    }

    public FieldCollectionBuilder<Parent, ?> done() {
      parent.fieldDisplayOrder = this.fieldDisplayOrder;
      return parent;
    }

    public FieldCollectionBuilder<T, Parent> showCondition(String condition) {
      pageShowConditions.put(this.pageId.toString(), condition);
      return this;
    }

    public FieldCollectionBuilder<T, Parent> midEventWebhook(String eventId) {
      event.customWebhookName = eventId;
      String url = event.getWebhookPathByConvention(Webhook.MidEvent);
      midEventWebhooks.put(this.pageId.toString(), url);
      return this;
    }

    public FieldCollectionBuilder<T, Parent> midEventWebhook() {
      String url = event.getWebhookPathByConvention(Webhook.MidEvent);
      midEventWebhooks.put(this.pageId.toString(), url);
      return this;
    }

    public <U> FieldCollectionBuilder<T, Parent> complex(TypedPropertyGetter<T, ?> getter,
        Class<U> c, Consumer<FieldCollectionBuilder<U, ?>> renderer, boolean showSummary) {
      renderer.accept(complex(getter, c, showSummary));
      return this;
    }

    public <U> FieldCollectionBuilder<T, Parent> complex(TypedPropertyGetter<T, ?> getter,
        Class<U> c, Consumer<FieldCollectionBuilder<U, ?>> renderer) {
      renderer.accept(complex(getter, c));
      return this;
    }

    public <U> FieldCollectionBuilder<U, T> complex(TypedPropertyGetter<T, U> getter) {
      Class<U> c = propertyUtils.getPropertyType(dataClass, getter);
      return complex(getter, c);
    }

    public <U> FieldCollectionBuilder<U, T> complex(TypedPropertyGetter<T, ?> getter,
        Class<U> c, boolean summary) {
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      if (null == this.rootFieldname) {
        // Register only the root complex as a field
        field().id(fieldName).context(DisplayContext.Complex).showSummary(summary);
      }
      return complex(fieldName, c);
    }

    public <U> FieldCollectionBuilder<U, T> complex(TypedPropertyGetter<T, ?> getter, Class<U> c) {
      return complex(getter, c, true);
    }

    <U> FieldCollectionBuilder<U, T> complex(String fieldName, Class<U> c) {
      FieldCollectionBuilder<T, Parent> result = (FieldCollectionBuilder<T, Parent>)
          FieldCollectionBuilder.builder(event, this, c, propertyUtils);
      complexFields.add(result);
      result.rootFieldname = fieldName;
      // Nested builders inherit ordering state.
      if (null != parent) {
        result.fieldDisplayOrder = this.fieldDisplayOrder;
      }
      return (FieldCollectionBuilder<U, T>) result;
    }

    public FieldCollectionBuilder<T, Parent> label(String id, String value) {
      explicitFields.add(field().id(id).context(DisplayContext.ReadOnly).label(value).readOnly());
      return this;
    }

    public FieldCollectionBuilder<T, Parent> page(String id) {
      return this.pageObj(id);
    }

    public FieldCollectionBuilder<T, Parent> page(int id) {
      return this.pageObj(String.valueOf(id));
    }

    public FieldCollectionBuilder<T, Parent> previousPage() {
      this.pageDisplayOrder--;
      return this;
    }

    private FieldCollectionBuilder<T, Parent> pageObj(String id) {
      this.pageId = id;
      this.order = 0;
      this.fieldDisplayOrder = 0;
      this.pageDisplayOrder++;
      return this;
    }

    public FieldCollectionBuilder<T, Parent> pageLabel(String label) {
      this.pageLabels.put(this.pageId.toString(), label);
      return this;
    }

    public EventBuilder<T, ?, ?> eventBuilder() {
      return event;
    }
  }
}
