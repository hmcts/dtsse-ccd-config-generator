package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SearchCases {
  private List<SearchCasesResultField> fields;

  public static class SearchCasesBuilder<T> {
    private Class<T> model;
    private PropertyUtils propertyUtils;

    public static <T> SearchCasesBuilder<T> builder(Class<T> model,
        PropertyUtils propertyUtils) {
      SearchCasesBuilder<T> result = SearchCases.builder();
      result.model = model;
      result.propertyUtils = propertyUtils;
      result.fields = new ArrayList<>();
      return result;
    }

    public SearchCasesBuilder<T> field(String id, String label, String displayContext,
                                       String listElementCode, String resultsOrdering) {
      fields.add(
          SearchCasesResultField.builder()
              .id(id)
              .label(label)
              .displayContextParameter(displayContext)
              .listElementCode(listElementCode)
              .resultsOrdering(resultsOrdering)
              .build()
      );
      return this;
    }

    public SearchCasesBuilder<T> field(String id, String label, String displayContext,
                                       String listElementCode) {
      return field(id, label, displayContext, listElementCode, null);
    }

    public SearchCasesBuilder<T> field(String id, String label, String displayContext) {
      return field(id, label, displayContext, null, null);
    }

    public SearchCasesBuilder<T> field(String id, String label) {
      return field(id, label, null, null, null);
    }

    public SearchCasesBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label,
                                       String displayContext, String listElementCode) {
      String name = propertyUtils.getPropertyName(model, getter);
      return field(name, label, displayContext, listElementCode, null);
    }

    public SearchCasesBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label,
                                       String displayContext) {
      String name = propertyUtils.getPropertyName(model, getter);
      return field(name, label, displayContext, null, null);
    }

    public SearchCasesBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label) {
      String name = propertyUtils.getPropertyName(model, getter);
      return field(name, label, null, null, null);
    }

    public SearchCasesBuilder<T> caseReferenceField() {
      fields.add(SearchCasesResultField.builder().id("[CASE_REFERENCE]").label("Case Number").build());
      return this;
    }

    public SearchCasesBuilder<T> stateField() {
      fields.add(SearchCasesResultField.builder().id("[STATE]").label("State").build());
      return this;
    }

    public SearchCasesBuilder<T> createdDateField() {
      fields.add(SearchCasesResultField.builder().id("[CREATED_DATE]").label("Created date").build());
      return this;
    }

    public SearchCasesBuilder<T> lastModifiedDate() {
      fields.add(SearchCasesResultField.builder().id("[LAST_MODIFIED_DATE]").label("Last modified date").build());
      return this;
    }

  }
}
