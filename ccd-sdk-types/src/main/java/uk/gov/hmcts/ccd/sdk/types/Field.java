package uk.gov.hmcts.ccd.sdk.types;

import java.util.Hashtable;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import uk.gov.hmcts.ccd.sdk.types.FieldCollection.FieldCollectionBuilder;

@Builder
@Data
public class Field<Type, Parent, Grandparent> {

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
  private Map<String, String> blacklistedRolePermissions;

  Class<Type> clazz;
  @ToString.Exclude
  private FieldCollectionBuilder<Parent, Grandparent> parent;

  public static class FieldBuilder<Type, Parent, Grandparent> {

    public static <Type, Parent, Grandparent> FieldBuilder<Type, Parent, Grandparent> builder(
        Class<Type> clazz, FieldCollection.FieldCollectionBuilder<Parent, Grandparent> parent,
        String id) {
      FieldBuilder result = new FieldBuilder();
      result.clazz = clazz;
      result.parent = parent;
      result.context = DisplayContext.Complex;
      result.blacklistedRolePermissions = new Hashtable<>();
      result.id = id;
      return result;
    }

    public FieldBuilder<Type, Parent, Grandparent> blacklist(String crud, HasRole... roles) {
      for (HasRole role : roles) {
        blacklistedRolePermissions.put(role.getRole(), crud);
      }

      return this;
    }

    public FieldBuilder<Type, Parent, Grandparent> blacklist(HasRole... roles) {
      return blacklist("CRUD", roles);
    }

    public FieldBuilder<Type, Parent, Grandparent> type(String t) {
      this.type = t;
      return this;
    }

    public FieldBuilder<Type, Parent, Grandparent> immutable() {
      this.immutable = true;
      return this;
    }

    public FieldBuilder<Type, Parent, Grandparent> mutable() {
      this.mutable = true;
      return this;
    }

    public FieldBuilder<Type, Parent, Grandparent> showSummary() {
      this.showSummary = true;
      return this;
    }

    public FieldBuilder<Type, Parent, Grandparent> showSummary(boolean b) {
      this.showSummary = b;
      return this;
    }

    public FieldCollectionBuilder<Type, FieldCollectionBuilder<Parent, Grandparent>> complex() {
      if (clazz == null) {
        throw new RuntimeException("Cannot infer type for field: " + id
            + ". Provide an explicit type.");
      }
      return parent.complex(this.id, clazz);
    }

    public <U> FieldCollectionBuilder<U, FieldCollectionBuilder<Parent, Grandparent>> complex(
        Class<U> c) {
      return parent.complex(this.id, c);
    }

    public <U> FieldCollectionBuilder<Parent, Grandparent> complex(Class<U> c,
        Consumer<FieldCollectionBuilder<U, ?>> renderer) {
      renderer.accept(parent.complex(this.id, c));
      return parent;
    }

    public <U> FieldCollectionBuilder<U,
        FieldCollectionBuilder<Parent, Grandparent>> complexWithParent(Class<U> c) {
      return parent.complex(this.id, c, false);
    }

    public FieldCollection.FieldCollectionBuilder<Parent, Grandparent> done() {
      return parent;
    }

    public FieldBuilder<Type, Parent, Grandparent> readOnly() {
      this.readOnly = true;
      return this;
    }

  }
}
