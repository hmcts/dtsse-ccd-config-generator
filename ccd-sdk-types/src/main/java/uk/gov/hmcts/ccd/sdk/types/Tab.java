package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Tab {
  private String tabID;
  private String label;
  private String showCondition;
  private int displayOrder;
  private List<TabField> fields;
  private Set<String> excludedRoles;
  private Map<String, HasRole[]> fieldsExcludedByRole;

  public static class TabBuilder<T, R extends HasRole> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T, R extends HasRole> TabBuilder<T, R> builder(Class<T> model,
        PropertyUtils propertyUtils) {
      TabBuilder<T, R> result = Tab.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      result.excludedRoles = new HashSet<>();
      result.fieldsExcludedByRole = new Hashtable<>();
      return result;
    }

    public TabBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String showCondition) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(TabField.builder().id(name).showCondition(showCondition).build());
      return this;
    }


    public TabBuilder<T, R> field(TypedPropertyGetter<T, ?> getter) {
      return field(getter, null);
    }

    public TabBuilder<T, R> field(String fieldName) {
      fields.add(TabField.builder().id(fieldName).build());
      return this;
    }

    public RestrictedFieldBuilder<T, R> restrictedField(String id) {
      fields.add(TabField.builder().id(id).showCondition(showCondition).build());
      return (roles) -> {
        fieldsExcludedByRole.put(id, roles);
        return this;
      };
    }

    public RestrictedFieldBuilder<T, R> restrictedField(TypedPropertyGetter<T, ?> getter) {
      return restrictedField(propertyUtils.getPropertyName(model, getter));
    }

    public TabBuilder<T, R> exclude(R... roles) {
      for (R role : roles) {
        excludedRoles.add(role.getRole());
      }

      return this;
    }

    public TabBuilder<T, R> collection(TypedPropertyGetter<T, ? extends Collection> getter) {
      return field(getter, null);
    }
  }

  @FunctionalInterface
  public interface RestrictedFieldBuilder<T, R extends HasRole> {
    TabBuilder<T, R> exclude(R... roles);
  }
}
