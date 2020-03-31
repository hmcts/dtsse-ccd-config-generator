package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.types.FieldCollection.FieldCollectionBuilder;

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
  String page;
  String caseEventFieldLabel;
  boolean showSummary;
  int fieldDisplayOrder;
  int pageFieldDisplayOrder;
  int pageDisplayOrder;
  String type;
  String fieldTypeParameter;
  boolean mutable;
  boolean immutable;
  boolean readOnly;

  Class dataClass;
  @ToString.Exclude
  private FieldCollection.FieldCollectionBuilder<T, Parent> parent;

  public static class FieldBuilder<T, Parent> {

    private uk.gov.hmcts.ccd.sdk.types.PropertyUtils propertyUtils;
    private TypedPropertyGetter<T, ?> getter;

    public static <T, Parent> FieldBuilder<T, Parent> builder(Class dataclass,
        FieldCollection.FieldCollectionBuilder<T, ?> parent,
        uk.gov.hmcts.ccd.sdk.types.PropertyUtils propertyUtils) {
      FieldBuilder result = new FieldBuilder();
      result.dataClass = dataclass;
      result.parent = parent;
      result.propertyUtils = propertyUtils;
      result.context = DisplayContext.Complex;
      return result;
    }

    public FieldBuilder<T, Parent> type(String t) {
      this.type = t;
      return this;
    }

    public FieldBuilder<T, Parent> immutable() {
      this.immutable = true;
      return this;
    }

    public FieldBuilder<T, Parent> mutable() {
      this.mutable = true;
      return this;
    }

    public FieldBuilder<T, Parent> showSummary() {
      this.showSummary = true;
      return this;
    }

    public FieldBuilder<T, Parent> showSummary(boolean b) {
      this.showSummary = b;
      return this;
    }

    public FieldCollectionBuilder<Parent, T> complex() {
      Class c = propertyUtils.getPropertyType(dataClass, getter);
      return parent.complex(this.id, c);
    }

    public <U> FieldCollectionBuilder<U, T> complex(Class<U> c) {
      return parent.complex(this.id, c);
    }

    public <U> FieldCollectionBuilder<T, Parent> complex(Class<U> c,
        Consumer<FieldCollectionBuilder<U, ?>> renderer) {
      renderer.accept(parent.complex(this.id, c));
      return parent;
    }

    public FieldBuilder<T, Parent> id(String id) {
      this.id = id;
      return this;
    }

    public FieldBuilder<T, Parent> id(TypedPropertyGetter<T, ?> getter) {
      this.getter = getter;
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

    public FieldBuilder<T, Parent> readOnly() {
      this.readOnly = true;
      return this;
    }

  }
}
