package uk.gov.hmcts.ccd.sdk.types;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorkBasketResult {
  private List<WorkBasketResultField> fields;

  public static class WorkBasketResultBuilder<T> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T> WorkBasketResultBuilder<T> builder(Class<T> model,
        PropertyUtils propertyUtils) {
      WorkBasketResultBuilder<T> result = WorkBasketResult.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public WorkBasketResultBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(WorkBasketResultField.builder().id(name).label(label).build());
      return this;
    }

    public WorkBasketResultBuilder<T> field(String fieldName, String label) {
      fields.add(WorkBasketResultField.builder().id(fieldName).label(label).build());
      return this;
    }
  }
}
