package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

    /**
     * Add a {@code SearchCasesResultFields} row configured through the per-field
     * {@link ResultFieldBuilder}, for the columns the plain {@code field(...)} overloads cannot
     * express: the {@code AccessProfile}/{@code UserRole} scope and the {@code UseCase}. Because the
     * {@link Consumer} disambiguates from the {@code String}-only overloads it composes with them
     * freely in one chain; the builder still returns {@code this}.
     *
     * <p>Any column left unset falls back to its default: an empty {@code UserRole} and
     * {@code UseCase=orgcases}. Scope a field to several roles or use-cases by calling this once per
     * combination; the rows are kept distinct because {@code UserRole} and {@code UseCase} are part of
     * the row's merge key, mirroring the role-scoping of the search/workbasket
     * {@link Search.SearchBuilder} sub-builder.
     *
     * <pre>{@code
     * builder.searchCasesFields()
     *     .field(CaseData::getCaseName, "Case name",
     *         f -> f.role(CASEWORKER).useCase("WORKBASKET"));
     * }</pre>
     */
    public SearchCasesBuilder<T> field(String id, String label,
                                       Consumer<ResultFieldBuilder> configurer) {
      ResultFieldBuilder fieldBuilder = new ResultFieldBuilder(id, label);
      configurer.accept(fieldBuilder);
      fields.add(fieldBuilder.build());
      return this;
    }

    /**
     * Add a {@code SearchCasesResultFields} row by getter, configured through the per-field
     * {@link ResultFieldBuilder}. See {@link #field(String, String, Consumer)}.
     */
    public SearchCasesBuilder<T> field(TypedPropertyGetter<T, ?> getter, String label,
                                       Consumer<ResultFieldBuilder> configurer) {
      return field(propertyUtils.getPropertyName(model, getter), label, configurer);
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

  /**
   * Per-field configurer for the optional {@code SearchCasesResultFields} columns. Obtained via
   * {@link SearchCasesBuilder#field(String, String, Consumer)}; every setter returns {@code this} for
   * chaining and any column left unset falls back to its default (empty {@code UserRole},
   * {@code UseCase=orgcases}).
   */
  public static class ResultFieldBuilder {
    private final SearchCasesResultField.SearchCasesResultFieldBuilder field;

    ResultFieldBuilder(String id, String label) {
      this.field = SearchCasesResultField.builder().id(id).label(label);
    }

    /**
     * Scope this row to a role, emitting its name in the {@code UserRole}/{@code AccessProfile}
     * column instead of the default empty value.
     */
    public ResultFieldBuilder role(HasRole role) {
      field.userRole(role);
      return this;
    }

    /**
     * Set the {@code UseCase} for this row, overriding the default {@code orgcases}.
     */
    public ResultFieldBuilder useCase(String useCase) {
      field.useCase(useCase);
      return this;
    }

    /**
     * {@code DisplayContextParameter} for this row.
     */
    public ResultFieldBuilder displayContextParameter(String displayContextParameter) {
      field.displayContextParameter(displayContextParameter);
      return this;
    }

    /**
     * Search within a complex field: the dotted path of the leaf element to expose.
     */
    public ResultFieldBuilder listElementCode(String listElementCode) {
      field.listElementCode(listElementCode);
      return this;
    }

    /**
     * {@code ResultsOrdering} for this row.
     */
    public ResultFieldBuilder resultsOrdering(String resultsOrdering) {
      field.resultsOrdering(resultsOrdering);
      return this;
    }

    SearchCasesResultField build() {
      return field.build();
    }
  }
}
