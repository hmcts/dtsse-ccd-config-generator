package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;

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
  private Map<String, String> midEventWebhooks;

  @ToString.Exclude
  private Map<String, String> pageShowConditions;

  @ToString.Exclude
  private Map<String, String> pageLabels;

  private String rootFieldname;

  public static class FieldCollectionBuilder<Type, Parent> {

    Class dataClass;

    private String pageId;
    private int order;
    private int pageDisplayOrder;
    private int fieldDisplayOrder;
    private String pageLabel;
    @ToString.Exclude
    private Parent parent;
    private PropertyUtils propertyUtils;
    private EventBuilder event;

    public static <Type, Parent> FieldCollectionBuilder<Type, Parent> builder(EventBuilder event,
        Parent parent, Class<Type> dataClass,
        PropertyUtils propertyUtils) {
      FieldCollectionBuilder<Type, Parent> result = new FieldCollectionBuilder<Type, Parent>();
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

    public <Value> FieldCollectionBuilder<Type, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, defaultValue);
    }

    public FieldCollectionBuilder<Type, Parent> optional(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition, true);
    }

    public FieldCollectionBuilder<Type, Parent> optional(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, true);
    }

    public FieldCollectionBuilder<Type, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition, false);
    }

    public FieldCollectionBuilder<Type, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, false);
    }

    public <Value> FieldCollectionBuilder<Type, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
                                                                  String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Mandatory, showCondition, true, defaultValue);
    }

    public FieldCollectionBuilder<Type, Parent> mandatory(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition, true);
    }

    public FieldCollectionBuilder<Type, Parent> mandatory(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, true);
    }

    public FieldCollectionBuilder<Type, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition, false);
    }

    public FieldCollectionBuilder<Type, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, false);
    }

    public FieldCollectionBuilder<Type, Parent> readonly(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, true);
    }

    public FieldCollectionBuilder<Type, Parent> readonly(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, true);
    }

    public FieldCollectionBuilder<Type, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, false);
    }

    public FieldCollectionBuilder<Type, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, false);
    }

    public FieldBuilder<?, Type, Parent> list(String id) {
      return field(id).mutableList().type("Collection");
    }

    public <U extends Iterable> FieldBuilder<U, Type, Parent> list(
        TypedPropertyGetter<Type, U> getter) {
      return field(getter).mutableList();
    }

    public <U extends Iterable> FieldBuilder<U, Type, Parent> immutableList(
        TypedPropertyGetter<Type, U> getter) {
      return field(getter).immutableList();
    }

    public FieldCollectionBuilder<Type, Parent> field(String id, DisplayContext context,
        String showCondition, String type, String typeParam, String label) {
      explicitFields.add(field(id).context(context).showCondition(showCondition).type(type)
          .fieldTypeParameter(typeParam).label(label));
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> field(String id, DisplayContext context,
        String showCondition) {
      explicitFields.add(field(id).context(context).showCondition(showCondition));
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> field(String fieldName, DisplayContext context) {
      explicitFields.add(field(fieldName).context(context));
      return this;
    }

    public FieldBuilder<?, Type, Parent> field(String id) {
      FieldBuilder<?, Type, Parent> result = createField(id, null);
      explicitFields.add(result);
      return result;
    }

    public FieldCollectionBuilder<Type, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context) {
      return field(getter, context, false);
    }

    public <Value> FieldCollectionBuilder<Type, Parent> field(TypedPropertyGetter<Type, Value> getter,
        DisplayContext context, String showCondition, boolean showSummary, Value defaultValue) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition).showSummary(showSummary).defaultValue(defaultValue);
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, String showCondition, boolean showSummary) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition).showSummary(showSummary);
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, String showCondition) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition);
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, boolean showSummary) {
      field(getter).context(context).showSummary(showSummary);
      return this;
    }

    public <U> Field.FieldBuilder<U, Type, Parent> field(TypedPropertyGetter<Type, U> getter) {
      String id = propertyUtils.getPropertyName(dataClass, getter);
      Class<U> clazz = propertyUtils.getPropertyType(dataClass, getter);
      FieldBuilder<U, Type, Parent> f = createField(id, clazz);
      CCD cf = propertyUtils.getAnnotationOfProperty(dataClass, getter, CCD.class);
      if (null != cf) {
        f.label(cf.label());
        f.hint(cf.hint());
      }
      return f;
    }

    private <U> FieldBuilder<U, Type, Parent> createField(String id, Class<U> clazz) {
      FieldBuilder<U, Type, Parent> f = FieldBuilder.builder(clazz, this, id);
      f.page(this.pageId);
      fields.add(f);
      f.fieldDisplayOrder(++fieldDisplayOrder);
      f.pageFieldDisplayOrder(++order);
      f.pageDisplayOrder(Math.max(1, this.pageDisplayOrder));
      return f;
    }

    public Parent done() {
      return parent;
    }

    public FieldCollectionBuilder<Type, Parent> showCondition(String condition) {
      pageShowConditions.put(this.pageId.toString(), condition);
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> midEventWebhook(String eventId) {
      event.customWebhookName = eventId;
      String url = event.getWebhookPathByConvention(Webhook.MidEvent);
      midEventWebhooks.put(this.pageId.toString(), url);
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> midEventWebhook() {
      String url = event.getWebhookPathByConvention(Webhook.MidEvent);
      midEventWebhooks.put(this.pageId.toString(), url);
      return this;
    }

    public <U> FieldCollectionBuilder<Type, Parent> complex(TypedPropertyGetter<Type, ?> getter,
        Class<U> c, Consumer<FieldCollectionBuilder<U, ?>> renderer, boolean showSummary) {
      renderer.accept(complex(getter, c, showSummary));
      return this;
    }

    public <U> FieldCollectionBuilder<Type, Parent> complex(TypedPropertyGetter<Type, ?> getter,
        Class<U> c, Consumer<FieldCollectionBuilder<U, ?>> renderer) {
      renderer.accept(complex(getter, c));
      return this;
    }

    public <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> complex(
        TypedPropertyGetter<Type, U> getter) {
      Class<U> c = propertyUtils.getPropertyType(dataClass, getter);
      return complex(getter, c);
    }

    public <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> complex(
        TypedPropertyGetter<Type, ?> getter, Class<U> c, boolean summary) {
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      if (null == this.rootFieldname) {
        // Register only the root complex as a field
        field(fieldName).context(DisplayContext.Complex).showSummary(summary);
      }
      return complex(fieldName, c);
    }

    public <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> complex(
        TypedPropertyGetter<Type, ?> getter, Class<U> c) {
      return complex(getter, c, true);
    }

    <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> complex(String fieldName,
        Class<U> c, boolean stripRoot) {
      FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> result =
          FieldCollectionBuilder.builder(event, this, c, propertyUtils);
      if (null != rootFieldname && stripRoot) {
        // TODO: refactor.
        // Nested fields should not be added to the main field list.
        fields.removeIf(x -> x.build().getId().equals(fieldName));
      }
      complexFields.add(result);
      result.rootFieldname = fieldName;
      // Nested builders inherit ordering state.
      if (null != parent) {
        result.fieldDisplayOrder = this.fieldDisplayOrder;
      }
      return result;
    }

    <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Type, Parent>> complex(String fieldName,
        Class<U> c) {
      return complex(fieldName, c, true);
    }

    public FieldCollectionBuilder<Type, Parent> label(String id, String value) {
      explicitFields.add(field(id).context(DisplayContext.ReadOnly).label(value).showSummary(false).immutable());
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> label(String id, String value, String showCondition) {
      explicitFields.add(field(id)
          .context(DisplayContext.ReadOnly)
          .label(value)
          .showCondition(showCondition)
          .showSummary(false)
          .immutable());
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> page(String id) {
      return this.pageObj(id);
    }

    public FieldCollectionBuilder<Type, Parent> page(int id) {
      return this.pageObj(String.valueOf(id));
    }

    public FieldCollectionBuilder<Type, Parent> previousPage() {
      this.pageDisplayOrder--;
      return this;
    }

    private FieldCollectionBuilder<Type, Parent> pageObj(String id) {
      this.pageId = id;
      this.order = 0;
      this.fieldDisplayOrder = 0;
      this.pageDisplayOrder++;
      return this;
    }

    public FieldCollectionBuilder<Type, Parent> pageLabel(String label) {
      this.pageLabels.put(this.pageId.toString(), label);
      return this;
    }
  }
}
