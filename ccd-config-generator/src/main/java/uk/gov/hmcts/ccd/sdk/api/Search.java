package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Search {
  private List<SearchField> fields;

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
      fields.add(SearchField.builder().id(name).label(label).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label) {
      fields.add(SearchField.builder().id(fieldName).label(label).build());
      return this;
    }

    public SearchBuilder<T, R> caseReferenceField() {
      fields.add(SearchField.builder().id("[CASE_REFERENCE]").label("CCD Case Number").build());
      return this;
    }

    public SearchBuilder<T, R> stateField() {
      fields.add(SearchField.builder().id("[STATE]").label("State").build());
      return this;
    }

    public SearchBuilder<T, R> createdDateField() {
      fields.add(SearchField.builder().id("[CREATED_DATE]").label("Created date").build());
      return this;
    }
  }
}
