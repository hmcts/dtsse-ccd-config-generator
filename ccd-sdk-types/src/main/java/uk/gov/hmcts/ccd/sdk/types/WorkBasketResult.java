package uk.gov.hmcts.ccd.sdk.types;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WorkBasketResult {
  private List<WorkBasketResultField> fields;

  public static class WorkBasketResultBuilder<T> {

    public static <T> WorkBasketResultBuilder<T> builder() {
      WorkBasketResultBuilder<T> result = WorkBasketResult.builder();
      result.fields = new ArrayList<>();
      return result;
    }

    public WorkBasketResultBuilder<T> field(String fieldName, String label) {
      fields.add(WorkBasketResultField.builder().id(fieldName).label(label).build());
      return this;
    }
  }
}
