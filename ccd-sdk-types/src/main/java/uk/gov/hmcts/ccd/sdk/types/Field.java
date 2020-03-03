package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Builder
@Data
public class Field<T, Parent> {

  String id;
  String name;
  String description;
  String label;
  String hint;
  DisplayContext context;
  String showCondition;
  Object page;
  boolean showSummary;
  int fieldDisplayOrder;
  int pageFieldDisplayOrder;
  int pageDisplayOrder;
  String pageLabel;
  String type;
  String fieldTypeParameter;

  private Class dataClass;
  @ToString.Exclude
  private FieldCollection.FieldCollectionBuilder<T, Parent> parent;

  public static class FieldBuilder<T, Parent> {

    private uk.gov.hmcts.ccd.sdk.types.PropertyUtils propertyUtils;

    public static <T, Parent> FieldBuilder<T, Parent> builder(Class dataclass,
        FieldCollection.FieldCollectionBuilder<T, ?> parent,
        uk.gov.hmcts.ccd.sdk.types.PropertyUtils propertyUtils) {
      FieldBuilder result = new FieldBuilder();
      result.dataClass = dataclass;
      result.parent = parent;
      result.propertyUtils = propertyUtils;
      return result;
    }

    protected FieldBuilder<T, Parent> type(String t) {
      this.type = t;
      return this;
    }

    protected FieldBuilder<T, Parent> id(String id) {
      this.id = id;
      return this;
    }

    public FieldBuilder<T, Parent> id(TypedPropertyGetter<T, ?> getter) {
      id = propertyUtils.getPropertyName(dataClass, getter);

      CCD cf = propertyUtils.getAnnotationOfProperty(dataClass, getter, CCD.class);
      if (null != cf) {
        label = cf.label();
        hint = cf.hint();
      }
      return this;
    }

    public FieldCollection.FieldCollectionBuilder<T, Parent> done() {
      return parent;
    }
  }
}
