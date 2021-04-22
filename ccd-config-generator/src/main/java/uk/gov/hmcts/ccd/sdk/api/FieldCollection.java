package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder;
import uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder;
import uk.gov.hmcts.ccd.sdk.api.callback.MidEvent;

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

  private String rootFieldname;

  public static class FieldCollectionBuilder<Type, StateType, Parent> {

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
      result.pageLabels = new Hashtable<>();
      result.propertyUtils = propertyUtils;
      return result;
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, Value> getter,
        String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Optional, showCondition, true, defaultValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optional(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Optional, showCondition, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> optionalNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Optional, false);
    }

    public <Value> FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, Value> getter,
                                                                  String showCondition, Value defaultValue) {
      return field(getter, DisplayContext.Mandatory, showCondition, true, defaultValue);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatory(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.Mandatory, showCondition, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> mandatoryNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.Mandatory, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonly(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonly(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, true);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter,
        String showCondition) {
      return field(getter, DisplayContext.ReadOnly, showCondition, false);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> readonlyNoSummary(TypedPropertyGetter<Type, ?> getter) {
      return field(getter, DisplayContext.ReadOnly, false);
    }

    public FieldBuilder<?, StateType, Type, Parent> list(String id) {
      return field(id).mutableList().type("Collection");
    }

    public <U extends Iterable> FieldBuilder<U, StateType, Type, Parent> list(
        TypedPropertyGetter<Type, U> getter) {
      return field(getter).mutableList();
    }

    public <U extends Iterable> FieldBuilder<U, StateType, Type, Parent> immutableList(
        TypedPropertyGetter<Type, U> getter) {
      return field(getter).immutableList();
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(String id, DisplayContext context,
        String showCondition, String type, String typeParam, String label) {
      explicitFields.add(field(id).context(context).showCondition(showCondition).type(type)
          .fieldTypeParameter(typeParam).label(label));
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(String id, DisplayContext context,
        String showCondition) {
      explicitFields.add(field(id).context(context).showCondition(showCondition));
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(String fieldName, DisplayContext context) {
      explicitFields.add(field(fieldName).context(context));
      return this;
    }

    FieldBuilder<?, StateType, Type, Parent> field(String id) {
      FieldBuilder<?, StateType, Type, Parent> result = createField(id, null);
      explicitFields.add(result);
      return result;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context) {
      return field(getter, context, false);
    }

    <Value> FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, Value> getter,
        DisplayContext context, String showCondition, boolean showSummary, Value defaultValue) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition).showSummary(showSummary).defaultValue(defaultValue);
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, String showCondition, boolean showSummary) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition).showSummary(showSummary);
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, String showCondition) {
      if (null != showCondition && null != rootFieldname) {
        showCondition = showCondition.replace("{{FIELD_NAME}}", rootFieldname);
      }
      field(getter).context(context).showCondition(showCondition);
      return this;
    }

    FieldCollectionBuilder<Type, StateType, Parent> field(TypedPropertyGetter<Type, ?> getter,
        DisplayContext context, boolean showSummary) {
      field(getter).context(context).showSummary(showSummary);
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
      FieldBuilder<U, StateType, Type, Parent> f = FieldBuilder.builder(clazz, this, id);
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

    public FieldCollectionBuilder<Type, StateType, Parent> showCondition(String condition) {
      pageShowConditions.put(this.pageId.toString(), condition);
      return this;
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, String showCondition) {
      return complex(getter, true, showCondition);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter, boolean summary) {
      Class<U> c = propertyUtils.getPropertyType(dataClass, getter);
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      if (null == this.rootFieldname) {
        // Register only the root complex as a field
        field(fieldName).context(DisplayContext.Complex).showSummary(summary);
      }
      return complex(fieldName, c);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, ?> getter, boolean summary, String showCondition) {
      Class<U> c = propertyUtils.getPropertyType(dataClass, getter);
      String fieldName = propertyUtils.getPropertyName(dataClass, getter);
      if (null == this.rootFieldname) {
        // Register only the root complex as a field
        field(fieldName).context(DisplayContext.Complex).showSummary(summary).showCondition(showCondition);
      }
      return complex(fieldName, c);
    }

    public <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(
        TypedPropertyGetter<Type, U> getter) {
      return complex(getter, true);
    }

    <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(String fieldName,
        Class<U> c, boolean stripRoot) {
      FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> result =
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

    <U> FieldCollectionBuilder<U, StateType, FieldCollectionBuilder<Type, StateType, Parent>> complex(String fieldName,
        Class<U> c) {
      return complex(fieldName, c, true);
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

    public FieldCollectionBuilder<Type, StateType, Parent> page(String id, MidEvent<Type, StateType> callback) {
      this.pagesToMidEvent.put(id, callback);
      return this.page(id);
    }

    public FieldCollectionBuilder<Type, StateType, Parent> page(String id) {
      return this.pageObj(id);
    }

    private FieldCollectionBuilder<Type, StateType, Parent> pageObj(String id) {
      this.pageId = id;
      this.order = 0;
      this.fieldDisplayOrder = 0;
      this.pageDisplayOrder++;
      return this;
    }

    public FieldCollectionBuilder<Type, StateType, Parent> pageLabel(String label) {
      this.pageLabels.put(this.pageId.toString(), label);
      return this;
    }
  }
}
