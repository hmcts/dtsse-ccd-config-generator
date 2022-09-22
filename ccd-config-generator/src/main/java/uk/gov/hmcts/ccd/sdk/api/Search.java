package uk.gov.hmcts.ccd.sdk.api;

import de.cronn.reflection.util.TypedPropertyGetter;
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

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, String showCondition) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder().id(name).label(label).showCondition(showCondition).build());
      return this;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, SortOrder order) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder().id(name).label(label).order(order).build());
      return this;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, SortOrder order,
                                     String displayContextParameter) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder()
          .id(name)
          .label(label)
          .order(order)
          .displayContextParameter(displayContextParameter)
          .build()
      );
      return this;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, String showCondition,
                                     SortOrder order, String displayContextParameter) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder()
          .id(name)
          .label(label)
          .order(order)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .build()
      );
      return this;
    }

    public SearchBuilder<T, R> field(TypedPropertyGetter<T, ?> getter, String label, String showCondition,
                                     String displayContextParameter) {
      String name = propertyUtils.getPropertyName(model, getter);
      fields.add(SearchField.<R>builder()
          .id(name)
          .label(label)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .build()
      );
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, SortOrder order) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).order(order).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode) {
      fields.add(SearchField.<R>builder().id(fieldName).label(label).listElementCode(listElementCode).build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, String showCondition) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, String showCondition,
                                     String displayContextParameter) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode,
                                                    String showCondition, R role) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .userRole(role)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode,
                                     String showCondition, R role, String displayContextParameter) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .userRole(role)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, SortOrder order) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .order(order)
          .listElementCode(listElementCode)
          .build());
      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, String showCondition,
                                     SortOrder order) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .order(order)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode,
                                                    String showCondition, R role, SortOrder order) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .order(order)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .userRole(role)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, String showCondition,
                                     String displayContextParameter, SortOrder order) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .order(order)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .build());

      return this;
    }

    public SearchBuilder<T, R> field(String fieldName, String label, String listElementCode, String showCondition,
                                     R role, String displayContextParameter, SortOrder order) {
      fields.add(SearchField.<R>builder()
          .id(fieldName)
          .label(label)
          .order(order)
          .listElementCode(listElementCode)
          .showCondition(showCondition)
          .displayContextParameter(displayContextParameter)
          .userRole(role)
          .build());

      return this;
    }

    public SearchBuilder<T, R> caseReferenceField() {
      fields.add(SearchField.<R>builder().id("[CASE_REFERENCE]").label("Case Number").build());
      return this;
    }

    public SearchBuilder<T, R> stateField() {
      fields.add(SearchField.<R>builder().id("[STATE]").label("State").build());
      return this;
    }

    public SearchBuilder<T, R> createdDateField() {
      fields.add(SearchField.<R>builder().id("[CREATED_DATE]").label("Created date").build());
      return this;
    }

    public SearchBuilder<T, R> lastModifiedDate() {
      fields.add(SearchField.<R>builder().id("[LAST_MODIFIED_DATE]").label("Last modified date").build());
      return this;
    }
  }
}
