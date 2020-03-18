package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

  public static class TabBuilder<T> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T> TabBuilder<T> builder(Class<T> model, PropertyUtils propertyUtils) {
      TabBuilder<T> result = Tab.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public TabBuilder<T> field(TypedPropertyGetter<T, ?> getter, String showCondition) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(TabField.builder().id(name).showCondition(showCondition).build());
      return this;
    }

    public TabBuilder<T> field(TypedPropertyGetter<T, ?> getter) {
      return field(getter, null);
    }

    public TabBuilder<T> field(String fieldName) {
      fields.add(TabField.builder().id(fieldName).build());
      return this;
    }

    public TabBuilder<T> collection(TypedPropertyGetter<T, ? extends Collection> getter) {
      return field(getter, null);
    }
  }
}
