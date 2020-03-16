package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorkBasket {
  private List<WorkBasketField> fields;

  public static class WorkBasketBuilder<T> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T> WorkBasketBuilder<T> builder(Class<T> model, PropertyUtils propertyUtils) {
      WorkBasketBuilder<T> result = WorkBasket.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public WorkBasketBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(WorkBasketField.builder().id(name).label(label).build());
      return this;
    }

    public WorkBasketBuilder<T> field(String fieldName, String label) {
      fields.add(WorkBasketField.builder().id(fieldName).label(label).build());
      return this;
    }
  }
}
