package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Search<T, R extends HasRole> {
  private List<SearchField<R>> fields;

  public static class SearchBuilder<T, R extends HasRole> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T, R extends HasRole> SearchBuilder<T, R> builder(Class<T> model,
                                                                     PropertyUtils propertyUtils) {
      SearchBuilder<T, R> result = Search.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder().id(name).label(label).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).build());
      return this;
    }

    public SearchBuilder<T, R> caseReferenceField() {
      fields.add(SearchField.<R>builder().id("[CASE_REFERENCE]").label("Case Number").build());
      return this;
    }

  }
}
